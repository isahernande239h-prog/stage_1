"""Orchestrator for the stage 1 benchmarks.

Runs the benchmark commands of the three languages as separate processes (each
configuration in a new process with an empty work folder), measures the peak memory of the
process from outside and saves one row per run in benchmarks/results/raw/<campaign>.csv.
If it gets cut off, running it again skips the runs that are already in the CSV.

    python benchmarks/bench.py env
    python benchmarks/bench.py equivalence
    python benchmarks/bench.py datalake --reps 5
    python benchmarks/bench.py recovery --reps 3
    python benchmarks/bench.py mirror --reps 3
    python benchmarks/bench.py live
    python benchmarks/bench.py index --reps 3

Requirements: the Java jar built, `npm install` done in javascript/ and mongod running.
"""
import argparse
import csv
import json
import platform
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "benchmarks"))

from searchengine.control import Control  # noqa: E402
from searchengine.datalake import Datalake  # noqa: E402
from mirror_server import serve  # noqa: E402

RESULTS = ROOT / "benchmarks" / "results"
LANGS = ("python", "java", "javascript")
LAYOUTS = ("time", "book", "range")
STORES = ("json", "hier", "mongo", "sqlite")
IDS = ROOT / "spec" / "corpus_ids.txt"
MIRROR_PORT = 8765


def command(lang):
    if lang == "python":
        return [sys.executable, "-m", "searchengine"], ROOT / "python"
    if lang == "java":
        return ["java", "-Xms256m", "-Xmx3g", "-jar", str(ROOT / "java" / "target" / "stage1.jar")], ROOT
    return ["node", "--max-old-space-size=3072", str(ROOT / "javascript" / "src" / "cli.js")], ROOT


def find_mongod():
    for p in psutil.process_iter(["name"]):
        if p.info["name"] == "mongod":
            return p
    return None


class PeakMemory(threading.Thread):
    """Samples the resident memory of the process (and its children) every 20 ms."""

    def __init__(self, pid, extra=None):
        super().__init__(daemon=True)
        self.proc = psutil.Process(pid)
        self.extra = extra
        self.peak = 0
        self.extra_peak = 0
        self.done = threading.Event()

    def run(self):
        while not self.done.is_set():
            try:
                rss = self.proc.memory_info().rss
                for child in self.proc.children(recursive=True):
                    rss += child.memory_info().rss
                self.peak = max(self.peak, rss)
                if self.extra is not None:
                    self.extra_peak = max(self.extra_peak, self.extra.memory_info().rss)
            except psutil.Error:
                pass
            self.done.wait(0.02)


