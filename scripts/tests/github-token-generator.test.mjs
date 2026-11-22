import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/github-token-generator.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({ URLSearchParams });
vm.runInContext(`${script};this.G = GithubTokenLink;`, context);
const G = context.G;

test('built-in self-test passes', () => {
  assert.deepEqual([...G.selfTest()], []);
});

test('preset 1 defaults produce the documented URL shape', () => {
  const r = G.build({ name: 'Obsidian iPhone', description: G.defaultDescription('obsidian'), expiresIn: '90', permissions: G.PRESETS.obsidian.perms });
  assert.deepEqual([...r.errors], []);
  const u = new URL(r.url);
  assert.equal(u.origin + u.pathname, 'https://github.com/settings/personal-access-tokens/new');
  assert.deepEqual([...u.searchParams.keys()], ['name', 'description', 'expires_in', 'contents', 'statuses', 'metadata']);
  assert.equal(u.searchParams.get('contents'), 'write');
});

test('limits are enforced', () => {
  const p = G.PRESETS.readonly.perms;
  assert.ok(G.build({ name: 'x'.repeat(41), expiresIn: '90', permissions: p }).errors.length);
  assert.ok(!G.build({ name: 'x'.repeat(40), expiresIn: '366', permissions: p }).errors.length);
  assert.ok(G.build({ name: 'x', expiresIn: '0', permissions: p }).errors.length);
  assert.ok(G.build({ name: 'x', expiresIn: '367', permissions: p }).errors.length);
  assert.ok(G.build({ name: 'x', expiresIn: '5', permissions: { workflows: 'read' } }).errors.length);
});

test('page is client-side only', () => {
  const body = script;
  assert.ok(!/fetch\(|XMLHttpRequest|localStorage|sessionStorage|scope=/.test(body));
  assert.ok(!/<script[^>]+src=/.test(html));
});
