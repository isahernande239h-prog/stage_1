// Comandos de benchmark. Cada uno ejecuta una configuracion y escribe un JSON con las medidas.
//
// El orquestador (benchmarks/bench.py) los lanza en un proceso nuevo para los tres lenguajes y
// mide desde fuera la memoria del proceso.
import assert from 'node:assert';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { Control } from './control.js';
import { Datalake, SyntheticClock, SimulatedCrash, realClock } from './datalake.js';
import { openStore, searchIds, compareCodePoints } from './index_stores.js';
import { MetadataStore, parseHeader } from './metadata.js';
import { openSource, BookNotFound } from './source.js';
import { loadIds, loadQueries } from './spec.js';
import { splitBook, NoMarkersError } from './splitter.js';
import { tokenize, termFrequencies } from './tokenizer.js';

const CRASH_EXIT = 3;

// segundos, como time.perf_counter()
function now() {
  return performance.now() / 1000;
}

function emit(result) {
  console.log(JSON.stringify(result));
}

function sha256(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function byNumber(a, b) {
  return a - b;
}

// ---------------------------------------------------------------- datalake

function makeClock(args) {
  if (args.clock === 'synthetic') return new SyntheticClock({ booksPerHour: 10, offsetHours: args.clockOffsetHours });
  return realClock;
}

// Descarga, separa y guarda los ids que no esten ya en el control. Devuelve [ingeridos, fallidos, bytes].
export async function ingestBooks(lake, control, source, ids, clock, { crashAfter = null, crashAt = null, delay = 0 } = {}) {
  const done = control.downloaded().union(new Set(control.failed().keys()));
  let ingested = 0;
  let failed = 0;
  let written = 0;
  for (const bookId of ids) {
    if (done.has(bookId)) continue;
    if (delay) await sleep(delay * 1000);
    let header;
    let body;
    try {
      [header, body] = splitBook(await source.fetch(bookId));
    } catch (e) {
      if (!(e instanceof BookNotFound || e instanceof NoMarkersError)) throw e;
      control.markFailed(bookId, e instanceof BookNotFound ? 'not_found' : 'no_markers');
      failed++;
      continue;
    }
    const crashNow = crashAfter !== null && ingested === crashAfter;
    lake.writeBook(bookId, header, body, crashNow && crashAt === 'between_renames');
    if (crashNow && crashAt === 'before_control') throw new SimulatedCrash('antes del control');
    control.markDownloaded(bookId);
    if (clock instanceof SyntheticClock) clock.tick();
    ingested++;
    written += Buffer.byteLength(header, 'utf8') + Buffer.byteLength(body, 'utf8');
  }
  return [ingested, failed, written];
}

async function cmdIngest(args) {
  const clock = makeClock(args);
  const lake = new Datalake(path.join(args.data, 'datalake'), args.layout, clock);
  const control = new Control(path.join(args.data, 'control'));
  const result = { reconcile_ms: null };
  if (args.reconcile) {
    const t = now();
    result.reconcile = lake.reconcile(control);
    result.reconcile_ms = (now() - t) * 1000;
  }

  const ids = loadIds(args.ids).slice(0, args.n);
  const t = now();
  let ingested;
  let failed;
  let written;
  try {
    [ingested, failed, written] = await ingestBooks(lake, control, openSource(args.source), ids, clock,
      { crashAfter: args.crashAfter, crashAt: args.crashAt, delay: args.delay });
  } catch (e) {
    if (!(e instanceof SimulatedCrash)) throw e;
    emit({ crashed: e.message });
    process.exitCode = CRASH_EXIT;
    return;
  }
  const seconds = now() - t;
  Object.assign(result, {
    ingested, failed, seconds, bytes: written,
    books_per_s: seconds ? ingested / seconds : 0,
    mb_per_s: seconds ? written / 1e6 / seconds : 0,
  });
  emit(result);
}

async function cmdBenchDatalake(args) {
  const clock = makeClock(args);
  const lakeDir = path.join(args.data, 'datalake');
  const controlDir = path.join(args.data, 'control');
  let lake = new Datalake(lakeDir, args.layout, clock);
  const control = new Control(controlDir);
  const ids = loadIds(args.ids).slice(0, args.n);
  const r = { layout: args.layout, n: ids.length };

  // 1) escritura
  let t = now();
  const [ingested, , written] = await ingestBooks(lake, control, openSource(args.source), ids, clock);
  const seconds = now() - t;
  Object.assign(r, { ingested, write_s: seconds, books_per_s: ingested / seconds, mb_per_s: written / 1e6 / seconds });

  // 2) localizar un libro con la ruta calculada (o el registro en time)
  lake = new Datalake(lakeDir, args.layout);
  t = now();
  if (args.layout === 'time') lake.loadRegistry();
  r.registry_load_ms = (now() - t) * 1000;
  t = now();
  for (const bookId of ids) assert(lake.locate(bookId) !== null);
  r.lookup_direct_us = (now() - t) / ids.length * 1e6;

  // 3) localizar recorriendo el arbol (sin estructuras auxiliares)
  const step = Math.max(1, Math.floor(ids.length / 10));
  const sample = ids.filter((_, i) => i % step === 0).slice(0, 10);
  t = now();
  for (const bookId of sample) assert(lake.scan().complete.has(bookId));
  r.lookup_scan_ms = (now() - t) / sample.length * 1000;

  // 4) detectar los libros nuevos (los ultimos args.new no estan indexados)
  const indexed = ids.slice(0, -args.new);
  const expected = new Set(ids.slice(-args.new));
  fs.writeFileSync(control.indexedFile, indexed.map((id) => `${id}\n`).join(''));
  const sameAsExpected = (found) => found.size === expected.size && found.isSubsetOf(expected);

  t = now();
  let found = new Control(controlDir).pending();
  r.detect_control_ms = (now() - t) * 1000;
  let ok = sameAsExpected(found);

  t = now();
  found = lake.scan().ids().difference(new Control(controlDir).indexed());
  r.detect_scan_ms = (now() - t) * 1000;
  ok = ok && sameAsExpected(found);

  if (args.layout === 'time') {
    const registry = lake.loadRegistry();
    const last = indexed.map((id) => registry.get(id)).reduce((a, b) => (b > a ? b : a));
    t = now();
    found = lake.scan(last).ids().difference(new Control(controlDir).indexed());
    r.detect_pruned_ms = (now() - t) * 1000;
    ok = ok && sameAsExpected(found);
  }
  r.detect_ok = ok;

  // 5) espacio
  for (const [key, value] of Object.entries(lake.storageStats())) r[`storage_${key}`] = value;
  r.control_bytes = fs.readdirSync(controlDir)
    .reduce((sum, name) => sum + fs.statSync(path.join(controlDir, name)).size, 0);
  emit(r);
}

// ---------------------------------------------------------------- indice

export function buildIndex(lake, ids) {
  const index = new Map();
  let tokens = 0;
  for (const bookId of ids) {
    const [, body] = lake.readBook(bookId);
    for (const [term, tf] of termFrequencies(body)) {
      let found = index.get(term);
      if (found === undefined) {
        found = new Map();
        index.set(term, found);
      }
      found.set(bookId, tf);
      tokens += tf;
    }
  }
  return [index, tokens];
}

// interpolacion lineal, igual que en python
export function percentile(values, p) {
  values = [...values].sort(byNumber);
  const k = (values.length - 1) * p;
  const lo = Math.floor(k);
  const hi = Math.min(lo + 1, values.length - 1);
  return values[lo] + (values[hi] - values[lo]) * (k - lo);
}

function storeOptions(args) {
  return { mongoUri: args.mongoUri, mongoDb: args.mongoDb };
}

async function cmdBenchIndex(args) {
  const datamarts = path.join(args.data, 'datamarts');
  const lake = new Datalake(args.datalake, 'range');
  const allIds = loadIds(args.ids);
  const ids = allIds.slice(0, args.n);
  const extra = allIds.slice(args.n, args.n + args.add);
  const r = { store: args.store, n: ids.length };

  let store = await openStore(args.store, datamarts, storeOptions(args));
  const t0 = now();
  let [index, tokens] = buildIndex(lake, ids);
  const t1 = now();
  await store.writeFull(index);
  const t2 = now();
  let postings = 0;
  for (const found of index.values()) postings += found.size;
  Object.assign(r, { tokens, terms: index.size, postings, tokenize_s: t1 - t0, persist_s: t2 - t1, build_s: t2 - t0 });
  index = null;
  r.disk_bytes = await store.diskUsage();
  await store.close();

  store = await openStore(args.store, datamarts, storeOptions(args));
  let t = now();
  await store.open();
  r.open_ms = (now() - t) * 1000;

  const queries = loadQueries(args.queries).map((q) => [q, tokenize(q)]);
  const lines = [];
  for (const [q, terms] of queries) lines.push(`${q}\t${(await searchIds(store, terms)).join(',')}`); // calentamiento
  r.queries_digest = sha256(lines.join('\n'));
  const times = [];
  for (let rep = 0; rep < args.queryReps; rep++) {
    for (const [, terms] of queries) {
      t = now();
      await searchIds(store, terms);
      times.push((now() - t) * 1000);
    }
  }
  Object.assign(r, {
    query_mean_ms: times.reduce((a, b) => a + b, 0) / times.length,
    query_p50_ms: percentile(times, 0.5),
    query_p95_ms: percentile(times, 0.95),
  });

  let addTerms = 0;
  let addSeconds = 0;
  for (const bookId of extra) {
    const tfs = termFrequencies(lake.readBook(bookId)[1]);
    addTerms += tfs.size;
    t = now();
    await store.addBook(bookId, tfs);
    addSeconds += now() - t;
  }
  Object.assign(r, { add_books: extra.length, add_terms: addTerms, add_ms: addSeconds * 1000 });
  await store.close();
  emit(r);
}

// Construye metadatos e indice y los exporta en un formato fijo para comparar lenguajes.
async function cmdCanonical(args) {
  const datamarts = path.join(args.data, 'datamarts');
  fs.mkdirSync(datamarts, { recursive: true });
  const lake = new Datalake(args.datalake, 'range');
  const allIds = loadIds(args.ids);
  const ids = allIds.slice(0, args.n);
  const extra = allIds.slice(args.n, args.n + args.add);

  const metadata = new MetadataStore(path.join(datamarts, 'metadata.db'));
  for (const bookId of [...ids, ...extra]) {
    const [headerPath, bodyPath] = lake.locate(bookId);
    const meta = parseHeader(fs.readFileSync(headerPath, 'utf8'));
    metadata.upsert(bookId, meta, lake.relative(headerPath), lake.relative(bodyPath));
  }

  const store = await openStore(args.store, datamarts, storeOptions(args));
  let [index] = buildIndex(lake, ids);
  await store.writeFull(index);
  index = null;
  await store.open();
  for (const bookId of extra) await store.addBook(bookId, termFrequencies(lake.readBook(bookId)[1]));

  const value = (v) => (v === null || v === undefined ? '' : String(v));
  const columns = ['book_id', 'title', 'author', 'language', 'year', 'release_year'];
  const metaLines = metadata.all().map((row) => columns.map((c) => value(row[c])).join('\t'));
  const postLines = [];
  for await (const [term, found] of store.terms()) {
    const bookIds = [...found.keys()].sort(byNumber);
    postLines.push(`${term}\t${bookIds.map((b) => `${b}:${found.get(b)}`).join(',')}`);
  }
  // el sort normal de JS ordena por unidades UTF-16; python ordena por code point
  metaLines.sort(compareCodePoints);
  postLines.sort(compareCodePoints);
  await store.close();
  metadata.close();

  const result = { store: args.store };
  for (const [kind, lines] of [['metadata', metaLines], ['postings', postLines]]) {
    const text = lines.map((line) => line + '\n').join('');
    fs.writeFileSync(`${args.out}.${kind}.tsv`, text);
    result[`${kind}_lines`] = lines.length;
    result[`${kind}_sha256`] = sha256(text);
  }
  emit(result);
}

// ---------------------------------------------------------------- argumentos

export function benchCommands(storeArgs, layouts) {
  return {
    ingest: {
      help: 'ingesta de una lista de ids (con caida simulada opcional)',
      options: {
        data: { required: true },
        layout: { choices: layouts, default: 'range' },
        source: { required: true },
        ids: { required: true },
        n: { type: 'int', default: 10 ** 9 },
        clock: { choices: ['real', 'synthetic'], default: 'synthetic' },
        'clock-offset-hours': { type: 'int', default: 0 },
        'crash-after': { type: 'int' },
        'crash-at': { choices: ['between_renames', 'before_control'] },
        reconcile: { type: 'flag' },
        delay: { type: 'float', default: 0.0 },
      },
      run: cmdIngest,
    },
    'bench-datalake': {
      options: {
        data: { required: true },
        layout: { choices: layouts, required: true },
        source: { required: true },
        ids: { required: true },
        n: { type: 'int', required: true },
        new: { type: 'int', default: 10 },
        clock: { choices: ['real', 'synthetic'], default: 'synthetic' },
        'clock-offset-hours': { type: 'int', default: 0 },
      },
      run: cmdBenchDatalake,
    },
    'bench-index': {
      options: {
        data: { required: true },
        datalake: { required: true, help: 'datalake ya construido (layout range)' },
        ids: { required: true },
        n: { type: 'int', required: true },
        add: { type: 'int', default: 10 },
        queries: {},
        'query-reps': { type: 'int', default: 5 },
        ...storeArgs,
      },
      run: cmdBenchIndex,
    },
    canonical: {
      options: {
        data: { required: true },
        datalake: { required: true },
        ids: { required: true },
        n: { type: 'int', default: 55 },
        add: { type: 'int', default: 5 },
        out: { required: true },
        ...storeArgs,
      },
      run: cmdCanonical,
    },
  };
}
