// Ficheros de control: que libros estan descargados, indexados o han fallado.
import fs from 'node:fs';
import path from 'node:path';

// Lineas completas del fichero. Una ultima linea sin salto se ignora (escritura cortada).
export function readLines(file) {
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, 'utf8').replace(/\r\n?/g, '\n');
  const lines = text.split('\n');
  lines.pop(); // lo que va detras del ultimo \n: vacio o incompleto
  return lines.filter((line) => line.trim() !== '');
}

export class Control {
  constructor(folder) {
    this.folder = folder;
    fs.mkdirSync(folder, { recursive: true });
    this.downloadedFile = path.join(folder, 'downloaded_books.txt');
    this.indexedFile = path.join(folder, 'indexed_books.txt');
    this.failedFile = path.join(folder, 'failed_books.txt');
  }

  downloaded() {
    return new Set(readLines(this.downloadedFile).map(Number));
  }

  indexed() {
    return new Set(readLines(this.indexedFile).map(Number));
  }

  // Map id -> motivo
  failed() {
    const result = new Map();
    for (const line of readLines(this.failedFile)) {
      const tab = line.indexOf('\t');
      if (tab < 0) result.set(Number(line), '');
      else result.set(Number(line.slice(0, tab)), line.slice(tab + 1));
    }
    return result;
  }

  // descargados y todavia sin indexar
  pending() {
    const indexed = this.indexed();
    return new Set([...this.downloaded()].filter((id) => !indexed.has(id)));
  }

  markDownloaded(bookId) {
    append(this.downloadedFile, `${bookId}\n`);
  }

  markIndexed(bookId) {
    append(this.indexedFile, `${bookId}\n`);
  }

  markFailed(bookId, reason) {
    append(this.failedFile, `${bookId}\t${reason}\n`);
  }

  rewriteDownloaded(ids) {
    const tmp = path.join(this.folder, 'downloaded_books.tmp');
    const sorted = [...ids].sort((a, b) => a - b);
    fs.writeFileSync(tmp, sorted.map((id) => `${id}\n`).join(''));
    fs.renameSync(tmp, this.downloadedFile);
  }
}

// una sola escritura por linea: si el proceso muere, como mucho queda una linea a medias
function append(file, line) {
  fs.appendFileSync(file, line);
}
