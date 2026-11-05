// Control layer: on each step it decides whether to index a pending book or download a new one.
import fs from 'node:fs';
import path from 'node:path';
import { Control } from './control.js';
import { Datalake, realClock } from './datalake.js';
import { openStore, searchIds } from './index_stores.js';
import { MetadataStore, parseHeader } from './metadata.js';
import { openSource, BookNotFound, DownloadError, DEFAULT_BASE } from './source.js';
import { loadIds } from './spec.js';
import { splitBook, NoMarkersError } from './splitter.js';
import { tokenize, termFrequencies } from './tokenizer.js';

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class Pipeline {
  // opening the index structure can be async (mongo), so it's not done in the constructor
  static async create(dataDir, {
    layout = 'range', store = 'sqlite', source = DEFAULT_BASE, candidates = null, delay = 0,
    clock = realClock, log = console.log, mongoUri, mongoDb,
  } = {}) {
    const pipe = new Pipeline();
    pipe.data = dataDir;
    pipe.lake = new Datalake(path.join(dataDir, 'datalake'), layout, clock);
    pipe.control = new Control(path.join(dataDir, 'control'));
    pipe.source = openSource(source);
    pipe.store = await openStore(store, path.join(dataDir, 'datamarts'), { mongoUri, mongoDb });
    await pipe.store.open();
    pipe.metadata = new MetadataStore(path.join(dataDir, 'datamarts', 'metadata.db'));
    pipe.candidates = candidates ?? loadIds();
    pipe.delay = delay;
    pipe.log = log;
    return pipe;
  }

  reconcile() {
    const stats = this.lake.reconcile(this.control);
    if (Object.values(stats).some((v) => v)) {
      // same format as the python dict
      const text = Object.entries(stats).map(([k, v]) => `'${k}': ${v}`).join(', ');
      this.log(`[CONTROL] reconciliation: {${text}}`);
    }
    return stats;
  }

  async step() {
    const pending = this.control.pending();
    if (pending.size > 0) {
      const bookId = [...pending].reduce((a, b) => Math.min(a, b));
      this.log(`[CONTROL] indexing book ${bookId}`);
      await this.indexBook(bookId);
      return ['indexed', bookId];
    }

    const done = this.control.downloaded().union(new Set(this.control.failed().keys()));
    for (const bookId of this.candidates) {
      if (!done.has(bookId)) {
        this.log(`[CONTROL] downloading book ${bookId}`);
        const ok = await this.downloadBook(bookId);
        return [ok ? 'downloaded' : 'failed', bookId];
      }
    }
    return ['idle', null];
  }

  async run(steps) {
    this.reconcile();
    for (let i = 0; i < steps; i++) {
      const [action] = await this.step();
      if (action === 'idle') {
        this.log('[CONTROL] nothing left to do');
        break;
      }
    }
  }

  async downloadBook(bookId) {
    if (this.delay) await sleep(this.delay * 1000); // be polite to gutenberg.org
    let header;
    let body;
    try {
      [header, body] = splitBook(await this.source.fetch(bookId));
    } catch (e) {
      let reason;
      if (e instanceof BookNotFound) reason = 'not_found';
      else if (e instanceof NoMarkersError) reason = 'no_markers';
      else if (e instanceof DownloadError) reason = 'download_error';
      else throw e;
      this.control.markFailed(bookId, reason);
      this.log(`[CONTROL] book ${bookId} skipped: ${reason}`);
      return false;
    }
    this.lake.writeBook(bookId, header, body);
    this.control.markDownloaded(bookId); // only once both files are in place
    return true;
  }

  async indexBook(bookId) {
    const [headerPath, bodyPath] = this.lake.locate(bookId);
    const header = fs.readFileSync(headerPath, 'utf8');
    const body = fs.readFileSync(bodyPath, 'utf8');
    this.metadata.upsert(bookId, parseHeader(header), this.lake.relative(headerPath), this.lake.relative(bodyPath));
    await this.store.addBook(bookId, termFrequencies(body));
    this.control.markIndexed(bookId);
  }

  async close() {
    await this.store.close();
    this.metadata.close();
  }
}

export async function search(dataDir, storeName, query, storeOptions = {}) {
  const store = await openStore(storeName, path.join(dataDir, 'datamarts'), storeOptions);
  await store.open();
  const metadata = new MetadataStore(path.join(dataDir, 'datamarts', 'metadata.db'));
  const terms = [...new Set(tokenize(query))];
  const ids = terms.length > 0 ? await searchIds(store, terms) : [];
  const results = [];
  for (const bookId of ids) {
    const meta = metadata.get(bookId) ?? { book_id: bookId };
    results.push({
      book_id: meta.book_id, title: meta.title ?? null, author: meta.author ?? null,
      language: meta.language ?? null, year: meta.year ?? null,
    });
  }
  await store.close();
  metadata.close();
  return { query, terms, total: results.length, results };
}
