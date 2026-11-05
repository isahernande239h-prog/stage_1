"""Reading books: from gutenberg.org (or a mirror with the same URLs) or from a local cache."""
import time
from pathlib import Path

import requests

DEFAULT_BASE = "https://www.gutenberg.org"
USER_AGENT = "ulpgc-bigdata-stage1/1.0 (class project)"
ATTEMPTS = 3


class BookNotFound(Exception):
    pass


class DownloadError(Exception):
    pass


def decode(raw):
    text = raw.decode("utf-8", errors="replace")
    if text.startswith("﻿"):
        text = text[1:]
    return text


class HttpSource:
    def __init__(self, base=DEFAULT_BASE):
        self.base = base.rstrip("/")
        self.session = requests.Session()
        # no compression, so we can compare Content-Length with what we got
        self.session.headers.update({"User-Agent": USER_AGENT, "Accept-Encoding": "identity"})

    def url(self, book_id):
        return f"{self.base}/cache/epub/{book_id}/pg{book_id}.txt"

    def fetch(self, book_id):
        return decode(self.fetch_raw(book_id))

    def fetch_raw(self, book_id):
        last_error = None
        for attempt in range(ATTEMPTS):
            if attempt > 0:
                time.sleep(2 ** (attempt - 1))  # 1 s and then 2 s
            try:
                resp = self.session.get(self.url(book_id), timeout=(10, 30))
            except requests.RequestException as e:
                last_error = str(e)
                continue
            if resp.status_code == 404:
                raise BookNotFound(book_id)
            if resp.status_code == 429 or resp.status_code >= 500:
                last_error = f"HTTP {resp.status_code}"
                continue
            if resp.status_code != 200:
                raise DownloadError(f"HTTP {resp.status_code}")
            expected = resp.headers.get("Content-Length")
            if expected is not None and int(expected) != len(resp.content):
                last_error = "incomplete"
                continue
            return resp.content
        raise DownloadError(last_error)


class CacheSource:
    """Reads pg<id>.txt from a folder. Handy for repeating benchmarks without downloading again."""

    def __init__(self, folder):
        self.folder = Path(folder)

    def fetch(self, book_id):
        path = self.folder / f"pg{book_id}.txt"
        if not path.exists():
            raise BookNotFound(book_id)
        return decode(path.read_bytes())


def open_source(spec):
    if spec.startswith("cache:"):
        return CacheSource(spec[len("cache:"):])
    return HttpSource(spec)
