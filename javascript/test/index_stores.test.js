import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { MongoClient } from 'mongodb';
import {
  openStore, searchIds, termFilename, filenameToTerm, compareCodePoints, STORES, DEFAULT_MONGO_URI,
} from '../src/index_stores.js';

async function mongoAvailable() {
  const client = new MongoClient(DEFAULT_MONGO_URI, { serverSelectionTimeoutMS: 500 });
  try {
    await client.db('admin').command({ ping: 1 });
    return true;
  } catch {
    return false;
  } finally {
    await client.close();
  }
}

const HAS_MONGO = await mongoAvailable();

const INDEX = new Map([
  ['island', new Map([[5, 3], [1342, 1]])],
  ['adventure', new Map([[5, 2], [12, 1]])],
  ['ship', new Map([[12, 4]])],
  ['οδος', new Map([[7, 1]])],
  // terminos que en un objeto normal chocarian con el prototipo
  ['constructor', new Map([[3, 1]])],
  ['__proto__', new Map([[3, 2]])],
]);

const dirs = [];
after(() => dirs.forEach((dir) => fs.rmSync(dir, { recursive: true, force: true })));

async function withStore(name, work) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stage1-js-'));
  dirs.push(dir);
  const store = await openStore(name, path.join(dir, 'datamarts'), { mongoDb: 'stage1_nodetest' });
  try {
    await work(store);
  } finally {
    if (name === 'mongo') await store.drop();
    await store.close();
  }
}

for (const name of STORES) {
  const skip = name === 'mongo' && !HAS_MONGO ? 'MongoDB no esta arrancado' : false;

  test(`${name}: construir y consultar`, { skip }, () => withStore(name, async (store) => {
    await store.writeFull(INDEX);
    await store.open();
    assert.deepEqual(await store.postings('island'), new Map([[5, 3], [1342, 1]]));
    assert.deepEqual(await store.postings('missing'), new Map());
    assert.deepEqual(await store.postings('οδος'), new Map([[7, 1]]));
    assert.deepEqual(await store.postings('constructor'), new Map([[3, 1]]));
    assert.deepEqual(await store.postings('__proto__'), new Map([[3, 2]]));
    assert.deepEqual(await store.postings('toString'), new Map());
    assert.deepEqual(await searchIds(store, ['island', 'adventure']), [5]);
    assert.deepEqual(await searchIds(store, ['island', 'ship']), []);
    assert.deepEqual(await searchIds(store, []), []);
  }));

  test(`${name}: anadir un libro dos veces no lo duplica`, { skip }, () => withStore(name, async (store) => {
    await store.writeFull(INDEX);
    await store.open();
    await store.addBook(99, new Map([['island', 2], ['volcano', 1], ['__proto__', 1]]));
    await store.addBook(99, new Map([['island', 5], ['volcano', 1], ['__proto__', 4]]));
    assert.deepEqual(await store.postings('island'), new Map([[5, 3], [1342, 1], [99, 5]]));
    assert.deepEqual(await store.postings('volcano'), new Map([[99, 1]]));
    assert.deepEqual(await store.postings('__proto__'), new Map([[3, 2], [99, 4]]));
  }));

  test(`${name}: exportar los terminos`, { skip }, () => withStore(name, async (store) => {
    await store.writeFull(INDEX);
    await store.open();
    const exported = new Map();
    for await (const [term, found] of store.terms()) exported.set(term, found);
    assert.equal(exported.size, INDEX.size);
    for (const [term, found] of INDEX) assert.deepEqual(exported.get(term), found);
  }));
}

test('el json sobrevive a reabrirlo con terminos raros', () => withStore('json', async (store) => {
  await store.writeFull(INDEX);
  await store.addBook(8, new Map([['constructor', 1]]));
  store.close();
  store.open();
  assert.deepEqual(store.postings('constructor'), new Map([[3, 1], [8, 1]]));
  assert.deepEqual(store.postings('__proto__'), new Map([[3, 2]]));
}));

test('el json conserva el orden de los ids al reescribirse (como python)', () => withStore('json', async (store) => {
  await store.writeFull(new Map([['sea', new Map([[1342, 1], [5, 2]])]]));
  await store.addBook(7, new Map([['sea', 1], ['ship', 3]]));
  assert.equal(fs.readFileSync(store.file, 'utf8'), '{"sea":{"1342":1,"5":2,"7":1},"ship":{"7":3}}');
}));

test('nombres de fichero', () => {
  assert.equal(termFilename('adventure'), 'adventure.txt');
  assert.equal(termFilename('con'), 'con_.txt');
  assert.equal(termFilename('café'), 'caf%C3%A9.txt');
  assert.equal(termFilename('𝔄𝔅'), '%F0%9D%94%84%F0%9D%94%85.txt');
  for (const term of ['café', 'con', 'οδος', 'nul', '𝔄𝔅']) assert.equal(filenameToTerm(termFilename(term)), term);
});

test('orden por code point', () => {
  // con el orden UTF-16 de JS, 𝔄 (U+1D504) quedaria antes que ﬀ (U+FB00)
  const words = ['ﬀ', '𝔄', 'b', 'a', 'ab'];
  assert.deepEqual(words.sort(compareCodePoints), ['a', 'ab', 'b', 'ﬀ', '𝔄']);
});
