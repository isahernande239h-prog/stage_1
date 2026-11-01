"""Acceso a los ficheros compartidos de spec/ (los mismos para los tres lenguajes)."""
import os
from pathlib import Path

SPEC_DIR = Path(os.environ.get("STAGE1_SPEC", Path(__file__).resolve().parents[2] / "spec"))


def _lines(path):
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip() and not line.startswith("#")]


def load_stopwords():
    return frozenset(_lines(SPEC_DIR / "stopwords.txt"))


def load_languages():
    table = {}
    for line in _lines(SPEC_DIR / "languages.tsv"):
        name, code = line.split("\t")
        table[name] = code
    return table


def load_queries(path=None):
    return _lines(path or SPEC_DIR / "queries.txt")


def load_ids(path=None):
    return [int(x) for x in _lines(path or SPEC_DIR / "corpus_ids.txt")]
