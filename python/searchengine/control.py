"""Control files: which books are downloaded, indexed or failed."""
import os
from pathlib import Path


def write_text(path, text):
    """Writes UTF-8 with \n as is (in text mode Windows would turn it into \r\n)."""
    Path(path).write_bytes(text.encode("utf-8"))


def read_lines(path):
    """Complete lines of the file. A last line without a newline is ignored (cut-off write)."""
    path = Path(path)
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    lines.pop()  # whatever comes after the last \n: empty or incomplete
    return [line for line in lines if line.strip()]


class Control:
    def __init__(self, folder):
        self.folder = Path(folder)
        self.folder.mkdir(parents=True, exist_ok=True)
        self.downloaded_file = self.folder / "downloaded_books.txt"
        self.indexed_file = self.folder / "indexed_books.txt"
        self.failed_file = self.folder / "failed_books.txt"

    def downloaded(self):
        return {int(x) for x in read_lines(self.downloaded_file)}

    def indexed(self):
        return {int(x) for x in read_lines(self.indexed_file)}

    def failed(self):
        result = {}
        for line in read_lines(self.failed_file):
            book_id, _, reason = line.partition("\t")
            result[int(book_id)] = reason
        return result

    def pending(self):
        """Downloaded but not indexed yet."""
        return self.downloaded() - self.indexed()

    def mark_downloaded(self, book_id):
        self._append(self.downloaded_file, f"{book_id}\n")

    def mark_indexed(self, book_id):
        self._append(self.indexed_file, f"{book_id}\n")

    def mark_failed(self, book_id, reason):
        self._append(self.failed_file, f"{book_id}\t{reason}\n")

    def rewrite_downloaded(self, ids):
        tmp = self.downloaded_file.with_suffix(".tmp")
        write_text(tmp, "".join(f"{i}\n" for i in sorted(ids)))
        os.replace(tmp, self.downloaded_file)

    @staticmethod
    def _append(path, line):
        # one write per line: if the process dies, at most one line is left half written
        with open(path, "a", encoding="utf-8", newline="") as f:
            f.write(line)
