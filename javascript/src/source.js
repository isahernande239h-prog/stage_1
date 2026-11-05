// Reading books: from gutenberg.org (or a mirror with the same URLs) or from a local cache.
import fs from 'node:fs';
import path from 'node:path';

export const DEFAULT_BASE = 'https://www.gutenberg.org';
export const USER_AGENT = 'ulpgc-bigdata-stage1/1.0 (class project)';
const ATTEMPTS = 3;

export class BookNotFound extends Error {}
export class DownloadError extends Error {}

// with ignoreBOM the decoder doesn't remove the BOM and we remove it by hand, like in python
// (so a file with two BOMs ends up the same in both languages)
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
      if (attempt > 0) await sleep(1000 * 2 ** (attempt - 1)); // 1 s and then 2 s
      let resp;
      let raw;
      try {
        resp = await fetch(this.url(bookId), {
          // no compression so we can compare Content-Length with what we got
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

// Reads pg<id>.txt from a folder. Useful for repeating benchmarks without downloading again.
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
