import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// Loads the tool's own script (the first plain <script>) and runs its sanity cases.
const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/markdown-diff.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.MdDiff = MdDiff;`, context);
const md = context.MdDiff;

test('built-in self-test passes', () => {
  assert.deepEqual([...md.selfTest()], []);
});

test('example highlights only the changed word in the paragraph', () => {
  const r = md.diff(md.EXAMPLE_A, md.EXAMPLE_B, {});
  assert.equal(md.summaryText(r), '0 lines added, 0 removed, 2 changed');
});

test('ignore wrapping shows no changes for the same paragraph at different widths', () => {
  const a = 'One two three four five six seven eight nine ten.\n';
  const b = 'One two three four\nfive six seven\neight nine ten.\n';
  assert.ok(md.diff(a, b, {}).changed > 0);
  const r = md.diff(a, b, { para: true });
  assert.equal(md.summaryText(r), 'No differences found.');
});

test('unbalanced markers name the line', () => {
  assert.match(md.parseConflicts('x\n<<<<<<< HEAD\na\n=======\nb\n').error, /^Line 2:/);
  assert.match(md.parseConflicts('x\n>>>>>>> b\n').error, /^Line 2:/);
});

test('page has no backticks or template placeholders in its script', () => {
  assert.ok(!script.includes('`'));
  assert.ok(!script.includes('${'));
});

test('page makes no network calls', () => {
  assert.ok(!/fetch\(|XMLHttpRequest|localStorage|sendBeacon|WebSocket/.test(script));
});
