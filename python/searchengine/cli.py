import argparse
import json
import sys

from .datalake import LAYOUTS
from .index_stores import STORES
from .source import DEFAULT_BASE
from .spec import load_ids


def add_store_args(p):
    p.add_argument("--store", choices=STORES, default="sqlite")
    p.add_argument("--mongo-uri", default="mongodb://127.0.0.1:27017")
    p.add_argument("--mongo-db", default="searchengine")


def store_options(args):
    return {"mongo_uri": args.mongo_uri, "mongo_db": args.mongo_db}


def build_parser():
    ap = argparse.ArgumentParser(prog="searchengine")
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("pipeline", help="ejecuta pasos de la capa de control")
    p.add_argument("--data", required=True)
    p.add_argument("--layout", choices=LAYOUTS, default="range")
    p.add_argument("--source", default=DEFAULT_BASE, help="URL base o cache:<carpeta>")
    p.add_argument("--candidates", help="fichero con ids candidatos (por defecto spec/corpus_ids.txt)")
    p.add_argument("--steps", type=int, default=10)
    p.add_argument("--delay", type=float, default=1.0, help="segundos entre descargas")
    add_store_args(p)

    p = sub.add_parser("search", help="busca libros que contengan todos los terminos")
    p.add_argument("--data", required=True)
    add_store_args(p)
    p.add_argument("query", nargs="+")

    from .bench import add_parsers
    add_parsers(sub, add_store_args, LAYOUTS)
    return ap


def main(argv=None):
    args = build_parser().parse_args(argv)

    if args.command == "pipeline":
        from .pipeline import Pipeline
        candidates = load_ids(args.candidates) if args.candidates else None
        pipe = Pipeline(args.data, args.layout, args.store, args.source, candidates,
                        args.delay, **store_options(args))
        pipe.run(args.steps)
        pipe.close()

    elif args.command == "search":
        from .pipeline import search
        result = search(args.data, args.store, " ".join(args.query), **store_options(args))
        json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
        print()

    else:
        args.func(args)
