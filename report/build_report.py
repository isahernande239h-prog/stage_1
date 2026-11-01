"""Genera el informe en PDF a partir de report/informe.md.

    python report/build_report.py            # report/informe.pdf

Pasa el markdown a HTML (con la portada de report/cover.json y report/style.css) y lo imprime a PDF
con Chrome/Chromium en modo headless, con el numero de pagina en el pie. Si no encuentra Chrome,
indicar la ruta con la variable CHROME_BIN.
"""
import base64
import html
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

import markdown

HERE = Path(__file__).resolve().parent

CHROME_CANDIDATES = [
    os.environ.get("CHROME_BIN", ""),
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "google-chrome", "chromium", "chromium-browser",
]


def find_chrome():
    for c in CHROME_CANDIDATES:
        if c and (Path(c).exists() or shutil.which(c)):
            return shutil.which(c) or c
    sys.exit("No encuentro Chrome/Chromium: indica la ruta con CHROME_BIN")


def cover_html(cover):
    rows = [("Autora" if cover.get("single_author") else "Integrantes", cover["members"])]
    if cover.get("student_id"):
        rows.append(("ID de estudiante", cover["student_id"]))
    if cover.get("group"):
        rows.append(("Grupo", cover["group"]))
    rows += [("Repositorio", f'<a href="{cover["repo"]}">{cover["repo"]}</a>'), ("Fecha", cover["date"])]
    table = "".join(f"<tr><th>{k}</th><td>{v}</td></tr>" for k, v in rows)
    return f"""
<section class="cover">
  <p class="uni">{cover["university"]}<br>{cover["degree"]}</p>
  <p class="course">{cover["course"]} &middot; Curso académico {cover["academic_year"]}</p>
  <h1>{cover["title"]}</h1>
  <h2>{cover["subtitle"]}</h2>
  <table class="cover-table">{table}</table>
</section>"""


def build_html(md_path, cover, css):
    text = md_path.read_text(encoding="utf-8")
    body = markdown.markdown(text, extensions=["tables", "fenced_code", "attr_list", "md_in_html", "toc"])
    return f"""<!doctype html>
<html lang="es"><head><meta charset="utf-8"><title>{html.escape(cover["subtitle"])}</title>
<style>{css}</style></head>
<body>{cover_html(cover)}<main>{body}</main></body></html>"""


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def print_pdf(chrome, html_file, pdf_file, footer_title):
    """Imprime con el protocolo DevTools para poder poner el numero de pagina en el pie."""
    import websocket  # websocket-client

    port = free_port()
    profile = tempfile.mkdtemp(prefix="chrome-report-")
    proc = subprocess.Popen([chrome, "--headless", "--disable-gpu", f"--remote-debugging-port={port}",
                             f"--user-data-dir={profile}", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json") as r:
                    targets = json.loads(r.read())
                page = next(t for t in targets if t.get("type") == "page")
                break
            except (OSError, StopIteration):
                time.sleep(0.1)
        ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=120)
        ids = iter(range(1, 1000))

        def call(method, **params):
            msg_id = next(ids)
            ws.send(json.dumps({"id": msg_id, "method": method, "params": params}))
            while True:
                msg = json.loads(ws.recv())
                if msg.get("id") == msg_id:
                    if "error" in msg:
                        raise RuntimeError(msg["error"])
                    return msg.get("result", {})

        call("Page.enable")
        call("Page.navigate", url=html_file.as_uri())
        time.sleep(1.5)  # que carguen las imagenes
        footer = (f'<div style="font-size:7.5pt;color:#666;width:100%;padding:0 16mm;display:flex;'
                  f'justify-content:space-between;font-family:Helvetica,Arial,sans-serif">'
                  f'<span>{html.escape(footer_title)}</span>'
                  f'<span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>')
        result = call("Page.printToPDF", printBackground=True, preferCSSPageSize=True,
                      displayHeaderFooter=True, headerTemplate="<span></span>", footerTemplate=footer)
        pdf_file.write_bytes(base64.b64decode(result["data"]))
        ws.close()
    finally:
        proc.terminate()
        proc.wait()
        shutil.rmtree(profile, ignore_errors=True)


def main():
    cover_file = HERE / "cover.json"
    if not cover_file.exists():
        sys.exit("falta report/cover.json")
    cover = json.loads(cover_file.read_text(encoding="utf-8"))
    css = (HERE / "style.css").read_text(encoding="utf-8")
    page = HERE / "informe.html"
    page.write_text(build_html(HERE / "informe.md", cover, css), encoding="utf-8")
    pdf = HERE / "informe.pdf"
    print_pdf(find_chrome(), page, pdf, f'{cover["title"]} - {cover["subtitle"]}')
    print("PDF ->", pdf)


if __name__ == "__main__":
    main()
