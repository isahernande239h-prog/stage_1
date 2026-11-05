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

test('basic split', () => {
  const [header, body] = splitBook(BOOK);
  assert.equal(header, 'The Project Gutenberg eBook of Test\nTitle: Test');
  assert.equal(body, 'Chapter 1\nSome text.');
});

test('THIS and E-BOOK variants', () => {
  const text = 'h\n***START OF THIS PROJECT GUTENBERG E-BOOK X\nbody\n*** end of this project gutenberg e-book X\nf';
  assert.deepEqual(splitBook(text), ['h', 'body']);
});

test('end marker missing', () => {
  assert.throws(() => splitBook('header\n*** START OF THE PROJECT GUTENBERG EBOOK X\nbody only\n'), NoMarkersError);
});

test('the marker has to be at the start of the line', () => {
  const text = 'h\nsee *** START OF THE PROJECT GUTENBERG EBOOK here\nbody\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test("loose asterisks inside the body don't count", () => {
  const text = 'h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n'
    + 'a\n***\nb\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.equal(splitBook(text)[1], 'a\n***\nb');
});

test('only ASCII spaces get trimmed', () => {
  const text = 'h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n body \n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.equal(splitBook(text)[1], ' body ');
});

test("U+2028 doesn't split lines", () => {
  const text = 'h *** START OF THE PROJECT GUTENBERG EBOOK X\nbody\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test("non-ASCII uppercase doesn't count as a marker", () => {
  // ſ (long s) in uppercase is S, but with re.ASCII it doesn't match
  const text = 'h\n*** ſTART OF THE PROJECT GUTENBERG EBOOK X\nbody\n*** END OF THE PROJECT GUTENBERG EBOOK X\n';
  assert.throws(() => splitBook(text), NoMarkersError);
});

test('decode removes the BOM', () => {
  assert.equal(decode(Buffer.from('﻿hello', 'utf8')), 'hello');
});

test('decode removes only one BOM and replaces invalid bytes', () => {
  const raw = Buffer.from([0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x41, 0xc3]);
  assert.equal(decode(raw), '﻿A�');
});
