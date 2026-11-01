import { test } from 'node:test';
import assert from 'node:assert/strict';
import { splitBook, NoMarkersError } from '../src/splitter.js';
import { decode } from '../src/source.js';

const BOOK = 'The Project Gutenberg eBook of Test\r\n'
  + 'Title: Test\r\n'
  + '\r\n'
  + '*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n'
  + '\r\n'
  + 'Chapter 1\r\n'
  + 'Some text.\r\n'
  + '*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n'
  + 'License stuff\r\n';

test('separacion basica', () => {
  const [header, body] = splitBook(BOOK);
  assert.equal(header, 'The Project Gutenberg eBook of Test\nTitle: Test');
  assert.equal(body, 'Chapter 1\nSome text.');
});

test('variantes THIS y E-BOOK', () => {
  const text = 'h\n***START OF THIS PROJECT GUTENBERG E-BOOK X\nbody\n*** end of this project gutenberg e-book X\nf';
  assert.deepEqual(splitBook(text), ['h', 'body']);
});

test('falta el marcador de fin', () => {
  assert.throws(() => splitBook('header\n*** START OF THE PROJECT GUTENBERG EBOOK X\nbody only\n'), NoMarkersError);
});

test('el marcador tiene que estar al principio de la linea', () => {
  const text = 'h\nsee *** START OF THE PROJECT GUTENBERG EBOOK here\nbody\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test('asteriscos sueltos dentro del cuerpo no cuentan', () => {
  const text = 'h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n'
    + 'a\n***\nb\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.equal(splitBook(text)[1], 'a\n***\nb');
});

test('solo se recortan espacios ASCII', () => {
  const text = 'h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n body \n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.equal(splitBook(text)[1], ' body ');
});

test('U+2028 no separa lineas', () => {
  const text = 'h *** START OF THE PROJECT GUTENBERG EBOOK X\nbody\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test('las mayusculas no ASCII no cuentan como marcador', () => {
  // ſ (s larga) en mayusculas es S, pero con re.ASCII no coincide
  const text = 'h\n*** ſTART OF THE PROJECT GUTENBERG EBOOK X\nbody\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test('decode quita el BOM', () => {
  assert.equal(decode(Buffer.from('﻿hola', 'utf8')), 'hola');
});

test('decode quita un solo BOM y sustituye bytes invalidos', () => {
  const raw = Buffer.from([0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x41, 0xc3]);
  assert.equal(decode(raw), '﻿A�');
});
