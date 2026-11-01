"""Capa de control: decide en cada paso si indexar un libro pendiente o descargar uno nuevo."""
import time
from pathlib import Path

from .control import Control
from .datalake import Datalake, utc_now
from .index_stores import open_store, search_ids
from .metadata import MetadataStore, parse_header
from .source import open_source, BookNotFound, DownloadError, DEFAULT_BASE
from .spec import load_ids
from .splitter import split_book, NoMarkersError
from .tokenizer import tokenize, term_frequencies


class Pipeline:
    def __init__(self, data_dir, layout="range", store="sqlite", source=DEFAULT_BASE,
                 candidates=None, delay=0.0, clock=utc_now, log=print, **store_options):
        self.data = Path(data_dir)
        self.lake = Datalake(self.data / "datalake", layout, clock)
        self.control = Control(self.data / "control")
        self.source = open_source(source)
        self.store = open_store(store, self.data / "datamarts", **store_options)
        self.store.open()
        self.metadata = MetadataStore(self.data / "datamarts" / "metadata.db")
        self.candidates = candidates if candidates is not None else load_ids()
        self.delay = delay
        self.log = log

    def reconcile(self):
        stats = self.lake.reconcile(self.control)
        if any(stats.values()):
            self.log(f"[CONTROL] reconciliacion: {stats}")
        return stats

    def step(self):
        pending = self.control.pending()
        if pending:
            book_id = min(pending)
            self.log(f"[CONTROL] indexando libro {book_id}")
            self.index_book(book_id)
            return "indexed", book_id

        done = self.control.downloaded() | set(self.control.failed())
        for book_id in self.candidates:
            if book_id not in done:
                self.log(f"[CONTROL] descargando libro {book_id}")
                ok = self.download_book(book_id)
                return ("downloaded" if ok else "failed"), book_id
        return "idle", None

    def run(self, steps):
        self.reconcile()
        for _ in range(steps):
            action, book_id = self.step()
            if action == "idle":
                self.log("[CONTROL] no queda nada por hacer")
                break

    def download_book(self, book_id):
        if self.delay:
            time.sleep(self.delay)  # cortesia con gutenberg.org
        try:
            header, body = split_book(self.source.fetch(book_id))
        except (BookNotFound, NoMarkersError, DownloadError) as e:
            reason = {BookNotFound: "not_found", NoMarkersError: "no_markers"}.get(type(e), "download_error")
            self.control.mark_failed(book_id, reason)
            self.log(f"[CONTROL] libro {book_id} descartado: {reason}")
            return False
        self.lake.write_book(book_id, header, body)
        self.control.mark_downloaded(book_id)  # solo cuando los dos ficheros ya estan en su sitio
        return True

    def index_book(self, book_id):
        header_path, body_path = self.lake.locate(book_id)
        header = header_path.read_text(encoding="utf-8")
        body = body_path.read_text(encoding="utf-8")
        self.metadata.upsert(book_id, parse_header(header),
                             self.lake.relative(header_path), self.lake.relative(body_path))
        self.store.add_book(book_id, term_frequencies(body))
        self.control.mark_indexed(book_id)

    def close(self):
        self.store.close()
        self.metadata.close()


def search(data_dir, store_name, query, **store_options):
    data = Path(data_dir)
    store = open_store(store_name, data / "datamarts", **store_options)
    store.open()
    metadata = MetadataStore(data / "datamarts" / "metadata.db")
    terms = list(dict.fromkeys(tokenize(query)))
    ids = search_ids(store, terms) if terms else []
    results = []
    for book_id in ids:
        meta = metadata.get(book_id) or {"book_id": book_id}
        results.append({k: meta.get(k) for k in ("book_id", "title", "author", "language", "year")})
    store.close()
    metadata.close()
    return {"query": query, "terms": terms, "total": len(results), "results": results}
