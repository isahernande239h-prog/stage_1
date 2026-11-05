"""Inverted index stores. They all have the same interface:

    write_full(index)       saves a full index {term: {book_id: tf}}
    open()                  gets the store ready for queries
    add_book(id, tfs)       adds (or overwrites) a book
    postings(term)          {book_id: tf}
    terms()                 goes through (term, postings) for exporting
    disk_usage()            bytes on disk
"""
import json
import os
import sqlite3
from pathlib import Path

from .datalake import allocated_bytes

STORES = ("json", "hier", "mongo", "sqlite")


class JsonStore:
    name = "json"

    def __init__(self, datamarts, **_):
        self.path = Path(datamarts) / "inverted_index.json"
        self.index = None

    def write_full(self, index):
        self._save(index)   # json turns the int keys into str
        self.index = None

    def _save(self, data):
        tmp = self.path.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8", newline="") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, self.path)

    def open(self):
        if self.path.exists():
            with open(self.path, encoding="utf-8") as f:
                self.index = json.load(f)
        else:
            self.index = {}

    def add_book(self, book_id, tfs):
        # there's no way to change just part of the file, so it gets rewritten whole
        if self.index is None:
            self.open()
        key = str(book_id)
        for term, tf in tfs.items():
            self.index.setdefault(term, {})[key] = tf
        self._save(self.index)

    def postings(self, term):
        found = self.index.get(term)
        return {int(k): v for k, v in found.items()} if found else {}

    def terms(self):
        for term, found in self.index.items():
            yield term, {int(k): v for k, v in found.items()}

    def disk_usage(self):
        return self.path.stat().st_size

    def close(self):
        self.index = None


RESERVED = {"con", "prn", "aux", "nul"} | {f"com{i}" for i in range(1, 10)} | {f"lpt{i}" for i in range(1, 10)}


def term_folder(term):
    first = term[0]
    return first.upper() if "a" <= first <= "z" else "_"


def term_filename(term):
    # a-z as is and the rest as %XX: the Windows and macOS file systems are
    # case-insensitive, and Greek terms like "οδος" and "οδοσ" would clash
    out = []
    for ch in term:
        if "a" <= ch <= "z":
            out.append(ch)
        else:
            out.extend(f"%{b:02X}" for b in ch.encode("utf-8"))
    name = "".join(out)
    if name in RESERVED:
        name += "_"
    return name + ".txt"


def filename_to_term(filename):
    name = filename[:-4]
    if name.endswith("_"):
        name = name[:-1]
    raw = bytearray()
    i = 0
    while i < len(name):
        if name[i] == "%":
            raw.append(int(name[i + 1:i + 3], 16))
            i += 3
        else:
            raw.extend(name[i].encode("ascii"))
            i += 1
    return raw.decode("utf-8")


class HierStore:
    name = "hier"

    def __init__(self, datamarts, **_):
        self.root = Path(datamarts) / "inverted_index"

    def _path(self, term):
        return self.root / term_folder(term) / term_filename(term)

    def _make_folders(self):
        for folder in [chr(c) for c in range(ord("A"), ord("Z") + 1)] + ["_"]:
            (self.root / folder).mkdir(parents=True, exist_ok=True)

    def write_full(self, index):
        self._make_folders()
        for term, found in index.items():
            with open(self._path(term), "w", encoding="utf-8", newline="") as f:
                f.write("".join(f"{b}\t{tf}\n" for b, tf in found.items()))

    def open(self):
        self._make_folders()

    def add_book(self, book_id, tfs):
        for term, tf in tfs.items():
            with open(self._path(term), "a", encoding="utf-8", newline="") as f:
                f.write(f"{book_id}\t{tf}\n")

    @staticmethod
    def _read(path):
        found = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                book_id, tf = line.split("\t")
                found[int(book_id)] = int(tf)   # if a book shows up twice, the last line wins
        return found

    def postings(self, term):
        path = self._path(term)
        return self._read(path) if path.exists() else {}

    def terms(self):
        for folder in sorted(os.scandir(self.root), key=lambda e: e.name):
            for entry in os.scandir(folder.path):
                yield filename_to_term(entry.name), self._read(entry.path)

    def disk_usage(self):
        # allocated bytes (blocks), which is what matters with half a million files
        total = 0
        for folder, _, files in os.walk(self.root):
            for name in files:
                total += allocated_bytes(os.stat(os.path.join(folder, name)))
        return total

    def close(self):
        pass


