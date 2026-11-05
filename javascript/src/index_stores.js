// Inverted index structures. They all have the same interface:
//
//   writeFull(index)       saves a full index Map term -> Map book_id -> tf
//   open()                 gets the structure ready for queries
//   addBook(id, tfs)       adds (or overwrites) a book
//   postings(term)         Map book_id -> tf
//   terms()                iterates over [term, postings] for exporting
//   diskUsage()            bytes on disk
//
// The mongo ones return promises, so from outside they're always called with await.
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export const STORES = ['json', 'hier', 'mongo', 'sqlite'];
export const DEFAULT_MONGO_URI = 'mongodb://127.0.0.1:27017';

// code point order (= UTF-8 byte order), the one used by python's sorted() and SQLite.
// JS < compares UTF-16 units and would put letters outside the BMP before U+E000-U+FFFF.
export function compareCodePoints(a, b) {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    const x = a.charCodeAt(i);
    const y = b.charCodeAt(i);
    if (x !== y) {
      if (x >= 0xd800 && y >= 0xd800) return codePointRank(x) - codePointRank(y);
      return x - y;
    }
  }
  return a.length - b.length;
}

function codePointRank(unit) {
  // surrogates (letters outside the BMP) go after U+E000-U+FFFF
  return unit >= 0xe000 ? unit - 0x800 : unit + 0x2000;
}

function byNumber(a, b) {
  return a - b;
}

// mongo keys ("1342") -> Map with numeric ids
function toPostings(obj) {
  const found = new Map();
  for (const key of Object.keys(obj)) found.set(Number(key), obj[key]);
  return found;
}

// JSON.parse puts keys that look like numbers in ascending order ("1853" before "10004")
// and python keeps the order of the file. Since we write the format ourselves
// ({"term":{"id":tf,...},...} with no spaces), it's read by hand and the file stays the same as in python.
function parseIndex(text) {
  if (text[0] !== '{') throw new Error('inverted_index.json does not have the expected format');
  const index = new Map();
  let i = 1;
  while (i < text.length - 1) {
    const close = text.indexOf('":{', i); // terms only have letters, never quotes
    const term = JSON.parse(text.slice(i, close + 1));
    const end = text.indexOf('}', close);
    const found = new Map();
    let j = close + 3;
    while (j < end) {
      const colon = text.indexOf(':', j);
      let comma = text.indexOf(',', colon);
      if (comma < 0 || comma > end) comma = end;
      found.set(Number(text.slice(j + 1, colon - 1)), Number(text.slice(colon + 1, comma)));
      j = comma + 1;
    }
    index.set(term, found);
    i = end + 2;
  }
  return index;
}

class JsonStore {
  constructor(datamarts) {
    this.file = path.join(datamarts, 'inverted_index.json');
    this.tmp = path.join(datamarts, 'inverted_index.tmp');
    this.index = null;
  }

  writeFull(index) {
    this.save(index);
    this.index = null;
  }

  // written in chunks: with half a million terms a single string would be huge
  save(index) {
    const fd = fs.openSync(this.tmp, 'w');
    let chunk = '{';
    let first = true;
    for (const [term, found] of index) {
      const parts = [];
      for (const [bookId, tf] of found) parts.push(`"${bookId}":${tf}`);
      chunk += `${first ? '' : ','}${JSON.stringify(term)}:{${parts.join(',')}}`;
      first = false;
      if (chunk.length > 1 << 20) {
        fs.writeSync(fd, chunk);
        chunk = '';
      }
    }
    fs.writeSync(fd, chunk + '}');
    fs.closeSync(fd);
    fs.renameSync(this.tmp, this.file);
  }

  open() {
    // a Map and not an object: terms like "constructor" would clash with the prototype
    if (fs.existsSync(this.file)) {
      this.index = parseIndex(fs.readFileSync(this.file, 'utf8'));
    } else {
      this.index = new Map();
    }
  }

  addBook(bookId, tfs) {
    // there's no way to change part of the file: it gets rewritten whole
    if (this.index === null) this.open();
    for (const [term, tf] of tfs) {
      let found = this.index.get(term);
      if (found === undefined) {
        found = new Map();
        this.index.set(term, found);
      }
      found.set(bookId, tf);
    }
    this.save(this.index);
  }

  postings(term) {
    const found = this.index.get(term);
    return found ? new Map(found) : new Map();
  }

