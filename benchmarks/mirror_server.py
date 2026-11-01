"""Espejo HTTP local de gutenberg.org: sirve /cache/epub/<id>/pg<id>.txt desde la cache.

Sirve para medir la ingesta con el cliente HTTP real de cada lenguaje sin depender de Internet
ni cargar el servidor de Gutenberg.

    python benchmarks/mirror_server.py --cache ~/bd_work/raw_cache --port 8765
"""
import argparse
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PATH_RE = re.compile(r"^/cache/epub/(\d+)/pg(\d+)\.txt$")


def make_handler(cache):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            m = PATH_RE.match(self.path)
            path = cache / f"pg{m.group(1)}.txt" if m and m.group(1) == m.group(2) else None
            if path is None or not path.exists():
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            data = path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    return Handler


def serve(cache, port):
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(Path(cache).expanduser()))
    server.daemon_threads = True
    return server


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    print(f"espejo en http://127.0.0.1:{args.port}", flush=True)
    serve(args.cache, args.port).serve_forever()