class MongoStore:
    name = "mongo"
    BATCH = 5000

    def __init__(self, datamarts=None, mongo_uri="mongodb://127.0.0.1:27017", mongo_db="searchengine", **_):
        from pymongo import MongoClient
        self.client = MongoClient(mongo_uri)
        self.db = self.client[mongo_db]
        self.coll = self.db["inverted_index"]

    def write_full(self, index):
        self.coll.drop()
        batch = []
        for term, found in index.items():
            batch.append({"_id": term, "postings": {str(b): tf for b, tf in found.items()}})
            if len(batch) == self.BATCH:
                self.coll.insert_many(batch, ordered=False)
                batch = []
        if batch:
            self.coll.insert_many(batch, ordered=False)

    def open(self):
        pass

    def add_book(self, book_id, tfs):
        from pymongo import UpdateOne
        ops = [UpdateOne({"_id": term}, {"$set": {f"postings.{book_id}": tf}}, upsert=True)
               for term, tf in tfs.items()]
        for i in range(0, len(ops), self.BATCH):
            self.coll.bulk_write(ops[i:i + self.BATCH], ordered=False)

    def postings(self, term):
        doc = self.coll.find_one({"_id": term})
        return {int(k): v for k, v in doc["postings"].items()} if doc else {}

    def terms(self):
        for doc in self.coll.find():
            yield doc["_id"], {int(k): v for k, v in doc["postings"].items()}

    def disk_usage(self):
        # without fsync, collStats can return almost 0 because WiredTiger hasn't written the checkpoint yet
        self.client.admin.command("fsync")
        stats = self.db.command("collStats", "inverted_index")
        return stats["storageSize"] + stats["totalIndexSize"]

    def drop(self):
        self.client.drop_database(self.db.name)

    def close(self):
        self.client.close()


class SqliteStore:
    name = "sqlite"

    def __init__(self, datamarts, **_):
        self.path = Path(datamarts) / "inverted_index.db"
        self.conn = None

    def _connect(self):
        if self.conn is None:
            self.conn = sqlite3.connect(str(self.path))
            self.conn.execute("""CREATE TABLE IF NOT EXISTS postings (
                term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL,
                PRIMARY KEY (term, book_id)) WITHOUT ROWID""")
            self.conn.commit()

    def write_full(self, index):
        self._connect()
        with self.conn:
            self.conn.execute("DELETE FROM postings")
            # inserting in key order is much faster in the B-tree
            self.conn.executemany("INSERT INTO postings VALUES (?, ?, ?)", (
                (term, b, index[term][b]) for term in sorted(index) for b in sorted(index[term])))

    def open(self):
        self._connect()

    def add_book(self, book_id, tfs):
        with self.conn:
            self.conn.executemany("INSERT OR REPLACE INTO postings VALUES (?, ?, ?)",
                                  ((term, book_id, tf) for term, tf in tfs.items()))

    def postings(self, term):
        return dict(self.conn.execute("SELECT book_id, tf FROM postings WHERE term = ?", (term,)))

    def terms(self):
        current, found = None, {}
        for term, b, tf in self.conn.execute("SELECT term, book_id, tf FROM postings ORDER BY term, book_id"):
            if term != current:
                if current is not None:
                    yield current, found
                current, found = term, {}
            found[b] = tf
        if current is not None:
            yield current, found

    def disk_usage(self):
        return self.path.stat().st_size

    def close(self):
        if self.conn is not None:
            self.conn.close()
            self.conn = None


def open_store(name, datamarts, **options):
    Path(datamarts).mkdir(parents=True, exist_ok=True)
    classes = {"json": JsonStore, "hier": HierStore, "mongo": MongoStore, "sqlite": SqliteStore}
    return classes[name](datamarts, **options)


def search_ids(store, terms):
    """Books that have all the terms, sorted by id."""
    result = None
    for term in dict.fromkeys(terms):
        found = set(store.postings(term))
        result = found if result is None else result & found
        if not result:
            return []
    return sorted(result) if result else []