  *terms() {
    yield* this.index;
  }

  diskUsage() {
    return fs.statSync(this.file).size;
  }

  close() {
    this.index = null;
  }
}

const RESERVED = new Set(['con', 'prn', 'aux', 'nul']);
for (let i = 1; i <= 9; i++) {
  RESERVED.add(`com${i}`);
  RESERVED.add(`lpt${i}`);
}

export function termFolder(term) {
  const first = term[0];
  return first >= 'a' && first <= 'z' ? first.toUpperCase() : '_';
}

// a-z as is and the rest as %XX: the Windows and macOS file systems
// are case-insensitive, and greek terms like "οδος" and "οδοσ" would clash
export function termFilename(term) {
  let name = '';
  for (const ch of term) {
    if (ch >= 'a' && ch <= 'z') {
      name += ch;
    } else {
      for (const byte of Buffer.from(ch, 'utf8')) name += '%' + byte.toString(16).toUpperCase().padStart(2, '0');
    }
  }
  if (RESERVED.has(name)) name += '_';
  return name + '.txt';
}

export function filenameToTerm(filename) {
  let name = filename.slice(0, -4);
  if (name.endsWith('_')) name = name.slice(0, -1);
  const bytes = [];
  let i = 0;
  while (i < name.length) {
    if (name[i] === '%') {
      bytes.push(parseInt(name.slice(i + 1, i + 3), 16));
      i += 3;
    } else {
      bytes.push(name.charCodeAt(i));
      i += 1;
    }
  }
  return Buffer.from(bytes).toString('utf8');
}

class HierStore {
  constructor(datamarts) {
    this.root = path.join(datamarts, 'inverted_index');
  }

  path(term) {
    return path.join(this.root, termFolder(term), termFilename(term));
  }

  makeFolders() {
    for (const folder of [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ', '_']) {
      fs.mkdirSync(path.join(this.root, folder), { recursive: true });
    }
  }

  writeFull(index) {
    this.makeFolders();
    for (const [term, found] of index) {
      let text = '';
      for (const [bookId, tf] of found) text += `${bookId}\t${tf}\n`;
      fs.writeFileSync(this.path(term), text);
    }
  }

  open() {
    this.makeFolders();
  }

  addBook(bookId, tfs) {
    for (const [term, tf] of tfs) fs.appendFileSync(this.path(term), `${bookId}\t${tf}\n`);
  }

  static read(file) {
    const found = new Map();
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      if (line === '') continue;
      const [bookId, tf] = line.split('\t');
      found.set(Number(bookId), Number(tf)); // if a book shows up twice, the last line wins
    }
    return found;
  }

  postings(term) {
    const file = this.path(term);
    return fs.existsSync(file) ? HierStore.read(file) : new Map();
  }

  *terms() {
    for (const folder of fs.readdirSync(this.root).sort()) {
      const dir = path.join(this.root, folder);
      for (const name of fs.readdirSync(dir)) yield [filenameToTerm(name), HierStore.read(path.join(dir, name))];
    }
  }

  diskUsage() {
    // allocated bytes (blocks), which is what matters with half a million files
    let total = 0;
    const walk = (folder) => {
      for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
        const full = path.join(folder, entry.name);
        if (entry.isDirectory()) walk(full);
        else total += fs.statSync(full).blocks * 512;
      }
    };
    walk(this.root);
    return total;
  }

  close() {}
}

class MongoStore {
  static BATCH = 5000;

  constructor(client, dbName) {
    this.client = client;
    this.db = client.db(dbName);
    this.coll = this.db.collection('inverted_index');
  }

  async writeFull(index) {
    await this.coll.drop();
    let batch = [];
    for (const [term, found] of index) {
      // a Map (and not an object) so the ids stay in the same order as in python
      const postings = new Map();
      for (const [bookId, tf] of found) postings.set(String(bookId), tf);
      batch.push({ _id: term, postings });
      if (batch.length === MongoStore.BATCH) {
        await this.coll.insertMany(batch, { ordered: false });
        batch = [];
      }
    }
    if (batch.length > 0) await this.coll.insertMany(batch, { ordered: false });
  }

  async open() {}

