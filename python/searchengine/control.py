"""Ficheros de control: que libros estan descargados, indexados o han fallado."""
import os
from pathlib import Path


def read_lines(path):
    """Lineas completas del fichero. Una ultima linea sin salto se ignora (escritura cortada)."""
    path = Path(path)
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    lines = text.split("\n")
    lines.pop()  # lo que va detras del ultimo \n: vacio o incompleto
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
        """Descargados y todavia sin indexar."""
        return self.downloaded() - self.indexed()

    def mark_downloaded(self, book_id):
        self._append(self.downloaded_file, f"{book_id}\n")

    def mark_indexed(self, book_id):
        self._append(self.indexed_file, f"{book_id}\n")

    def mark_failed(self, book_id, reason):
        self._append(self.failed_file, f"{book_id}\t{reason}\n")

    def rewrite_downloaded(self, ids):
        tmp = self.downloaded_file.with_suffix(".tmp")
        tmp.write_text("".join(f"{i}\n" for i in sorted(ids)), encoding="utf-8")
        os.replace(tmp, self.downloaded_file)

    @staticmethod
    def _append(path, line):
        # una sola escritura por linea: si el proceso muere, como mucho queda una linea a medias
        with open(path, "a", encoding="utf-8") as f:
            f.write(line)
