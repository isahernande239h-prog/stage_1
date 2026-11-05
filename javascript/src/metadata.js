// Metadata taken from the header and stored in SQLite (datamarts/metadata.db).
import { DatabaseSync } from 'node:sqlite';
import { loadLanguages } from './spec.js';

// with the s flag the dot also matches U+2028 and U+2029, like in python
const FIELD_RE = /^([A-Za-z][A-Za-z -]*):[ \t]*(.*)$/s;
const YEAR_RE = /(?<![0-9])[0-9]{4}(?![0-9])/g;
const SPACES_RE = /[ \t]+/g;
const LANG_SPLIT_RE = /,|;| and /i;
const LANGUAGES = loadLanguages();

function stripSpaceTab(text) {
  return text.replace(/^[ \t]+|[ \t]+$/g, '');
}

function clean(value) {
  return stripSpaceTab(value.replace(SPACES_RE, ' '));
}

// Map lowercase name -> [values]. Each value is a list of lines (the first one + continuations).
export function headerFields(header) {
  const fields = new Map();
  let current = null;
  for (const line of header.split('\n')) {
    if ((line[0] === ' ' || line[0] === '\t') && current !== null && stripSpaceTab(line) !== '') {
      current.push(clean(line));
      continue;
    }
    const match = FIELD_RE.exec(line);
    if (match) {
      current = [clean(match[2])];
      const name = match[1].toLowerCase();
      if (!fields.has(name)) fields.set(name, []);
      fields.get(name).push(current);
    } else {
      current = null;
    }
  }
  return fields;
}

export function parseHeader(header) {
  const fields = headerFields(header);

  let title = null;
  if (fields.has('title')) {
    title = fields.get('title')[0].filter((x) => x).join(' ') || null;
  }

  const authors = (fields.get('author') ?? []).flat().filter((x) => x);
  const author = authors.join('; ') || null;

  let language = null;
  if (fields.has('language')) {
    const first = fields.get('language')[0].join(' ').split(LANG_SPLIT_RE)[0];
    language = LANGUAGES.get(clean(first).toLowerCase()) ?? null;
  }

  let year = null;
  if (fields.has('original publication')) {
    const years = fields.get('original publication')[0].join(' ').match(YEAR_RE);
    if (years) year = Number(years[years.length - 1]);
  }

  let releaseYear = null;
  if (fields.has('release date')) {
    const text = fields.get('release date')[0].join(' ').replace(/\[[^\]]*\]/g, '');
    const found = text.match(YEAR_RE);
    if (found) releaseYear = Number(found[0]);
  }

  return { title, author, language, year, release_year: releaseYear };
}

export class MetadataStore {
  constructor(file) {
    this.db = new DatabaseSync(file);
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS books (
        book_id INTEGER PRIMARY KEY,
        title TEXT, author TEXT, language TEXT,
        year INTEGER, release_year INTEGER,
        header_path TEXT, body_path TEXT)`);
    this.upsertStmt = this.db.prepare('INSERT OR REPLACE INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
    this.getStmt = this.db.prepare('SELECT * FROM books WHERE book_id = ?');
  }

  upsert(bookId, meta, headerPath, bodyPath) {
    this.upsertStmt.run(bookId, meta.title, meta.author, meta.language, meta.year,
      meta.release_year, headerPath, bodyPath);
  }

  get(bookId) {
    const row = this.getStmt.get(bookId);
    return row ? { ...row } : null;
  }

  all() {
    return this.db.prepare('SELECT * FROM books ORDER BY book_id').all().map((row) => ({ ...row }));
  }

  byAuthor(text) {
    const rows = this.db.prepare('SELECT * FROM books WHERE author LIKE ? ORDER BY book_id').all(`%${text}%`);
    return rows.map((row) => ({ ...row }));
  }

  close() {
    this.db.close();
  }
}
