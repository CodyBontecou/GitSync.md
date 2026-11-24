import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/github-file-size-checker.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.SizeCheck = SizeCheck;`, context);
const sc = context.SizeCheck;
const MIB = 1048576;

test('built-in self-test passes', () => {
  assert.deepEqual([...sc.selfTest()], []);
});

test('120 MiB, 60 MiB, 30 MiB and 1 KiB files give one flag per band', () => {
  const r = sc.analyze([
    { path: 'a.mp4', size: 120 * MIB },
    { path: 'b.pdf', size: 60 * MIB },
    { path: 'c.zip', size: 30 * MIB },
    { path: 'd.md', size: 1024 },
  ], { excludeObsidian: true });
  assert.equal(r.counts.blocked, 1);
  assert.equal(r.counts.warn, 1);
  assert.equal(r.counts.browser, 1);
  assert.equal(r.flagged.length, 3);
  assert.deepEqual([...r.lfsLines], [
    '*.mp4 filter=lfs diff=lfs merge=lfs -text',
    '*.pdf filter=lfs diff=lfs merge=lfs -text',
    '*.zip filter=lfs diff=lfs merge=lfs -text',
  ]);
});

test('.git is always skipped; .obsidian and .trash only with the checkbox', () => {
  const files = [
    { path: '.git/x.pack', size: 200 * MIB },
    { path: '.obsidian/a.bin', size: 200 * MIB },
    { path: 'sub/.trash/b.bin', size: 200 * MIB },
  ];
  assert.equal(sc.analyze(files, { excludeObsidian: true }).count, 0);
  assert.equal(sc.analyze(files, { excludeObsidian: false }).count, 2);
});

test('paths with spaces are quoted and extensionless files use exact paths', () => {
  const r = sc.analyze([{ path: 'My Videos/clip', size: 101 * MIB }], {});
  assert.equal(r.lfsLines[0], '"/My Videos/clip" filter=lfs diff=lfs merge=lfs -text');
  assert.equal(r.ignoreLines[0], '/My Videos/clip');
});

test('page makes no network calls and declares the canonical URL', () => {
  assert.ok(!/\b(fetch|XMLHttpRequest|sendBeacon|WebSocket)\b/.test(script.replace(/\/\*[\s\S]*?\*\//, '')));
  assert.ok(html.includes('<link rel="canonical" href="https://gitsync.md/tools/github-file-size-checker">'));
});
