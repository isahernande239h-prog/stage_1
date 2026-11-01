// Datalake con tres organizaciones posibles: time, book y range (ver spec/pipeline_spec.md).
import fs from 'node:fs';
import path from 'node:path';
import { readLines } from './control.js';

export const LAYOUTS = ['time', 'book', 'range'];
const REGISTRY = '_registry.tsv';
const HOUR_MS = 3600 * 1000;

// Para los experimentos de recuperacion: simula que el proceso muere a mitad.
export class SimulatedCrash extends Error {}

export const realClock = { now: () => new Date() };

// Reloj del benchmark: N libros por hora a partir de una fecha fija.
// Con el reloj real todo el benchmark caeria en la misma carpeta horaria.
export class SyntheticClock {
  constructor({ booksPerHour = 10, offsetHours = 0 } = {}) {
    this.start = Date.UTC(2026, 0, 1) + offsetHours * HOUR_MS;
    this.booksPerHour = booksPerHour;
    this.count = 0;
  }

  now() {
    return new Date(this.start + Math.floor(this.count / this.booksPerHour) * HOUR_MS);
  }

  tick() {
    this.count++;
  }
}

// YYYYMMDD/HH en UTC
function hourPartition(date) {
  const iso = date.toISOString();
  return `${iso.slice(0, 4)}${iso.slice(5, 7)}${iso.slice(8, 10)}/${iso.slice(11, 13)}`;
}

function isFile(file) {
  return fs.statSync(file, { throwIfNoEntry: false })?.isFile() ?? false;
}

function isDir(folder) {
  return fs.statSync(folder, { throwIfNoEntry: false })?.isDirectory() ?? false;
}

function sortedSubdirs(folder) {
  return fs.readdirSync(folder, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => e.name)
    .sort();
}

const DIGITS = /^[0-9]+$/;

export class ScanResult {
  constructor() {
    this.complete = new Map(); // id -> lista de particiones donde esta completo
    this.orphans = []; // ficheros sin su pareja
    this.temps = []; // .tmp que quedaron de una escritura cortada
  }

  ids() {
    return new Set(this.complete.keys());
  }
}

export class Datalake {
  constructor(root, layout = 'range', clock = realClock) {
    if (!LAYOUTS.includes(layout)) throw new Error(`layout desconocido: ${layout}`);
    this.root = root;
    this.layout = layout;
    this.clock = clock;
    this.registry = null;
  }

  // rutas

  partitionFor(bookId, when = null) {
    if (this.layout === 'time') return hourPartition(when ?? this.clock.now());
    if (this.layout === 'book') return String(bookId);
    return String(Math.floor(bookId / 1000) * 1000).padStart(6, '0');
  }

  fileNames(bookId) {
    if (this.layout === 'book') return ['header.txt', 'body.txt'];
    return [`${bookId}.header.txt`, `${bookId}.body.txt`];
  }

  paths(bookId, partition) {
    const folder = path.join(this.root, partition);
    const [headerName, bodyName] = this.fileNames(bookId);
    return [path.join(folder, headerName), path.join(folder, bodyName)];
  }

  // escritura

  // Escribe los dos ficheros con tmp + rename. Devuelve la particion usada.
  writeBook(bookId, header, body, crashBetweenRenames = false) {
    const partition = this.partitionFor(bookId);
    const [headerPath, bodyPath] = this.paths(bookId, partition);
    fs.mkdirSync(path.dirname(headerPath), { recursive: true });
    const headerTmp = headerPath + '.tmp';
    const bodyTmp = bodyPath + '.tmp';
    fs.writeFileSync(bodyTmp, body);
    fs.writeFileSync(headerTmp, header);
    fs.renameSync(bodyTmp, bodyPath);
    if (crashBetweenRenames) throw new SimulatedCrash('entre renombrados');
    fs.renameSync(headerTmp, headerPath);
    if (this.layout === 'time') {
      fs.appendFileSync(path.join(this.root, REGISTRY), `${bookId}\t${partition}\n`);
      if (this.registry !== null) this.registry.set(bookId, partition);
    }
    return partition;
  }

  // lectura

  loadRegistry() {
    const registry = new Map();
    for (const line of readLines(path.join(this.root, REGISTRY))) {
      const tab = line.indexOf('\t');
      const bookId = Number(tab < 0 ? line : line.slice(0, tab));
      registry.set(bookId, tab < 0 ? '' : line.slice(tab + 1)); // si se repite, vale la ultima
    }
    this.registry = registry;
    return registry;
  }

  // [header, body] de un libro completo, o null
  locate(bookId) {
    let partition;
    if (this.layout === 'time') {
      if (this.registry === null) this.loadRegistry();
      partition = this.registry.get(bookId);
      if (partition === undefined) return null;
    } else {
      partition = this.partitionFor(bookId);
    }
    const [headerPath, bodyPath] = this.paths(bookId, partition);
    if (isFile(headerPath) && isFile(bodyPath)) return [headerPath, bodyPath];
    return null;
  }

