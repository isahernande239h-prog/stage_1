"""Tablas y graficas a partir de los CSV de benchmarks/results/raw/.

    python benchmarks/analyze.py

Escribe benchmarks/results/tables.md (tablas en markdown, media ± desviacion tipica de las
repeticiones) y las figuras en benchmarks/results/figures/. El informe usa estas mismas tablas.
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "results" / "raw"
FIG = ROOT / "results" / "figures"

LANGS = ["python", "java", "javascript"]
LANG_NAME = {"python": "Python", "java": "Java", "javascript": "JavaScript"}
LAYOUTS = ["time", "book", "range"]
STORES = ["json", "hier", "mongo", "sqlite"]
COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]
MARKERS = ["o", "s", "^", "D", "v"]
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"

plt.rcParams.update({
    "font.size": 9, "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2, "ytick.color": INK2,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "axes.axisbelow": True,
    "axes.spines.top": False, "axes.spines.right": False, "legend.frameon": False, "figure.dpi": 150,
    "lines.linewidth": 2, "lines.markersize": 5,
})


def load(name):
    path = RAW / f"{name}.csv"
    if not path.exists():
        return []
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            for k, v in row.items():
                try:
                    row[k] = int(v)
                except (TypeError, ValueError):
                    try:
                        row[k] = float(v)
                    except (TypeError, ValueError):
                        pass
            rows.append(row)
    return rows


def group(rows, *keys):
    out = defaultdict(list)
    for r in rows:
        out[tuple(r[k] for k in keys)].append(r)
    return out


def values(rows, key):
    return [r[key] for r in rows if isinstance(r.get(key), (int, float))]


def mean(rows, key):
    v = values(rows, key)
    return statistics.mean(v) if v else float("nan")


def ms(rows, key, digits=1):
    """'media ± sd' como texto."""
    v = values(rows, key)
    if not v:
        return "-"
    sd = statistics.stdev(v) if len(v) > 1 else 0.0
    return f"{statistics.mean(v):.{digits}f} ± {sd:.{digits}f}"


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(lines)


def save(fig, name):
    FIG.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(FIG / f"{name}.png", bbox_inches="tight")
    plt.close(fig)


def top_legend(fig, ax, ncol):
    """Leyenda encima de toda la figura (si va dentro de un eje, tight_layout descoloca los paneles)."""
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, ncol=ncol, loc="lower center", bbox_to_anchor=(0.5, 1.0), fontsize=8)


def bars(ax, groups, series, value, error=None, labels=None):
    """Barras agrupadas: un grupo por elemento de groups y una barra por serie."""
    width = 0.8 / len(series)
    for i, s in enumerate(series):
        xs = [g + (i - (len(series) - 1) / 2) * width for g in range(len(groups))]
        ys = [value(g, s) for g in groups]
        errs = [error(g, s) for g in groups] if error else None
        ax.bar(xs, ys, width * 0.92, yerr=errs, color=COLORS[i], label=labels[s] if labels else s,
               error_kw={"elinewidth": 0.8, "capsize": 2, "ecolor": INK2})
    ax.set_xticks(range(len(groups)))
    ax.set_xticklabels(groups)


def sd(rows, key):
    v = values(rows, key)
    return statistics.stdev(v) if len(v) > 1 else 0.0


# ------------------------------------------------------------------ datalake

def datalake_section(out):
    rows = load("datalake")
    if not rows:
        return
    by = group(rows, "layout", "lang", "n")
    nmax = max(r["n"] for r in rows)

    out.append(f"### Datalake con {nmax} libros (escritura desde la cache local, localizacion y memoria)\n")
    out.append(table(["layout", "lenguaje", "libros/s", "MB/s", "lookup directo (µs)", "carga registro (ms)",
                      "lookup escaneo (ms)", "pico RAM (MB)"],
                     [[lay, LANG_NAME[lang], ms(g, "books_per_s"), ms(g, "mb_per_s"), ms(g, "lookup_direct_us"),
                       ms(g, "registry_load_ms", 2), ms(g, "lookup_scan_ms", 2), ms(g, "peak_rss_mb", 0)]
                      for lay in LAYOUTS for lang in LANGS for g in [by[(lay, lang, nmax)]] if g]))

    fig, ax = plt.subplots(figsize=(6.2, 2.8))
    bars(ax, LAYOUTS, LANGS, lambda l, s: mean(by[(l, s, nmax)], "books_per_s"),
         lambda l, s: sd(by[(l, s, nmax)], "books_per_s"), LANG_NAME)
    ax.set_ylabel("libros por segundo")
    ax.legend(ncol=3, loc="upper center", bbox_to_anchor=(0.5, 1.15))
    save(fig, "datalake_throughput")

    fig, (a1, a2) = plt.subplots(1, 2, figsize=(7.2, 2.8))
    bars(a1, LAYOUTS, LANGS, lambda l, s: mean(by[(l, s, nmax)], "lookup_direct_us"),
         lambda l, s: sd(by[(l, s, nmax)], "lookup_direct_us"), LANG_NAME)
    a1.set_title("ruta calculada / registro", color=INK, fontsize=9)
    a1.set_ylabel("µs por libro")
    bars(a2, LAYOUTS, LANGS, lambda l, s: mean(by[(l, s, nmax)], "lookup_scan_ms"),
         lambda l, s: sd(by[(l, s, nmax)], "lookup_scan_ms"), LANG_NAME)
    a2.set_title("recorriendo el arbol", color=INK, fontsize=9)
    a2.set_ylabel("ms por libro")
    top_legend(fig, a1, 3)
    save(fig, "datalake_lookup")

    # deteccion de libros nuevos
    sizes = sorted({r["n"] for r in rows})
    out.append("\n### Deteccion de los 10 libros nuevos (ms, media de los tres lenguajes)\n")
    trows = []
    for lay in LAYOUTS:
        for n in sizes:
            g = [r for r in rows if r["layout"] == lay and r["n"] == n]
            trows.append([lay, n, ms(g, "detect_control_ms", 2), ms(g, "detect_scan_ms", 2),
                          ms(g, "detect_pruned_ms", 2) if lay == "time" else "-",
                          "si" if all(str(r["detect_ok"]) == "True" for r in g) else "NO"])
    out.append(table(["layout", "libros", "ficheros de control", "escaneo completo", "escaneo podado", "correcto"], trows))

    fig, axes = plt.subplots(1, 3, figsize=(8.4, 2.7), sharey=True)
    for ax, lang in zip(axes, LANGS):
        series = [("control", "detect_control_ms", None)] + [(f"escaneo {l}", "detect_scan_ms", l) for l in LAYOUTS] \
            + [("escaneo podado time", "detect_pruned_ms", "time")]
        for i, (label, key, lay) in enumerate(series):
            ys = [mean([r for r in rows if r["lang"] == lang and r["n"] == n and (lay is None or r["layout"] == lay)], key)
                  for n in sizes]
            ax.plot(sizes, ys, marker=MARKERS[i], color=COLORS[i], label=label)
        ax.set_title(LANG_NAME[lang], color=INK, fontsize=9)
        ax.set_xlabel("libros en el datalake")
        ax.set_yscale("log")
    axes[0].set_ylabel("ms (escala log)")
    top_legend(fig, axes[0], 5)
    save(fig, "datalake_detection")

    out.append(f"\n### Ocupacion con {nmax} libros (igual en los tres lenguajes)\n")
    trows = []
    for lay in LAYOUTS:
        r = by[(lay, "python", nmax)][0]
        same = len({(x["storage_files"], x["storage_dirs"], x["storage_bytes"]) for lang in LANGS
                    for x in by[(lay, lang, nmax)]}) == 1
        trows.append([lay, r["storage_files"], r["storage_dirs"], f"{r['storage_bytes'] / 1e6:.2f}",
                      f"{r['storage_allocated_bytes'] / 1e6:.2f}", r["storage_aux_bytes"], r["control_bytes"],
                      "si" if same else "NO"])
    out.append(table(["layout", "ficheros", "directorios", "MB", "MB asignados", "bytes registro", "bytes control",
                      "igual en los 3"], trows))


def recovery_section(out):
    rows = load("recovery")
    if not rows:
        return
    out.append("\n### Recuperacion: caida durante el libro 51 de 100 y reanudacion 24 h despues\n")
    trows = []
    crash_name = {"between_renames": "entre renombrados", "before_control": "antes del control"}
    mode_name = {"naive": "solo control", "reconcile": "con reconciliacion"}
    for lay in LAYOUTS:
        for crash in ("between_renames", "before_control"):
            for mode in ("naive", "reconcile"):
                g = [r for r in rows if r["layout"] == lay and r["crash_at"] == crash and r["mode"] == mode]
                if not g:
                    continue
                ok = sum(1 for r in g if str(r["consistent"]) == "True")
                rec = [ms([r for r in g if r["lang"] == lang], "reconcile_ms", 1) if mode == "reconcile" else "-"
                       for lang in LANGS]
                trows.append([lay, crash_name[crash], mode_name[mode], f"{ok}/{len(g)}",
                              f"{mean(g, 'duplicates'):.1f}", f"{mean(g, 'orphans'):.1f}", f"{mean(g, 'temps'):.1f}", *rec])
    out.append(table(["layout", "caida", "reanudacion", "consistentes", "duplicados", "huerfanos", "temporales",
                      "reconciliar Python (ms)", "reconciliar Java (ms)", "reconciliar JS (ms)"], trows))


def ingest_http_section(out):
    mirror, live, dl = load("mirror"), load("live"), load("datalake")
    if not mirror:
        return
    nmax = max(r["n"] for r in dl) if dl else 400
    out.append("\n### Ingesta con el cliente HTTP de cada lenguaje\n")
    trows = []
    for lang in LANGS:
        for lay in LAYOUTS:
            g = [r for r in mirror if r["lang"] == lang and r["layout"] == lay]
            cache = [r for r in dl if r["lang"] == lang and r["layout"] == lay and r["n"] == nmax]
            if g:
                trows.append(["espejo HTTP local", LANG_NAME[lang], lay, g[0]["n"], ms(g, "books_per_s"),
                              f"{mean(cache, 'books_per_s'):.1f}" if cache else "-", len(g)])
    for r in live:
        trows.append(["gutenberg.org (orientativo)", LANG_NAME[r["lang"]], r["layout"], r["n"],
                      f"{r['books_per_s']:.2f}", "-", 1])
    out.append(table(["origen", "lenguaje", "layout", "libros", "libros/s", "libros/s desde cache", "reps"], trows))


# ------------------------------------------------------------------ indice

def index_section(out):
    rows = load("index")
    if not rows:
        return
    sizes = sorted({r["n"] for r in rows})
    nmax = sizes[-1]
    by = group(rows, "store", "lang", "n")

    out.append("\n### Tamano del problema\n")
    trows = []
    for n in sizes:
        g = [r for r in rows if r["n"] == n]
        digests = {r["queries_digest"] for r in g}
        trows.append([n, g[0]["tokens"], g[0]["terms"], g[0]["postings"],
                      "si" if len(digests) == 1 else f"NO ({len(digests)})"])
    out.append(table(["libros", "tokens", "terminos", "postings", "mismas respuestas en las 12 combinaciones"], trows))

    out.append(f"\n### Indice con {nmax} libros\n")
    trows = []
    for store in STORES:
        for lang in LANGS:
            g = by[(store, lang, nmax)]
            if not g:
                continue
            mem = ms(g, "peak_rss_mb", 0)
            if store == "mongo":
                mem += f" (+{mean(g, 'mongod_rss_mb'):.0f} mongod)"
            trows.append([store, LANG_NAME[lang], ms(g, "build_s", 2), ms(g, "tokenize_s", 2), ms(g, "persist_s", 2),
                          ms(g, "open_ms", 1), ms(g, "query_p50_ms", 3), ms(g, "query_p95_ms", 3),
                          f"{mean(g, 'disk_bytes') / 1e6:.1f}", mem])
    out.append(table(["estructura", "lenguaje", "construccion (s)", "tokenizar (s)", "persistir (s)", "apertura (ms)",
                      "consulta p50 (ms)", "consulta p95 (ms)", "disco (MB)", "pico RAM (MB)"], trows))

    out.append("\n### Anadir 10 libros a un indice existente (ms por cada 1000 terminos distintos del libro)\n")
    trows = []
    inc_sizes = [n for n in sizes if mean([r for r in rows if r["n"] == n], "add_books") > 0]
    for store in STORES:
        for lang in LANGS:
            cells = []
            for n in inc_sizes:
                g = by[(store, lang, n)]
                cells.append(f"{1000 * mean(g, 'add_ms') / mean(g, 'add_terms'):.1f}" if g else "-")
            trows.append([store, LANG_NAME[lang], *cells])
    out.append(table(["estructura", "lenguaje", *[f"n={n}" for n in inc_sizes]], trows))

    def per_lang(name, key, ylabel, scale=1.0, log=True):
        fig, axes = plt.subplots(1, 3, figsize=(8.4, 2.7), sharey=True)
        for ax, lang in zip(axes, LANGS):
            for i, store in enumerate(STORES):
                ys = [mean(by[(store, lang, n)], key) * scale for n in sizes]
                ax.plot(sizes, ys, marker=MARKERS[i], color=COLORS[i], label=store)
            ax.set_title(LANG_NAME[lang], color=INK, fontsize=9)
            ax.set_xlabel("libros")
            if log:
                ax.set_yscale("log")
        axes[0].set_ylabel(ylabel)
        top_legend(fig, axes[0], 4)
        save(fig, name)

    per_lang("index_build", "build_s", "segundos (log)")
    per_lang("index_persist", "persist_s", "segundos (log)")
    per_lang("index_query_p50", "query_p50_ms", "ms por consulta (log)")
    per_lang("index_memory", "peak_rss_mb", "MB (pico, log)")

    fig, ax = plt.subplots(figsize=(4.8, 2.8))
    for i, lang in enumerate(LANGS):
        ys = [mean([r for r in rows if r["lang"] == lang and r["n"] == n], "tokens")
              / mean([r for r in rows if r["lang"] == lang and r["n"] == n], "tokenize_s") / 1e6 for n in sizes]
        ax.plot(sizes, ys, marker=MARKERS[i], color=COLORS[i], label=LANG_NAME[lang])
    ax.set_xlabel("libros")
    ax.set_ylabel("millones de tokens/s")
    ax.legend(ncol=3, loc="upper center", bbox_to_anchor=(0.5, 1.18))
    save(fig, "index_tokenize_rate")

    fig, ax = plt.subplots(figsize=(4.8, 2.8))
    for i, store in enumerate(STORES):
        ys = [mean([r for r in rows if r["store"] == store and r["n"] == n], "disk_bytes") / 1e6 for n in sizes]
        ax.plot(sizes, ys, marker=MARKERS[i], color=COLORS[i], label=store)
    ax.set_xlabel("libros")
    ax.set_ylabel("MB en disco (log)")
    ax.set_yscale("log")
    ax.legend(ncol=4, loc="upper center", bbox_to_anchor=(0.5, 1.18))
    save(fig, "index_disk")


def main():
    out = ["# Resultados de los benchmarks (generado por benchmarks/analyze.py)\n"]
    datalake_section(out)
    recovery_section(out)
    ingest_http_section(out)
    index_section(out)
    (ROOT / "results" / "tables.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("tablas ->", ROOT / "results" / "tables.md")
    print("figuras ->", FIG)


if __name__ == "__main__":
    main()
