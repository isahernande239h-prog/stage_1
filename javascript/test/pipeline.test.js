import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Pipeline, search } from '../src/pipeline.js';
import { percentile } from '../src/bench.js';

const HEADER = 'Title: {title}\n\nAuthor: {author}\n\nLanguage: English\n';
const BOOKS = new Map([
  [1, ['Moby Dick', 'Herman Melville', 'the whale and the sea']],
  [2, ['Treasure Island', 'R. L. Stevenson', 'an island with gold treasure by the sea']],
  [3, ['Missing', 'Nobody', null]], // 404
]);

const dirs = [];
after(() => dirs.forEach((dir) => fs.rmSync(dir, { recursive: true, force: true })));

function tmpDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stage1-js-'));
  dirs.push(dir);
  return dir;
}

function makeCache(folder) {
  fs.mkdirSync(folder);
  for (const [bookId, [title, author, body]] of BOOKS) {
    if (body === null) continue;
    const text = HEADER.replace('{title}', title).replace('{author}', author)
      + '*** START OF THE PROJECT GUTENBERG EBOOK X ***\n' + body
      + '\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\n';
    fs.writeFileSync(path.join(folder, `pg${bookId}.txt`), text);
  }
  fs.writeFileSync(path.join(folder, 'pg4.txt'), 'no markers here');
}

test('el bucle de control descarga y luego indexa', async () => {
  const dir = tmpDir();
  makeCache(path.join(dir, 'cache'));
  const data = path.join(dir, 'data');
  const logs = [];
  const pipe = await Pipeline.create(data, {
    layout: 'range', store: 'sqlite', source: `cache:${path.join(dir, 'cache')}`,
    candidates: [1, 2, 3, 4], delay: 0, log: (m) => logs.push(m),
  });

  assert.deepEqual(await pipe.step(), ['downloaded', 1]);
  assert.deepEqual(await pipe.step(), ['indexed', 1]); // primero se indexa lo pendiente
  assert.deepEqual(await pipe.step(), ['downloaded', 2]);
  assert.deepEqual(await pipe.step(), ['indexed', 2]);
  assert.deepEqual(await pipe.step(), ['failed', 3]);
  assert.deepEqual(await pipe.step(), ['failed', 4]);
  assert.deepEqual(await pipe.step(), ['idle', null]);
  assert.deepEqual(pipe.control.failed(), new Map([[3, 'not_found'], [4, 'no_markers']]));
  assert.deepEqual(pipe.control.downloaded(), new Set([1, 2]));
  assert.deepEqual(pipe.control.indexed(), new Set([1, 2]));
  assert.equal(logs[0], '[CONTROL] descargando libro 1');
  assert.ok(logs.includes('[CONTROL] libro 4 descartado: no_markers'));
  await pipe.close();

  const result = await search(data, 'sqlite', 'the sea');
  assert.deepEqual(result.terms, ['sea']);
  assert.deepEqual(result.results.map((r) => r.book_id), [1, 2]);
  assert.equal(result.results[1].title, 'Treasure Island');
  assert.deepEqual(Object.keys(result.results[0]), ['book_id', 'title', 'author', 'language', 'year']);
  assert.equal((await search(data, 'sqlite', 'gold island')).total, 1);
  assert.equal((await search(data, 'sqlite', 'the of')).total, 0);
});

test('el pipeline sigue donde lo dejo sin duplicar', async () => {
  const dir = tmpDir();
  makeCache(path.join(dir, 'cache'));
  const data = path.join(dir, 'data');
  const options = { layout: 'range', store: 'json', source: `cache:${path.join(dir, 'cache')}`, candidates: [1, 2], delay: 0, log: () => {} };
  let pipe = await Pipeline.create(data, options);
  await pipe.run(3);
  await pipe.close();
  pipe = await Pipeline.create(data, options);
  await pipe.run(10);
  await pipe.close();
  const lines = fs.readFileSync(path.join(data, 'control', 'downloaded_books.txt'), 'utf8').split(/\s+/).filter((x) => x);
  assert.deepEqual(lines.sort(), ['1', '2']);
  assert.equal((await search(data, 'json', 'sea')).total, 2);
});

test('la reconciliacion se anota en el log como en python', async () => {
  const dir = tmpDir();
  makeCache(path.join(dir, 'cache'));
  const data = path.join(dir, 'data');
  fs.mkdirSync(path.join(data, 'control'), { recursive: true });
  fs.writeFileSync(path.join(data, 'control', 'downloaded_books.txt'), '99\n');
  const logs = [];
  const pipe = await Pipeline.create(data, { source: `cache:${path.join(dir, 'cache')}`, candidates: [1], log: (m) => logs.push(m) });
  await pipe.run(0);
  await pipe.close();
  assert.deepEqual(logs, ["[CONTROL] reconciliacion: {'temps': 0, 'orphans': 0, 'duplicates': 0, "
    + "'added_to_control': 0, 'removed_from_control': 1}"]);
});

test('percentiles con interpolacion lineal', () => {
  assert.equal(percentile([4, 1, 3, 2], 0.5), 2.5);
  assert.equal(percentile([1, 2, 3, 4, 5], 0.95), 4.8);
  assert.equal(percentile([7], 0.95), 7);
});