  async addBook(bookId, tfs) {
    const ops = [];
    for (const [term, tf] of tfs) {
      ops.push({ updateOne: { filter: { _id: term }, update: { $set: { [`postings.${bookId}`]: tf } }, upsert: true } });
    }
    for (let i = 0; i < ops.length; i += MongoStore.BATCH) {
      await this.coll.bulkWrite(ops.slice(i, i + MongoStore.BATCH), { ordered: false });
    }
  }

  async postings(term) {
    const doc = await this.coll.findOne({ _id: term });
    return doc ? toPostings(doc.postings) : new Map();
  }

  async *terms() {
    for await (const doc of this.coll.find()) yield [doc._id, toPostings(doc.postings)];
  }

  async diskUsage() {
    // without fsync, collStats can return almost 0 because WiredTiger hasn't written the checkpoint yet
    await this.client.db('admin').command({ fsync: 1 });
    const stats = await this.db.command({ collStats: 'inverted_index' });
    return stats.storageSize + stats.totalIndexSize;
  }

  async drop() {
    await this.db.dropDatabase();
  }

  async close() {
    await this.client.close();
  }
}

class SqliteStore {
  constructor(datamarts) {
    this.file = path.join(datamarts, 'inverted_index.db');
    this.db = null;
  }

  connect() {
    if (this.db === null) {
      this.db = new DatabaseSync(this.file);
      this.db.exec(`CREATE TABLE IF NOT EXISTS postings (
        term TEXT NOT NULL, book_id INTEGER NOT NULL, tf INTEGER NOT NULL,
        PRIMARY KEY (term, book_id)) WITHOUT ROWID`);
      this.selectStmt = this.db.prepare('SELECT book_id, tf FROM postings WHERE term = ?');
      this.replaceStmt = this.db.prepare('INSERT OR REPLACE INTO postings VALUES (?, ?, ?)');
    }
  }

  // everything inside a transaction (like python's "with conn")
  transaction(work) {
    this.db.exec('BEGIN');
    try {
      work();
      this.db.exec('COMMIT');
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  writeFull(index) {
    this.connect();
    const insert = this.db.prepare('INSERT INTO postings VALUES (?, ?, ?)');
    this.transaction(() => {
      this.db.exec('DELETE FROM postings');
      // inserting in key order is much faster in the B-tree
      for (const term of [...index.keys()].sort(compareCodePoints)) {
        const found = index.get(term);
        for (const bookId of [...found.keys()].sort(byNumber)) insert.run(term, bookId, found.get(bookId));
      }
    });
  }

  open() {
    this.connect();
  }

  addBook(bookId, tfs) {
    this.transaction(() => {
      for (const [term, tf] of tfs) this.replaceStmt.run(term, bookId, tf);
    });
  }

  postings(term) {
    const found = new Map();
    for (const row of this.selectStmt.all(term)) found.set(row.book_id, row.tf);
    return found;
  }

  *terms() {
    let current = null;
    let found = new Map();
    const rows = this.db.prepare('SELECT term, book_id, tf FROM postings ORDER BY term, book_id').iterate();
    for (const row of rows) {
      if (row.term !== current) {
        if (current !== null) yield [current, found];
        current = row.term;
        found = new Map();
      }
      found.set(row.book_id, row.tf);
    }
    if (current !== null) yield [current, found];
  }

  diskUsage() {
    return fs.statSync(this.file).size;
  }

  close() {
    if (this.db !== null) {
      this.db.close();
      this.db = null;
    }
  }
}

export async function openStore(name, datamarts, { mongoUri = DEFAULT_MONGO_URI, mongoDb = 'searchengine' } = {}) {
  fs.mkdirSync(datamarts, { recursive: true });
  if (name === 'json') return new JsonStore(datamarts);
  if (name === 'hier') return new HierStore(datamarts);
  if (name === 'sqlite') return new SqliteStore(datamarts);
  if (name === 'mongo') {
    // the driver is only loaded if needed (same as pymongo in python)
    const { MongoClient } = await import('mongodb');
    return new MongoStore(new MongoClient(mongoUri), mongoDb);
  }
  throw new Error(`unknown store: ${name}`);
}

// Books that have all the terms, sorted by id.
export async function searchIds(store, terms) {
  let result = null;
  for (const term of new Set(terms)) {
    const found = await store.postings(term);
    result = result === null ? new Set(found.keys()) : new Set([...result].filter((id) => found.has(id)));
    if (result.size === 0) return [];
  }
  return result ? [...result].sort(byNumber) : [];
}
