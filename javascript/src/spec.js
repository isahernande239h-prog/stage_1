// Acceso a los ficheros compartidos de spec/ (los mismos para los tres lenguajes).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const SPEC_DIR = process.env.STAGE1_SPEC ?? fileURLToPath(new URL('../../spec', import.meta.url));

// los mismos espacios que str.strip() de python (trim() tambien quita el BOM y no quita \x1c-\x1f ni \x85)
const SPACES = '[\\t\\n\\v\\f\\r\\x1c-\\x20\\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]';
const STRIP_RE = new RegExp(`^${SPACES}+|${SPACES}+$`, 'g');

function strip(text) {
  return text.replace(STRIP_RE, '');
}

function lines(file) {
  const text = fs.readFileSync(file, 'utf8').replace(/\r\n?/g, '\n');
  return text.split('\n').filter((line) => strip(line) !== '' && !line.startsWith('#')).map(strip);
}

export function loadStopwords() {
  return new Set(lines(path.join(SPEC_DIR, 'stopwords.txt')));
}

export function loadLanguages() {
  const table = new Map();
  for (const line of lines(path.join(SPEC_DIR, 'languages.tsv'))) {
    const [name, code] = line.split('\t');
    table.set(name, code);
  }
  return table;
}

export function loadQueries(file = null) {
  return lines(file ?? path.join(SPEC_DIR, 'queries.txt'));
}

export function loadIds(file = null) {
  return lines(file ?? path.join(SPEC_DIR, 'corpus_ids.txt')).map(Number);
}
