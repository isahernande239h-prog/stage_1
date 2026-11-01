"""Comandos de benchmark. Cada uno ejecuta una configuracion y escribe un JSON con las medidas.

El orquestador (benchmarks/bench.py) los lanza en un proceso nuevo para los tres lenguajes y
mide desde fuera la memoria del proceso.
"""
import hashlib
import json
import sys
import time
from pathlib import Path

from .control import Control, write_text
from .datalake import Datalake, SyntheticClock, SimulatedCrash, utc_now
from .index_stores import open_store, search_ids
from .metadata import MetadataStore, parse_header
from .source import open_source, BookNotFound
from .spec import load_ids, load_queries
from .splitter import split_book, NoMarkersError
from .tokenizer import tokenize, term_frequencies

CRASH_EXIT = 3


def now():
    return time.perf_counter()


def emit(result):
    json.dump(result, sys.stdout)
    print()


# ---------------------------------------------------------------- datalake

def make_clock(args):
    if args.clock == "synthetic":
        return SyntheticClock(books_per_hour=10, offset_hours=args.clock_offset_hours)
    return utc_now


def ingest_books(lake, control, source, ids, clock, crash_after=None, crash_at=None, delay=0.0):
    """Descarga, separa y guarda los ids que no esten ya en el control. Devuelve (ingeridos, fallidos, bytes)."""
    done = control.downloaded() | set(control.failed())
    ingested = failed = written = 0
    for book_id in ids:
        if book_id in done:
            continue
        if delay:
            time.sleep(delay)
        try:
            header, body = split_book(source.fetch(book_id))
        except (BookNotFound, NoMarkersError) as e:
            control.mark_failed(book_id, "not_found" if isinstance(e, BookNotFound) else "no_markers")
            failed += 1
            continue
        crash_now = crash_after is not None and ingested == crash_after
        lake.write_book(book_id, header, body, crash_between_renames=crash_now and crash_at == "between_renames")
        if crash_now and crash_at == "before_control":
            raise SimulatedCrash("antes del control")
        control.mark_downloaded(book_id)
        if isinstance(clock, SyntheticClock):
            clock.tick()
        ingested += 1
        written += len(header.encode("utf-8")) + len(body.encode("utf-8"))
    return ingested, failed, written


def cmd_ingest(args):
    data = Path(args.data)
    clock = make_clock(args)
    lake = Datalake(data / "datalake", args.layout, clock)
    control = Control(data / "control")
    result = {"reconcile_ms": None}
    if args.reconcile:
        t = now()
        result["reconcile"] = lake.reconcile(control)
        result["reconcile_ms"] = (now() - t) * 1000

    ids = load_ids(args.ids)[:args.n]
    t = now()
    try:
        ingested, failed, written = ingest_books(lake, control, open_source(args.source), ids, clock,
                                                 args.crash_after, args.crash_at, args.delay)
    except SimulatedCrash as e:
        emit({"crashed": str(e)})
        sys.exit(CRASH_EXIT)
    seconds = now() - t
    result.update({"ingested": ingested, "failed": failed, "seconds": seconds, "bytes": written,
                   "books_per_s": ingested / seconds if seconds else 0,
                   "mb_per_s": written / 1e6 / seconds if seconds else 0})
    emit(result)


