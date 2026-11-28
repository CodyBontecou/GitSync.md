import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// Loads the tool's own script (the first plain <script>) with synthetic notes only.
const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/markdown-preview.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.MdPreview = MdPreview;`, context);
const md = context.MdPreview;
const gh = (s, o = {}) => md.render(s, { obsidian: false, preview: true, prefix: 'gh-', ...o });
const ob = (s, o = {}) => md.render(s, { obsidian: true, preview: true, prefix: 'ob-', ...o });

test('page has no external scripts or network calls', () => {
  assert.doesNotMatch(html, /<script[^>]*\bsrc=/i);
  assert.doesNotMatch(script, /\b(fetch|XMLHttpRequest|WebSocket|sendBeacon)\b/);
});

test('wikilinks are literal on GitHub and links in Obsidian', () => {
  assert.match(gh('See [[Meeting notes]]'), /\[\[Meeting notes\]\]/);
  assert.doesNotMatch(gh('See [[Meeting notes]]'), /<a /);
  assert.match(ob('See [[Meeting notes]]'), /<a class="wikilink"[^>]*>Meeting notes<\/a>/);
  assert.match(ob('[[Note|alias]] [[Note#Head]]'), />alias<\/a>.*>Note &gt; Head<\/a>/);
});

test('embeds are literal on GitHub and a placeholder in Obsidian', () => {
  assert.match(gh('![[a.png]]'), /!\[\[a\.png\]\]/);
  assert.match(ob('![[a.png]]'), /embedded:<\/span> a\.png/);
});

test('NOTE alert is styled in both, abstract only in Obsidian', () => {
  const note = '> [!NOTE]\n> hi';
  assert.match(gh(note), /markdown-alert-note/);
  assert.match(ob(note), /callout-note/);
  const abs = '> [!abstract]\n> hi';
  assert.doesNotMatch(gh(abs), /markdown-alert|callout/);
  assert.match(gh(abs), /<blockquote>[\s\S]*\[!abstract\]/);
  assert.match(ob(abs), /callout-abstract/);
});

test('Obsidian callout fold markers and custom titles', () => {
  const r = ob('> [!faq]- Are they foldable?\n> yes');
  assert.match(r, /<details class="callout callout-question"><summary[^>]*>Are they foldable\?/);
  assert.match(ob('> [!tip]+ Open\n> x'), /<details[^>]* open>/);
  assert.match(gh('> [!NOTE]- Title\n> x'), /<blockquote>/);
});

test('highlights, comments and tags', () => {
  assert.match(ob('a ==b== c'), /<mark>b<\/mark>/);
  assert.match(gh('a ==b== c'), /==b==/);
  assert.doesNotMatch(ob('a %%secret%% b'), /secret/);
  assert.match(gh('a %%secret%% b'), /%%secret%%/);
  assert.match(ob('x #plan and #1984'), /<span class="tag">#plan<\/span> and #1984/);
  assert.doesNotMatch(gh('x #plan'), /class="tag"/);
  assert.doesNotMatch(ob('a\n\n%%\nhidden\nblock\n%%\n\nb'), /hidden/);
});

test('front matter: table on GitHub, Properties in Obsidian', () => {
  const note = '---\ntitle: T\ntags:\n  - a\n---\n# H';
  assert.match(gh(note), /<table><thead><tr><th>title<\/th><th>tags<\/th>/);
  assert.match(ob(note), /class="properties"[\s\S]*<span class="tag">#a<\/span>/);
});

test('common Markdown: tables, tasks, nesting, code, footnotes, strikethrough', () => {
  const t = gh('| a | b | c |\n| :-- | :-: | --: |\n| 1 | 2 | 3 |');
  assert.match(t, /<th align="left">a<\/th><th align="center">b<\/th><th align="right">c<\/th>/);
  const l = gh('- [x] done\n- [ ] todo\n  - nested\n\n1. one\n2. two');
  assert.match(l, /<input type="checkbox" disabled checked> done/);
  assert.match(l, /todo<ul><li>nested<\/li><\/ul>/);
  assert.match(l, /<ol><li>one<\/li><li>two<\/li><\/ol>/);
  assert.match(gh('```js\nlet a = 1 < 2;\n```'), /<code class="language-js">let a = 1 &lt; 2;<\/code>/);
  assert.match(gh('x[^1]\n\n[^1]: note'), /<sup class="fnref">[\s\S]*<section class="footnotes">[\s\S]*note/);
  assert.match(gh('~~gone~~ **b** *i* `c`'), /<del>gone<\/del> <strong>b<\/strong> <em>i<\/em> <code>c<\/code>/);
  assert.match(gh('> quote'), /<blockquote>/);
  assert.match(gh('---\n\ntext\n\n***'), /<hr>[\s\S]*<hr>/);
  assert.match(gh('https://example.com/a_b_c.'), /<a href="https:\/\/example\.com\/a_b_c"[^>]*>https:\/\/example\.com\/a_b_c<\/a>\./);
});

test('user HTML is escaped and unsafe links are dropped', () => {
  const xss = '<img src=x onerror=alert(1)>';
  for (const out of [gh(xss), ob(xss), gh('a ' + xss), ob('# ' + xss), md.exportFragment(xss, false), md.exportDocument('# ' + xss, true)]) {
    assert.doesNotMatch(out, /<img src=x/);
  }
  assert.match(gh('a ' + xss), /&lt;img src=x onerror=alert\(1\)&gt;/);
  assert.doesNotMatch(gh('[x](javascript:alert(1))'), /href="javascript/i);
  assert.doesNotMatch(md.exportFragment('![x](javascript:alert(1))', false), /<img/);
  assert.match(gh('<script>alert(1)</script>'), /&lt;script&gt;/);
  assert.doesNotMatch(gh('"><b>'), /<b>/);
});

test('images are placeholders in the preview and <img> in the export', () => {
  const src = '![logo](https://example.com/a.png)';
  assert.doesNotMatch(gh(src), /<img/);
  assert.match(gh(src), /imgph[\s\S]*logo[\s\S]*https:\/\/example\.com\/a\.png/);
  assert.match(md.exportFragment(src, false), /<img src="https:\/\/example\.com\/a\.png" alt="logo">/);
});

test('mermaid, math and HTML show as source with a note', () => {
  assert.match(gh('```mermaid\ngraph TD\n```'), /language-mermaid[\s\S]*GitHub renders this[\s\S]*this preview shows the source\./);
  assert.match(gh('$$E=mc^2$$'), /language-math">E=mc\^2/);
  assert.match(gh('<div>hi</div>'), /&lt;div&gt;hi&lt;\/div&gt;[\s\S]*shows the source/);
  assert.doesNotMatch(md.exportFragment('$$x$$', false), /renders this/);
});

test('"what changes" lists the sample note rows with line numbers', () => {
  const rows = md.findings(md.SAMPLE);
  assert.ok(rows.length >= 5);
  const kinds = rows.map(r => r.kind);
  for (const k of ['front matter', 'wikilink', 'embed', 'callout', 'highlight', 'comment', 'tag']) assert.ok(kinds.includes(k), k);
  const wl = rows.find(r => r.kind === 'wikilink');
  assert.equal(md.SAMPLE.split('\n')[wl.line - 1].includes(wl.found), true);
  assert.match(wl.github, /literal text \[\[Meeting notes\]\], not a link/);
});

test('"what changes" ignores code and plain GitHub alerts', () => {
  assert.equal(md.findings('> [!NOTE]\n> x\n\n`[[a]]` inline\n\n```\n[[b]]\n```\n').length, 0);
  assert.equal(md.findings('```\n[[b]] ==x== #t\n```\n').length, 0);
  assert.equal(md.findings('> [!TIP]- t\n> x').length, 1);
});

test('export document is a standalone page with an optional style block', () => {
  const plain = md.exportFragment('# Hi', false);
  assert.doesNotMatch(plain, /<style>/);
  assert.match(md.exportFragment('# Hi', true), /^<style>/);
  const doc = md.exportDocument('# A <b>', false);
  assert.match(doc, /^<!doctype html>[\s\S]*<title>A &lt;b&gt;<\/title>[\s\S]*<h1>A &lt;b&gt;<\/h1>/);
});
