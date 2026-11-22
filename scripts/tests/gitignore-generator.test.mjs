import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// Runs the generator's built-in sanity cases (also available in the browser via #selftest).
const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/obsidian-gitignore-generator.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.GitignoreGen = GitignoreGen;`, context);
const gen = context.GitignoreGen;

test('built-in generator self-test passes', () => {
  assert.deepEqual([...gen.selfTest()], []);
});

test('default preset ignores workspace.json and keeps snippets', () => {
  const { text } = gen.generate({ config: '.obsidian', ...gen.presets.shared, trash: true, junk: true });
  const rules = text.split('\n').filter(l => l && !l.startsWith('#'));
  assert.ok(rules.includes('.obsidian/workspace.json'));
  assert.ok(!rules.some(r => r.includes('snippets')));
});
