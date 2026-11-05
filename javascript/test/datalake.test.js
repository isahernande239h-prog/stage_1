import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Control } from '../src/control.js';
import { Datalake, SyntheticClock, SimulatedCrash } from '../src/datalake.js';

const dirs = [];
after(() => dirs.forEach((dir) => fs.rmSync(dir, { recursive: true, force: true })));

function tmpDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stage1-js-'));
  dirs.push(dir);
  return dir;
}

for (const [layout, expected] of [
  ['book', '1342/header.txt'],
  ['range', '001000/1342.header.txt'],
  ['time', '20260101/00/1342.header.txt'],
]) {
  test(`paths in ${layout}`, () => {
    const lake = new Datalake(tmpDir(), layout, new SyntheticClock());
    lake.writeBook(1342, 'h', 'b');
    const [header, body] = lake.locate(1342);
    assert.equal(lake.relative(header), expected);
    assert.equal(fs.readFileSync(body, 'utf8'), 'b');
  });
}

test('the range bucket has six digits', () => {
  const lake = new Datalake(tmpDir(), 'range');
  assert.equal(lake.partitionFor(5), '000000');
  assert.equal(lake.partitionFor(75999), '075000');
});

test('the time layout uses the registry', () => {
  const root = tmpDir();
  const clock = new SyntheticClock({ booksPerHour: 2 });
  const lake = new Datalake(root, 'time', clock);
  for (const bookId of [1, 2, 3]) {
    lake.writeBook(bookId, 'h', 'b');
    clock.tick();
  }
  const fresh = new Datalake(root, 'time');
  assert.deepEqual(fresh.loadRegistry(), new Map([[1, '20260101/00'], [2, '20260101/00'], [3, '20260101/01']]));
  assert.notEqual(fresh.locate(3), null);
  assert.equal(fresh.locate(4), null);
});

test('full scan and pruned scan', () => {
  const clock = new SyntheticClock({ booksPerHour: 1 });
  const lake = new Datalake(tmpDir(), 'time', clock);
  for (let bookId = 1; bookId <= 5; bookId++) {
    lake.writeBook(bookId, 'h', 'b');
    clock.tick();
  }
  assert.deepEqual(lake.scan().ids(), new Set([1, 2, 3, 4, 5]));
  assert.deepEqual(lake.scan('20260101/03').ids(), new Set([4, 5]));
});

for (const layout of ['time', 'book', 'range']) {
  test(`crash between renames and reconciliation in ${layout}`, () => {
    const dir = tmpDir();
    const lake = new Datalake(path.join(dir, 'lake'), layout, new SyntheticClock());
    const control = new Control(path.join(dir, 'control'));
    lake.writeBook(1, 'h', 'b');
    control.markDownloaded(1);
    assert.throws(() => lake.writeBook(2, 'h', 'b', true), SimulatedCrash);

    let scan = lake.scan();
    assert.deepEqual(scan.ids(), new Set([1]));
    assert.equal(scan.orphans.length, 1);
    assert.equal(scan.temps.length, 1);

    const stats = lake.reconcile(control);
    assert.equal(stats.orphans, 1);
    assert.equal(stats.temps, 1);
    scan = lake.scan();
    assert.deepEqual(scan.ids(), new Set([1]));
    assert.equal(scan.orphans.length, 0);
    assert.equal(scan.temps.length, 0);
  });
}

test('reconciliation adds the complete books to the control', () => {
  const dir = tmpDir();
  const lake = new Datalake(path.join(dir, 'lake'), 'range');
  const control = new Control(path.join(dir, 'control'));
  lake.writeBook(7, 'h', 'b'); // crash before writing it to the control
  control.markDownloaded(99); // in the control but with no files
  const stats = lake.reconcile(control);
  assert.deepEqual(control.downloaded(), new Set([7]));
  assert.equal(stats.added_to_control, 1);
  assert.equal(stats.removed_from_control, 1);
});

test('reconciliation removes duplicates in time', () => {
  const dir = tmpDir();
  const lake = new Datalake(path.join(dir, 'lake'), 'time', new SyntheticClock());
  lake.writeBook(1, 'h', 'b');
  lake.clock = new SyntheticClock({ offsetHours: 24 });
  lake.writeBook(1, 'h', 'b');
  assert.deepEqual(lake.scan().complete.get(1), ['20260101/00', '20260102/00']);
  const stats = lake.reconcile(new Control(path.join(dir, 'control')));
  assert.equal(stats.duplicates, 1);
  assert.deepEqual(lake.scan().complete.get(1), ['20260102/00']);
});

test('the control ignores the cut-off last line', () => {
  const control = new Control(tmpDir());
  control.markDownloaded(10);
  control.markDownloaded(11);
  fs.appendFileSync(control.downloadedFile, '12'); // the write got cut off
  assert.deepEqual(control.downloaded(), new Set([10, 11]));
});

test('pending and failed', () => {
  const control = new Control(tmpDir());
  for (const id of [1, 2, 3]) control.markDownloaded(id);
  control.markIndexed(2);
  control.markFailed(4, 'not_found');
  assert.deepEqual(control.pending(), new Set([1, 3]));
  assert.deepEqual(control.failed(), new Map([[4, 'not_found']]));
});

test('disk space used', () => {
  const lake = new Datalake(tmpDir(), 'book');
  lake.writeBook(1, 'hh', 'bbb');
  lake.writeBook(2, 'hh', 'bbb');
  const stats = lake.storageStats();
  assert.equal(stats.files, 4);
  assert.equal(stats.dirs, 2);
  assert.equal(stats.bytes, 10);
});

test('reconcile time with the datalake still empty', () => {
  const dir = tmpDir();
  const lake = new Datalake(path.join(dir, 'lake'), 'time');
  assert.equal(lake.reconcile(new Control(path.join(dir, 'control'))).temps, 0);
});
