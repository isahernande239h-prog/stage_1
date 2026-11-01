// Lectura de libros: de gutenberg.org (o un espejo con las mismas URLs) o de una cache local.
import fs from 'node:fs';
import path from 'node:path';

export const DEFAULT_BASE = 'https://www.gutenberg.org';
export const USER_AGENT = 'ulpgc-bigdata-stage1/1.0 (proyecto de clase)';
const ATTEMPTS = 3;

export class BookNotFound extends Error {}
export class DownloadError extends Error {}

// con ignoreBOM el decoder no quita el BOM y lo quitamos a mano, como en python
// (asi un fichero con dos BOM queda igual en los dos lenguajes)
const decoder = new TextDecoder('utf-8', { ignoreBOM: true });

export function decode(raw) {
  let text = decoder.decode(raw);
  if (text.startsWith('﻿')) text = text.slice(1);
  return text;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class HttpSource {
  constructor(base = DEFAULT_BASE) {
    this.base = base.replace(/\/+$/, '');
  }

  url(bookId) {
    return `${this.base}/cache/epub/${bookId}/pg${bookId}.txt`;
  }

  async fetch(bookId) {
    return decode(await this.fetchRaw(bookId));
  }

  async fetchRaw(bookId) {
    let lastError = null;
    for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
      if (attempt > 0) await sleep(1000 * 2 ** (attempt - 1)); // 1 s y luego 2 s
      let resp;
      let raw;
      try {
        resp = await fetch(this.url(bookId), {
          // sin compresion para poder comparar Content-Length con lo recibido
          headers: { 'User-Agent': USER_AGENT, 'Accept-Encoding': 'identity' },
          signal: AbortSignal.timeout(30000),
        });
        raw = Buffer.from(await resp.arrayBuffer());
      } catch (e) {
        lastError = String(e.cause ?? e.message);
        continue;
      }
      if (resp.status === 404) throw new BookNotFound(String(bookId));
      if (resp.status === 429 || resp.status >= 500) {
        lastError = `HTTP ${resp.status}`;
        continue;
      }
      if (resp.status !== 200) throw new DownloadError(`HTTP ${resp.status}`);
      const expected = resp.headers.get('Content-Length');
      if (expected !== null && Number(expected) !== raw.length) {
        lastError = 'incomplete';
        continue;
      }
      return raw;
    }
    throw new DownloadError(lastError);
  }
}

// Lee pg<id>.txt de una carpeta. Sirve para repetir benchmarks sin volver a descargar.
export class CacheSource {
  constructor(folder) {
    this.folder = folder;
  }

  fetch(bookId) {
    const file = path.join(this.folder, `pg${bookId}.txt`);
    if (!fs.existsSync(file)) throw new BookNotFound(String(bookId));
    return decode(fs.readFileSync(file));
  }
}

export function openSource(spec) {
  if (spec.startsWith('cache:')) return new CacheSource(spec.slice('cache:'.length));
  return new HttpSource(spec);
}
