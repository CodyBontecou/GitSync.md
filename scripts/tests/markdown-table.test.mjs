import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// Loads the tool's own script (the first plain <script>) and runs its sanity cases.
const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/markdown-table-generator.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.MdTable = MdTable;`, context);
const t = context.MdTable;
const all = { header: true, style: 'compact', escape: true, wiki: true, br: true, trim: true };

test('built-in self-test passes', () => {
  assert.deepEqual([...t.selfTest()], []);
});

test('wikilink alias pipe is escaped and stable on re-import', () => {
  const out = t.render(t.parseTable('| a | b |\n|---|:-:|\n| [[Note|Alias]] | x |'), all);
  assert.match(out, /\[\[Note\\\|Alias\]\]/);
  assert.equal(t.render(t.parseTable(out), all), out);
});

test('TSV and RFC 4180 CSV import', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(t.parseTable('x\ty\n1\t2').rows)), [['x', 'y'], ['1', '2']]);
  const csv = t.parseTable('a,b\n"a, b","say ""hi"""');
  assert.deepEqual(JSON.parse(JSON.stringify(csv.rows[1])), ['a, b', 'say "hi"']);
});

test('aligned and compact output reparse to the same grid', () => {
  const grid = t.parseTable('Task,Status\nBook the room,In progress\nx,Done');
  const a = t.render(grid, { ...all, style: 'aligned' });
  const c = t.render(grid, all);
  assert.deepEqual(JSON.parse(JSON.stringify(t.parseTable(a).rows)), JSON.parse(JSON.stringify(t.parseTable(c).rows)));
});

test('preview escapes cell text', () => {
  const h = t.previewHtml(t.parseTable('a\n<img src=x onerror=alert(1)>'), all);
  assert.ok(!h.includes('<img'));
});

test('page script has no backticks, template placeholders or network calls', () => {
  assert.ok(!script.includes('`'));
  assert.ok(!script.includes('${'));
  assert.ok(!/fetch\(|XMLHttpRequest|sendBeacon|https?:\/\//.test(script));
});
