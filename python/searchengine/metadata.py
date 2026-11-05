"""Metadata taken from the header and stored in SQLite (datamarts/metadata.db)."""
import re
import sqlite3

from .spec import load_languages

FIELD_RE = re.compile(r"^([A-Za-z][A-Za-z -]*):[ \t]*(.*)$")
YEAR_RE = re.compile(r"(?<![0-9])[0-9]{4}(?![0-9])")
SPACES_RE = re.compile(r"[ \t]+")
LANG_SPLIT_RE = re.compile(r",|;| and ", re.IGNORECASE | re.ASCII)
LANGUAGES = load_languages()


def _clean(value):
    return SPACES_RE.sub(" ", value).strip(" \t")


def header_fields(header):
    """{lowercase name: [values]}. Each value is a list of lines (the first one + continuations)."""
    fields = {}
    current = None
    for line in header.split("\n"):
        if line[:1] in (" ", "\t") and current is not None and line.strip(" \t"):
            current.append(_clean(line))
            continue
        match = FIELD_RE.match(line)
        if match:
            current = [_clean(match.group(2))]
            fields.setdefault(match.group(1).lower(), []).append(current)
        else:
            current = None
    return fields


def parse_header(header):
    fields = header_fields(header)

    title = None
    if "title" in fields:
        title = " ".join(x for x in fields["title"][0] if x) or None

    authors = [x for value in fields.get("author", []) for x in value if x]
    author = "; ".join(authors) or None

    language = None
    if "language" in fields:
        first = LANG_SPLIT_RE.split(" ".join(fields["language"][0]))[0]
        language = LANGUAGES.get(_clean(first).lower())

    year = None
    if "original publication" in fields:
        years = YEAR_RE.findall(" ".join(fields["original publication"][0]))
        if years:
            year = int(years[-1])

    release_year = None
    if "release date" in fields:
        text = re.sub(r"\[[^\]]*\]", "", " ".join(fields["release date"][0]))
        found = YEAR_RE.search(text)
        if found:
            release_year = int(found.group())

    return {"title": title, "author": author, "language": language,
            "year": year, "release_year": release_year}


class MetadataStore:
    COLUMNS = ("book_id", "title", "author", "language", "year", "release_year", "header_path", "body_path")

    def __init__(self, path):
        self.conn = sqlite3.connect(str(path))
        self.conn.execute("""
            CREATE TABLE IF NOT EXISTS books (
                book_id INTEGER PRIMARY KEY,
                title TEXT, author TEXT, language TEXT,
                year INTEGER, release_year INTEGER,
                header_path TEXT, body_path TEXT)""")
        self.conn.commit()

    def upsert(self, book_id, meta, header_path, body_path):
        self.conn.execute(
            "INSERT OR REPLACE INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (book_id, meta["title"], meta["author"], meta["language"], meta["year"],
             meta["release_year"], header_path, body_path))
        self.conn.commit()

    def get(self, book_id):
        row = self.conn.execute("SELECT * FROM books WHERE book_id = ?", (book_id,)).fetchone()
        return dict(zip(self.COLUMNS, row)) if row else None

    def all(self):
        return [dict(zip(self.COLUMNS, row)) for row in self.conn.execute("SELECT * FROM books ORDER BY book_id")]

    def by_author(self, text):
        rows = self.conn.execute("SELECT * FROM books WHERE author LIKE ? ORDER BY book_id", (f"%{text}%",))
        return [dict(zip(self.COLUMNS, row)) for row in rows]

    def close(self):
        self.conn.close()
