"""Downloads the benchmark corpus once into a local cache.

The ids are picked at random (fixed seed) from 1..75000 and it keeps the first ones that exist and
have START/END markers. It waits 1 s between requests so it doesn't overload the server.

    python scripts/download_corpus.py --cache ~/bd_work/raw_cache --count 400
"""
import argparse
import random
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

from searchengine.source import HttpSource, BookNotFound, DownloadError, decode  # noqa: E402
from searchengine.splitter import split_book, NoMarkersError  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--count", type=int, default=400)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--max-id", type=int, default=75000)
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--out", default=str(ROOT / "spec" / "corpus_ids.txt"))
    args = ap.parse_args()

    cache = Path(args.cache).expanduser()
    cache.mkdir(parents=True, exist_ok=True)
    candidates = random.Random(args.seed).sample(range(1, args.max_id + 1), args.count * 2)
    source = HttpSource()

    valid, skipped = [], []
    for book_id in candidates:
        if len(valid) == args.count:
            break
        path = cache / f"pg{book_id}.txt"
        if path.exists():
            raw = path.read_bytes()
        else:
            time.sleep(args.delay)
            try:
                raw = source.fetch_raw(book_id)
            except BookNotFound:
                skipped.append((book_id, "not_found"))
                continue
            except DownloadError as e:
                skipped.append((book_id, f"error {e}"))
                continue
        try:
            split_book(decode(raw))
        except NoMarkersError:
            skipped.append((book_id, "no_markers"))
            continue
        if not path.exists():
            path.write_bytes(raw)
        valid.append(book_id)
        if len(valid) % 25 == 0:
            print(f"{len(valid)} books", flush=True)

    Path(args.out).write_text("".join(f"{i}\n" for i in valid), encoding="utf-8")
    print(f"valid: {len(valid)}  skipped: {len(skipped)}")
    for book_id, reason in skipped:
        print(f"  {book_id}\t{reason}")


if __name__ == "__main__":
    main()