def run(lang, args, expect=(0,), mongo=False):
    base, cwd = command(lang)
    start = time.perf_counter()
    proc = subprocess.Popen(base + [str(a) for a in args], cwd=cwd, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    sampler = PeakMemory(proc.pid, find_mongod() if mongo else None)
    sampler.start()
    out, err = proc.communicate()
    sampler.done.set()
    sampler.join()
    if proc.returncode not in expect:
        raise RuntimeError(f"{lang} {' '.join(map(str, args))} -> exit {proc.returncode}\n{err[-3000:]}")
    lines = [line for line in out.splitlines() if line.startswith("{")]
    result = json.loads(lines[-1]) if lines else {}
    result.update(peak_rss_mb=round(sampler.peak / 1e6, 1), wall_s=round(time.perf_counter() - start, 3))
    if mongo:
        result["mongod_rss_mb"] = round(sampler.extra_peak / 1e6, 1)
    return result


def clean(path):
    shutil.rmtree(path, ignore_errors=True)
    path.mkdir(parents=True)


class Campaign:
    """CSV of one campaign. Rewrites the whole file on every row (there are only a few hundred)."""

    def __init__(self, name):
        self.path = RESULTS / "raw" / f"{name}.csv"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.rows = []
        if self.path.exists():
            with open(self.path, newline="", encoding="utf-8") as f:
                self.rows = list(csv.DictReader(f))
        self.done = {row["run_id"] for row in self.rows}

    def add(self, run_id, row):
        flat = {"run_id": run_id}
        for key, value in row.items():
            flat[key] = json.dumps(value) if isinstance(value, (dict, list)) else value
        self.rows.append(flat)
        self.done.add(run_id)
        fields = []
        for r in self.rows:
            fields.extend(k for k in r if k not in fields)
        with open(self.path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerows(self.rows)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


# ------------------------------------------------------------------ campaigns

def campaign_datalake(opts):
    camp = Campaign("datalake")
    work = opts.work / "datalake"
    for rep in range(opts.reps):
        for n in opts.sizes or (50, 100, 200, 400):
            for layout in LAYOUTS:
                for lang in opts.langs:
                    run_id = f"{lang}|{layout}|{n}|{rep}"
                    if run_id in camp.done:
                        continue
                    clean(work)
                    r = run(lang, ["bench-datalake", "--data", work, "--layout", layout,
                                   "--source", f"cache:{opts.cache}", "--ids", IDS, "--n", n])
                    camp.add(run_id, {"lang": lang, "layout": layout, "n": n, "rep": rep, **r})
                    log(f"datalake {run_id}: {r['books_per_s']:.0f} books/s")


def check_state(work, layout, expected):
    lake = Datalake(work / "datalake", layout)
    scan = lake.scan()
    duplicates = sum(len(p) - 1 for p in scan.complete.values())
    missing = len(set(expected) - set(scan.complete))
    aligned = Control(work / "control").downloaded() == set(scan.complete)
    consistent = not missing and not duplicates and not scan.orphans and not scan.temps and aligned
    return {"complete": len(scan.complete), "missing": missing, "duplicates": duplicates,
            "orphans": len(scan.orphans), "temps": len(scan.temps),
            "control_aligned": aligned, "consistent": consistent}


def campaign_recovery(opts):
    """Crash during book 51 of 100 and resume 24 h later, with and without reconciliation."""
    camp = Campaign("recovery")
    work = opts.work / "recovery"
    expected = [int(x) for x in IDS.read_text().split()][:100]
    common = ["--source", f"cache:{opts.cache}", "--ids", IDS, "--n", 100]
    for rep in range(opts.reps):
        for layout in LAYOUTS:
            for crash_at in ("between_renames", "before_control"):
                for mode in ("naive", "reconcile"):
                    for lang in opts.langs:
                        run_id = f"{lang}|{layout}|{crash_at}|{mode}|{rep}"
                        if run_id in camp.done:
                            continue
                        clean(work)
                        run(lang, ["ingest", "--data", work, "--layout", layout, *common,
                                   "--crash-after", 50, "--crash-at", crash_at], expect=(3,))
                        extra = ["--reconcile"] if mode == "reconcile" else []
                        r = run(lang, ["ingest", "--data", work, "--layout", layout, *common,
                                       "--clock-offset-hours", 24, *extra])
                        state = check_state(work, layout, expected)
                        camp.add(run_id, {"lang": lang, "layout": layout, "crash_at": crash_at, "mode": mode,
                                          "rep": rep, "reconcile_ms": r.get("reconcile_ms"), **state})
                        log(f"recovery {run_id}: consistent={state['consistent']}")


def campaign_mirror(opts):
    """Ingestion through the HTTP client of each language, against a local mirror."""
    camp = Campaign("mirror")
    work = opts.work / "mirror"
    server = serve(opts.cache, MIRROR_PORT)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for rep in range(opts.reps):
            for layout in LAYOUTS:
                for lang in opts.langs:
                    run_id = f"{lang}|{layout}|{rep}"
                    if run_id in camp.done:
                        continue
                    clean(work)
                    r = run(lang, ["ingest", "--data", work, "--layout", layout, "--source",
                                   f"http://127.0.0.1:{MIRROR_PORT}", "--ids", IDS, "--n", 400])
                    camp.add(run_id, {"lang": lang, "layout": layout, "n": 400, "rep": rep, **r})
                    log(f"mirror {run_id}: {r['books_per_s']:.0f} books/s")
    finally:
        server.shutdown()


def campaign_live(opts):
    """A single pass against gutenberg.org with 10 books per language (just a rough reference)."""
    camp = Campaign("live")
    work = opts.work / "live"
    for lang in opts.langs:
        run_id = f"{lang}|range|0"
        if run_id in camp.done:
            continue
        clean(work)
        r = run(lang, ["ingest", "--data", work, "--layout", "range", "--source", "https://www.gutenberg.org",
                       "--ids", IDS, "--n", 10])
        camp.add(run_id, {"lang": lang, "layout": "range", "n": 10, "rep": 0, **r})
        log(f"live {run_id}: {r['books_per_s']:.2f} books/s")
        time.sleep(5)


def prepare_lake(opts):
    """Shared datalake (range layout) that all the index runs read from."""
    lake = opts.work / "index_lake"
    if not (lake / "control" / "downloaded_books.txt").exists():
        clean(lake)
        run("python", ["ingest", "--data", lake, "--layout", "range", "--source", f"cache:{opts.cache}",
                       "--ids", IDS])
    return lake / "datalake"


def drop_mongo(db):
    from pymongo import MongoClient
    client = MongoClient("mongodb://127.0.0.1:27017")
    client.drop_database(db)
    client.close()


def campaign_index(opts):
    camp = Campaign("index")
    lake = prepare_lake(opts)
    work = opts.work / "index"
    for rep in range(opts.reps):
        for n in opts.sizes or (25, 50, 100, 200, 400):
            for store in opts.stores or STORES:
                for lang in opts.langs:
                    run_id = f"{lang}|{store}|{n}|{rep}"
                    if run_id in camp.done:
                        continue
                    clean(work)
                    r = run(lang, ["bench-index", "--data", work, "--datalake", lake, "--ids", IDS, "--n", n,
                                   "--add", 10, "--store", store, "--mongo-db", f"bench_{lang}"],
                            mongo=store == "mongo")
                    if store == "mongo":
                        drop_mongo(f"bench_{lang}")
                    camp.add(run_id, {"lang": lang, "store": store, "n": n, "rep": rep, **r})
                    log(f"index {run_id}: build {r['build_s']:.2f} s, p50 {r['query_p50_ms']:.3f} ms")
    shutil.rmtree(work, ignore_errors=True)


def campaign_equivalence(opts):
    """Same metadata and index in all 12 cases (3 languages x 4 stores)."""
    lake = prepare_lake(opts)
    out = opts.work / "canonical"
    clean(out)
    results = {}
    for store in STORES:
        for lang in opts.langs:
            work = out / f"{lang}_{store}"
            work.mkdir()
            results[(lang, store)] = run(lang, ["canonical", "--data", work, "--datalake", lake, "--ids", IDS,
                                                "--n", 55, "--add", 5, "--store", store,
                                                "--mongo-db", f"canon_{lang}", "--out", out / f"{lang}_{store}"])
            if store == "mongo":
                drop_mongo(f"canon_{lang}")

    meta = {r["metadata_sha256"] for r in results.values()}
    post = {r["postings_sha256"] for r in results.values()}
    lines = ["Canonical output: 55 books in bulk + 5 incremental", "",
             "language    store        metadata  postings  sha256 metadata   sha256 postings"]
    for (lang, store), r in results.items():
        lines.append(f"{lang:<11} {store:<11} {r['metadata_lines']:>9}  {r['postings_lines']:>8}  "
                     f"{r['metadata_sha256'][:16]}  {r['postings_sha256'][:16]}")
    ok = len(meta) == 1 and len(post) == 1
    lines += ["", "IDENTICAL in all 12 cases" if ok else "DIFFERENCES FOUND"]
    (RESULTS / "equivalence.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    if not ok:
        sys.exit(1)


def campaign_env(opts):
    def output(cmd):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True)
            return (p.stdout + p.stderr).strip().splitlines()[0]
        except OSError:
            return None

    env = {
        "cpu": output(["sysctl", "-n", "machdep.cpu.brand_string"]),
        "cores_logical": psutil.cpu_count(),
        "cores_physical": psutil.cpu_count(logical=False),
        "ram_gb": round(psutil.virtual_memory().total / 2 ** 30, 1),
        "os": platform.platform(),
        "python": platform.python_version(),
        "sqlite_python": sqlite3.sqlite_version,
        "java": output(["java", "-version"]),
        "node": output(["node", "--version"]),
        "mongod": output(["mongod", "--version"]),
        "jvm_flags": "-Xms256m -Xmx3g",
        "node_flags": "--max-old-space-size=3072",
        "corpus_books": len(IDS.read_text().split()),
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "env.json").write_text(json.dumps(env, indent=2), encoding="utf-8")
    print(json.dumps(env, indent=2))


CAMPAIGNS = {"env": campaign_env, "equivalence": campaign_equivalence, "datalake": campaign_datalake,
             "recovery": campaign_recovery, "mirror": campaign_mirror, "live": campaign_live,
             "index": campaign_index}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("campaign", choices=list(CAMPAIGNS) + ["all"])
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--sizes", type=int, nargs="*")
    ap.add_argument("--stores", nargs="*")
    ap.add_argument("--langs", nargs="*", default=list(LANGS))
    ap.add_argument("--cache", type=Path, default=Path.home() / "bd_work" / "raw_cache")
    ap.add_argument("--work", type=Path, default=Path.home() / "bd_work" / "runs")
    opts = ap.parse_args()
    names = list(CAMPAIGNS) if opts.campaign == "all" else [opts.campaign]
    for name in names:
        log(f"== {name}")
        CAMPAIGNS[name](opts)


if __name__ == "__main__":
    main()