def cmd_bench_datalake(args):
    data = Path(args.data)
    clock = make_clock(args)
    lake = Datalake(data / "datalake", args.layout, clock)
    control = Control(data / "control")
    ids = load_ids(args.ids)[:args.n]
    r = {"layout": args.layout, "n": len(ids)}

    # 1) escritura
    t = now()
    ingested, _, written = ingest_books(lake, control, open_source(args.source), ids, clock)
    seconds = now() - t
    r.update(ingested=ingested, write_s=seconds, books_per_s=ingested / seconds, mb_per_s=written / 1e6 / seconds)

    # 2) localizar un libro con la ruta calculada (o el registro en time)
    lake = Datalake(data / "datalake", args.layout)
    t = now()
    if args.layout == "time":
        lake.load_registry()
    r["registry_load_ms"] = (now() - t) * 1000
    t = now()
    for book_id in ids:
        assert lake.locate(book_id) is not None
    r["lookup_direct_us"] = (now() - t) / len(ids) * 1e6

    # 3) localizar recorriendo el arbol (sin estructuras auxiliares)
    sample = ids[::max(1, len(ids) // 10)][:10]
    t = now()
    for book_id in sample:
        assert book_id in lake.scan().complete
    r["lookup_scan_ms"] = (now() - t) / len(sample) * 1000

    # 4) detectar los libros nuevos (los ultimos args.new no estan indexados)
    indexed, expected = ids[:-args.new], set(ids[-args.new:])
    write_text(control.indexed_file, "".join(f"{i}\n" for i in indexed))

    t = now()
    found = Control(data / "control").pending()
    r["detect_control_ms"] = (now() - t) * 1000
    ok = found == expected

    t = now()
    found = lake.scan().ids() - Control(data / "control").indexed()
    r["detect_scan_ms"] = (now() - t) * 1000
    ok = ok and found == expected

    if args.layout == "time":
        last = max(lake.load_registry()[i] for i in indexed)
        t = now()
        found = lake.scan(min_partition=last).ids() - Control(data / "control").indexed()
        r["detect_pruned_ms"] = (now() - t) * 1000
        ok = ok and found == expected
    r["detect_ok"] = ok

    # 5) espacio
    stats = lake.storage_stats()
    r.update({f"storage_{k}": v for k, v in stats.items()})
    r["control_bytes"] = sum(f.stat().st_size for f in (data / "control").iterdir())
    emit(r)


# ---------------------------------------------------------------- indice

def build_index(lake, ids):
    index = {}
    tokens = 0
    for book_id in ids:
        _, body = lake.read_book(book_id)
        for term, tf in term_frequencies(body).items():
            index.setdefault(term, {})[book_id] = tf
            tokens += tf
    return index, tokens


def percentile(values, p):
    values = sorted(values)
    k = (len(values) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def store_options(args):
    return {"mongo_uri": args.mongo_uri, "mongo_db": args.mongo_db}


def cmd_bench_index(args):
    datamarts = Path(args.data) / "datamarts"
    lake = Datalake(args.datalake, "range")
    all_ids = load_ids(args.ids)
    ids, extra = all_ids[:args.n], all_ids[args.n:args.n + args.add]
    r = {"store": args.store, "n": len(ids)}

    store = open_store(args.store, datamarts, **store_options(args))
    t0 = now()
    index, tokens = build_index(lake, ids)
    t1 = now()
    store.write_full(index)
    t2 = now()
    r.update(tokens=tokens, terms=len(index), postings=sum(len(p) for p in index.values()),
             tokenize_s=t1 - t0, persist_s=t2 - t1, build_s=t2 - t0)
    del index
    r["disk_bytes"] = store.disk_usage()
    store.close()

    store = open_store(args.store, datamarts, **store_options(args))
    t = now()
    store.open()
    r["open_ms"] = (now() - t) * 1000

    queries = [(q, tokenize(q)) for q in load_queries(args.queries)]
    lines = [f"{q}\t{','.join(map(str, search_ids(store, terms)))}" for q, terms in queries]  # calentamiento
    r["queries_digest"] = hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()
    times = []
    for _ in range(args.query_reps):
        for _, terms in queries:
            t = now()
            search_ids(store, terms)
            times.append((now() - t) * 1000)
    r.update(query_mean_ms=sum(times) / len(times), query_p50_ms=percentile(times, 0.5),
             query_p95_ms=percentile(times, 0.95))

    add_terms = 0
    add_seconds = 0.0
    for book_id in extra:
        tfs = term_frequencies(lake.read_book(book_id)[1])
        add_terms += len(tfs)
        t = now()
        store.add_book(book_id, tfs)
        add_seconds += now() - t
    r.update(add_books=len(extra), add_terms=add_terms, add_ms=add_seconds * 1000)
    store.close()
    emit(r)


def cmd_canonical(args):
    """Construye metadatos e indice y los exporta en un formato fijo para comparar lenguajes."""
    datamarts = Path(args.data) / "datamarts"
    datamarts.mkdir(parents=True, exist_ok=True)
    lake = Datalake(args.datalake, "range")
    all_ids = load_ids(args.ids)
    ids, extra = all_ids[:args.n], all_ids[args.n:args.n + args.add]

    metadata = MetadataStore(datamarts / "metadata.db")
    for book_id in ids + extra:
        header_path, body_path = lake.locate(book_id)
        meta = parse_header(header_path.read_text(encoding="utf-8"))
        metadata.upsert(book_id, meta, lake.relative(header_path), lake.relative(body_path))

    store = open_store(args.store, datamarts, **store_options(args))
    index, _ = build_index(lake, ids)
    store.write_full(index)
    del index
    store.open()
    for book_id in extra:
        store.add_book(book_id, term_frequencies(lake.read_book(book_id)[1]))

    def value(v):
        return "" if v is None else str(v)

    meta_lines = sorted("\t".join(value(row[c]) for c in
                                  ("book_id", "title", "author", "language", "year", "release_year"))
                        for row in metadata.all())
    post_lines = sorted(term + "\t" + ",".join(f"{b}:{found[b]}" for b in sorted(found))
                        for term, found in store.terms())
    store.close()
    metadata.close()

    out = Path(args.out)
    result = {"store": args.store}
    for kind, lines in (("metadata", meta_lines), ("postings", post_lines)):
        text = "".join(line + "\n" for line in lines)
        write_text(f"{out}.{kind}.tsv", text)
        result[f"{kind}_lines"] = len(lines)
        result[f"{kind}_sha256"] = hashlib.sha256(text.encode("utf-8")).hexdigest()
    emit(result)


# ---------------------------------------------------------------- argumentos

def add_parsers(sub, add_store_args, layouts):
    p = sub.add_parser("ingest", help="ingesta de una lista de ids (con caida simulada opcional)")
    p.add_argument("--data", required=True)
    p.add_argument("--layout", choices=layouts, default="range")
    p.add_argument("--source", required=True)
    p.add_argument("--ids", required=True)
    p.add_argument("--n", type=int, default=10 ** 9)
    p.add_argument("--clock", choices=("real", "synthetic"), default="synthetic")
    p.add_argument("--clock-offset-hours", type=int, default=0)
    p.add_argument("--crash-after", type=int)
    p.add_argument("--crash-at", choices=("between_renames", "before_control"))
    p.add_argument("--reconcile", action="store_true")
    p.add_argument("--delay", type=float, default=0.0)
    p.set_defaults(func=cmd_ingest)

    p = sub.add_parser("bench-datalake")
    p.add_argument("--data", required=True)
    p.add_argument("--layout", choices=layouts, required=True)
    p.add_argument("--source", required=True)
    p.add_argument("--ids", required=True)
    p.add_argument("--n", type=int, required=True)
    p.add_argument("--new", type=int, default=10)
    p.add_argument("--clock", choices=("real", "synthetic"), default="synthetic")
    p.add_argument("--clock-offset-hours", type=int, default=0)
    p.set_defaults(func=cmd_bench_datalake)

    p = sub.add_parser("bench-index")
    p.add_argument("--data", required=True)
    p.add_argument("--datalake", required=True, help="datalake ya construido (layout range)")
    p.add_argument("--ids", required=True)
    p.add_argument("--n", type=int, required=True)
    p.add_argument("--add", type=int, default=10)
    p.add_argument("--queries")
    p.add_argument("--query-reps", type=int, default=5)
    add_store_args(p)
    p.set_defaults(func=cmd_bench_index)

    p = sub.add_parser("canonical")
    p.add_argument("--data", required=True)
    p.add_argument("--datalake", required=True)
    p.add_argument("--ids", required=True)
    p.add_argument("--n", type=int, default=55)
    p.add_argument("--add", type=int, default=5)
    p.add_argument("--out", required=True)
    add_store_args(p)
    p.set_defaults(func=cmd_canonical)