  readBook(bookId) {
    const found = this.locate(bookId);
    if (found === null) throw new Error(`libro ${bookId} no esta en el datalake`);
    const [headerPath, bodyPath] = found;
    return [fs.readFileSync(headerPath, 'utf8'), fs.readFileSync(bodyPath, 'utf8')];
  }

  relative(file) {
    return path.relative(this.root, file).split(path.sep).join('/');
  }

  // recorrido completo

  *partitions(minPartition = null) {
    if (!isDir(this.root)) return;
    if (this.layout !== 'time') {
      for (const name of sortedSubdirs(this.root)) yield [name, path.join(this.root, name)];
      return;
    }
    for (const day of sortedSubdirs(this.root)) {
      if (minPartition && day < minPartition.slice(0, 8)) continue;
      for (const hour of sortedSubdirs(path.join(this.root, day))) {
        const partition = `${day}/${hour}`;
        if (!minPartition || partition >= minPartition) yield [partition, path.join(this.root, day, hour)];
      }
    }
  }

  // [book_id, 'header'|'body'] o null si el fichero no es de un libro
  parseName(partition, name) {
    if (this.layout === 'book') {
      if ((name === 'header.txt' || name === 'body.txt') && DIGITS.test(partition)) {
        return [Number(partition), name.slice(0, -4)];
      }
      return null;
    }
    const parts = name.split('.');
    if (parts.length === 3 && DIGITS.test(parts[0]) && (parts[1] === 'header' || parts[1] === 'body')
        && parts[2] === 'txt') {
      return [Number(parts[0]), parts[1]];
    }
    return null;
  }

  scan(minPartition = null) {
    const result = new ScanResult();
    for (const [partition, folder] of this.partitions(minPartition)) {
      const found = new Map();
      for (const name of fs.readdirSync(folder)) {
        if (name.endsWith('.tmp')) {
          result.temps.push(path.join(folder, name));
          continue;
        }
        const parsed = this.parseName(partition, name);
        if (parsed) {
          const [bookId, kind] = parsed;
          if (!found.has(bookId)) found.set(bookId, {});
          found.get(bookId)[kind] = path.join(folder, name);
        }
      }
      for (const [bookId, kinds] of found) {
        const files = Object.values(kinds);
        if (files.length === 2) {
          if (!result.complete.has(bookId)) result.complete.set(bookId, []);
          result.complete.get(bookId).push(partition);
        } else {
          result.orphans.push(...files);
        }
      }
    }
    return result;
  }

  // recuperacion

  // Deja el datalake y downloaded_books.txt consistentes despues de una caida.
  reconcile(control) {
    const scan = this.scan();
    const stats = { temps: scan.temps.length, orphans: scan.orphans.length, duplicates: 0 };
    for (const file of [...scan.temps, ...scan.orphans]) fs.rmSync(file, { force: true });

    const registry = this.layout === 'time' ? this.loadRegistry() : new Map();
    for (const [bookId, partitions] of scan.complete) {
      if (partitions.length > 1) {
        let keep = registry.get(bookId);
        if (!partitions.includes(keep)) keep = partitions.reduce((a, b) => (b > a ? b : a));
        for (const partition of partitions) {
          if (partition !== keep) {
            for (const file of this.paths(bookId, partition)) fs.rmSync(file, { force: true });
            stats.duplicates++;
          }
        }
        scan.complete.set(bookId, [keep]);
      }
    }

    if (this.layout === 'time') {
      fs.mkdirSync(this.root, { recursive: true }); // puede que aun no se haya escrito nada
      const tmp = path.join(this.root, REGISTRY + '.tmp');
      const ids = [...scan.complete.keys()].sort((a, b) => a - b);
      fs.writeFileSync(tmp, ids.map((id) => `${id}\t${scan.complete.get(id)[0]}\n`).join(''));
      fs.renameSync(tmp, path.join(this.root, REGISTRY));
      this.registry = null;
    }

    const complete = scan.ids();
    const registered = control.downloaded();
    stats.added_to_control = [...complete].filter((id) => !registered.has(id)).length;
    stats.removed_from_control = [...registered].filter((id) => !complete.has(id)).length;
    if (stats.added_to_control > 0 || stats.removed_from_control > 0) control.rewriteDownloaded(complete);
    return stats;
  }

  // espacio ocupado

  storageStats() {
    let files = 0;
    let dirs = 0;
    let size = 0;
    let allocated = 0;
    const walk = (folder) => {
      for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
        const full = path.join(folder, entry.name);
        if (entry.isDirectory()) {
          dirs++;
          walk(full);
          continue;
        }
        if (folder === this.root && entry.name === REGISTRY) continue;
        const st = fs.statSync(full);
        files++;
        size += st.size;
        allocated += (st.blocks ?? 0) * 512;
      }
    };
    if (isDir(this.root)) walk(this.root);
    const registry = path.join(this.root, REGISTRY);
    const aux = fs.existsSync(registry) ? fs.statSync(registry).size : 0;
    return { files, dirs, bytes: size, allocated_bytes: allocated, aux_bytes: aux };
  }
}
