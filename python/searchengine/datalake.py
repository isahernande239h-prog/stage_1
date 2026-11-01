"""Datalake con tres organizaciones posibles: time, book y range (ver spec/pipeline_spec.md)."""
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .control import read_lines

LAYOUTS = ("time", "book", "range")
REGISTRY = "_registry.tsv"


class SimulatedCrash(Exception):
    """Para los experimentos de recuperacion: simula que el proceso muere a mitad."""


def utc_now():
    return datetime.now(timezone.utc)


class SyntheticClock:
    """Reloj del benchmark: N libros por hora a partir de una fecha fija.

    Con el reloj real todo el benchmark caeria en la misma carpeta horaria.
    """

    def __init__(self, books_per_hour=10, offset_hours=0):
        self.start = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(hours=offset_hours)
        self.books_per_hour = books_per_hour
        self.count = 0

    def __call__(self):
        return self.start + timedelta(hours=self.count // self.books_per_hour)

    def tick(self):
        self.count += 1


class ScanResult:
    def __init__(self):
        self.complete = {}   # id -> lista de particiones donde esta completo
        self.orphans = []    # ficheros sin su pareja
        self.temps = []      # .tmp que quedaron de una escritura cortada

    def ids(self):
        return set(self.complete)


class Datalake:
    def __init__(self, root, layout="range", clock=utc_now):
        if layout not in LAYOUTS:
            raise ValueError(f"layout desconocido: {layout}")
        self.root = Path(root)
        self.layout = layout
        self.clock = clock
        self._registry = None

    # ---------- rutas ----------

    def partition_for(self, book_id, when=None):
        if self.layout == "time":
            return (when or self.clock()).strftime("%Y%m%d/%H")
        if self.layout == "book":
            return str(book_id)
        return f"{book_id // 1000 * 1000:06d}"

    def file_names(self, book_id):
        if self.layout == "book":
            return "header.txt", "body.txt"
        return f"{book_id}.header.txt", f"{book_id}.body.txt"

    def paths(self, book_id, partition):
        folder = self.root / partition
        header_name, body_name = self.file_names(book_id)
        return folder / header_name, folder / body_name

    # ---------- escritura ----------

    def write_book(self, book_id, header, body, crash_between_renames=False):
        """Escribe los dos ficheros con tmp + rename. Devuelve la particion usada."""
        partition = self.partition_for(book_id)
        header_path, body_path = self.paths(book_id, partition)
        header_path.parent.mkdir(parents=True, exist_ok=True)
        header_tmp = header_path.with_name(header_path.name + ".tmp")
        body_tmp = body_path.with_name(body_path.name + ".tmp")
        body_tmp.write_bytes(body.encode("utf-8"))
        header_tmp.write_bytes(header.encode("utf-8"))
        os.replace(body_tmp, body_path)
        if crash_between_renames:
            raise SimulatedCrash("entre renombrados")
        os.replace(header_tmp, header_path)
        if self.layout == "time":
            with open(self.root / REGISTRY, "a", encoding="utf-8") as f:
                f.write(f"{book_id}\t{partition}\n")
            if self._registry is not None:
                self._registry[book_id] = partition
        return partition

    # ---------- lectura ----------

    def load_registry(self):
        registry = {}
        for line in read_lines(self.root / REGISTRY):
            book_id, _, partition = line.partition("\t")
            registry[int(book_id)] = partition  # si se repite, vale la ultima
        self._registry = registry
        return registry

    def locate(self, book_id):
        """(header, body) de un libro completo, o None."""
        if self.layout == "time":
            if self._registry is None:
                self.load_registry()
            partition = self._registry.get(book_id)
            if partition is None:
                return None
        else:
            partition = self.partition_for(book_id)
        header_path, body_path = self.paths(book_id, partition)
        if header_path.is_file() and body_path.is_file():
            return header_path, body_path
        return None

    def read_book(self, book_id):
        found = self.locate(book_id)
        if found is None:
            raise FileNotFoundError(f"libro {book_id} no esta en el datalake")
        header_path, body_path = found
        return header_path.read_text(encoding="utf-8"), body_path.read_text(encoding="utf-8")

    def relative(self, path):
        return path.relative_to(self.root).as_posix()

    # ---------- recorrido completo ----------

    def _partitions(self, min_partition=None):
        if not self.root.is_dir():
            return
        if self.layout != "time":
            for entry in sorted(os.scandir(self.root), key=lambda e: e.name):
                if entry.is_dir():
                    yield entry.name, entry.path
            return
        for day in sorted(os.scandir(self.root), key=lambda e: e.name):
            if not day.is_dir():
                continue
            if min_partition and day.name < min_partition[:8]:
                continue
            for hour in sorted(os.scandir(day.path), key=lambda e: e.name):
                partition = f"{day.name}/{hour.name}"
                if hour.is_dir() and (not min_partition or partition >= min_partition):
                    yield partition, hour.path

    def _parse_name(self, partition, name):
        """(book_id, 'header'|'body') o None si el fichero no es de un libro."""
        if self.layout == "book":
            if name in ("header.txt", "body.txt") and partition.isdigit():
                return int(partition), name[:-4]
            return None
        parts = name.split(".")
        if len(parts) == 3 and parts[0].isdigit() and parts[1] in ("header", "body") and parts[2] == "txt":
            return int(parts[0]), parts[1]
        return None

    def scan(self, min_partition=None):
        result = ScanResult()
        for partition, folder in self._partitions(min_partition):
            found = {}
            for entry in os.scandir(folder):
                if entry.name.endswith(".tmp"):
                    result.temps.append(Path(entry.path))
                    continue
                parsed = self._parse_name(partition, entry.name)
                if parsed:
                    found.setdefault(parsed[0], {})[parsed[1]] = Path(entry.path)
            for book_id, kinds in found.items():
                if len(kinds) == 2:
                    result.complete.setdefault(book_id, []).append(partition)
                else:
                    result.orphans.extend(kinds.values())
        return result

    # ---------- recuperacion ----------

    def reconcile(self, control):
        """Deja el datalake y downloaded_books.txt consistentes despues de una caida."""
        scan = self.scan()
        stats = {"temps": len(scan.temps), "orphans": len(scan.orphans), "duplicates": 0}
        for path in scan.temps + scan.orphans:
            path.unlink(missing_ok=True)

        registry = self.load_registry() if self.layout == "time" else {}
        for book_id, partitions in scan.complete.items():
            if len(partitions) > 1:
                keep = registry.get(book_id)
                if keep not in partitions:
                    keep = max(partitions)
                for partition in partitions:
                    if partition != keep:
                        for path in self.paths(book_id, partition):
                            path.unlink(missing_ok=True)
                        stats["duplicates"] += 1
                scan.complete[book_id] = [keep]

        if self.layout == "time":
            tmp = self.root / (REGISTRY + ".tmp")
            tmp.write_text("".join(f"{i}\t{p[0]}\n" for i, p in sorted(scan.complete.items())), encoding="utf-8")
            os.replace(tmp, self.root / REGISTRY)
            self._registry = None

        complete = scan.ids()
        registered = control.downloaded()
        stats["added_to_control"] = len(complete - registered)
        stats["removed_from_control"] = len(registered - complete)
        if complete != registered:
            control.rewrite_downloaded(complete)
        return stats

    # ---------- espacio ocupado ----------

    def storage_stats(self):
        files = dirs = size = allocated = 0
        for folder, dirnames, filenames in os.walk(self.root):
            dirs += len(dirnames)
            for name in filenames:
                if folder == str(self.root) and name == REGISTRY:
                    continue
                st = os.stat(os.path.join(folder, name))
                files += 1
                size += st.st_size
                allocated += getattr(st, "st_blocks", 0) * 512
        registry = self.root / REGISTRY
        aux = registry.stat().st_size if registry.exists() else 0
        return {"files": files, "dirs": dirs, "bytes": size, "allocated_bytes": allocated, "aux_bytes": aux}
