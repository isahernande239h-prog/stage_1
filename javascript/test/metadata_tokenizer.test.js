import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseHeader, MetadataStore } from '../src/metadata.js';
import { tokenize, termFrequencies } from '../src/tokenizer.js';

const HEADER = `The Project Gutenberg eBook of Pride and Prejudice

This ebook is for the use of anyone anywhere in the United States and
most other parts of the world at no cost and with almost no restrictions
whatsoever.

Title: Pride and Prejudice

Author: Jane Austen

Release date: June 1, 1998 [eBook #1342]
                Most recently updated: October 29, 2024

Language: English

Original publication: London: T. Egerton, 1813

Credits: Chuck Greif`;

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stage1-js-'));
after(() => fs.rmSync(dir, { recursive: true, force: true }));

test('cabecera completa', () => {
  assert.deepEqual(parseHeader(HEADER), {
    title: 'Pride and Prejudice', author: 'Jane Austen', language: 'en', year: 1813, release_year: 1998,
  });
});

test('lineas de continuacion', () => {
  const header = 'Title: The Life and Adventures\n   of Robinson Crusoe\n\n'
    + 'Author: First Author\n        Second Author\n\nLanguage: French and English';
  const meta = parseHeader(header);
  assert.equal(meta.title, 'The Life and Adventures of Robinson Crusoe');
  assert.equal(meta.author, 'First Author; Second Author');
  assert.equal(meta.language, 'fr');
});

test('los campos que faltan son null', () => {
  const meta = parseHeader('Title: Only a title\nLanguage: Klingon');
  assert.equal(meta.author, null);
  assert.equal(meta.year, null);
  assert.equal(meta.language, null);
});

test('el ano de publicacion ignora el numero de ebook', () => {
  assert.equal(parseHeader('Release Date: [EBook #1234] March, 2004').release_year, 2004);
});

test('varios campos author y U+2028 dentro de un valor', () => {
  const meta = parseHeader('Author: A\nAuthor: B C\nOriginal publication: 1850-1851');
  assert.equal(meta.author, 'A; B C');
  assert.equal(meta.year, 1851);
});

test('upsert no duplica', () => {
  const store = new MetadataStore(path.join(dir, 'metadata.db'));
  const meta = parseHeader(HEADER);
  store.upsert(1342, meta, '001000/1342.header.txt', '001000/1342.body.txt');
  store.upsert(1342, meta, '001000/1342.header.txt', '001000/1342.body.txt');
  assert.equal(store.all().length, 1);
  assert.equal(store.get(1342).author, 'Jane Austen');
  assert.equal(store.get(1342).year, 1813);
  assert.equal(store.byAuthor('austen')[0].book_id, 1342);
  assert.equal(store.get(1), null);
  store.close();
});

test('reglas del tokenizador', () => {
  const text = "The Whale! a whale's TALE, café-au-lait 1851 x Δέλτα";
  assert.deepEqual(tokenize(text), ['whale', 'whale', 'tale', 'café', 'au', 'lait', 'δέλτα']);
});

test('letras unicode y longitud en code points', () => {
  // 𝔄 es una letra fuera del BMP: cuenta como un caracter
  assert.deepEqual(tokenize('𝔄𝔅 𝔄'), ['𝔄𝔅']);
});

test('sigma final igual que en python', () => {
  assert.deepEqual(tokenize('ΟΔΟΣ ΣΑΣ'), ['οδος', 'σας']);
});

test('frecuencias', () => {
  assert.deepEqual(termFrequencies('sea sea ship the'), new Map([['sea', 2], ['ship', 1]]));
});
