import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// Loads the tool's own engine (the first plain <script>) with synthetic notes only.
const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/tools/obsidian-frontmatter-checker.html', root), 'utf8');
const script = html.match(/<script>\n([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({});
vm.runInContext(`${script};this.FmCheck = FmCheck;`, context);
const fm = context.FmCheck;
const msgs = (r, sev) => r.problems.filter((p) => !sev || p.sev === sev).map((p) => p.msg);
const has = (r, re, sev) => msgs(r, sev).some((m) => re.test(m));

test('page has no external scripts, network calls or storage', () => {
  assert.doesNotMatch(html, /<script[^>]*\bsrc=/i);
  assert.doesNotMatch(html, /\b(fetch|XMLHttpRequest|WebSocket|sendBeacon|localStorage|sessionStorage|indexedDB)\b/);
});

test('valid examples from the Properties help show no errors or warnings', () => {
  const r = fm.check(`---
title: "My note"
year: 1994
favorite: true
date: 2020-08-21
time: 2020-08-21T10:30:00
link: "[[Link]]"
cast:
  - Mark Hamill
  - "[[Carrie Fisher]]"
tags:
  - journal
  - project/alpha
  - y1984
cssclasses:
  - wide
---
Body`);
  assert.equal(r.problems.length, 0);
  const types = Object.fromEntries(r.props.map((p) => [p.name, p.type]));
  assert.deepEqual({ ...types }, { title: 'Text', year: 'Number', favorite: 'Checkbox', date: 'Date', time: 'Date & time', link: 'Text', cast: 'List', tags: 'Tags', cssclasses: 'List' });
  assert.equal(r.changed, false);
});

test('JSON frontmatter is accepted and shown as YAML', () => {
  const r = fm.check('---\n{\n  "tags": ["journal"],\n  "favorite": true,\n  "date": "2020-08-21"\n}\n---');
  assert.equal(r.json, true);
  assert.equal(msgs(r, 'error').length, 0);
  assert.equal(r.corrected, '---\ntags:\n  - journal\nfavorite: true\ndate: 2020-08-21\n---');
  assert.ok(fm.check('---\n{ "a": \n---').problems.some((p) => p.sev === 'error'));
});

test('the example note triggers every group and the safe fixes apply', () => {
  const r = fm.check(fm.SAMPLE);
  assert.ok(has(r, /needs a space after the colon/, 'error'));
  assert.ok(has(r, /already appears on line 8/, 'error'));
  assert.ok(has(r, /internal link must be in quotes/, 'error'));
  assert.ok(has(r, /“tag” is deprecated/, 'warning'));
  assert.ok(has(r, /has a space/, 'error'));
  assert.ok(has(r, /only numbers/, 'error'));
  assert.ok(has(r, /21\/08\/2020/, 'warning'));
  assert.ok(has(r, /“yes” is Text, not a checkbox/, 'warning'));
  assert.ok(has(r, /tab/, 'error'));
  assert.equal(r.corrected, [
    '---', 'title: Weekly review', 'date: 2020-08-21', 'reviewed: 2020-08-21T10:30:00', 'published: yes',
    'tags:', '  - project/alpha', '  - my notes', '  - 1984', 'related: "[[Meeting notes]]"',
    'status: draft', 'status: done', 'aliases:', '  - Review', 'cssclasses:', '  - wide', '---'].join('\n'));
});

test('block position and closing', () => {
  assert.ok(has(fm.check('Intro\n\n---\ntitle: x\n---\n'), /starts on line 3/, 'error'));
  const open = fm.check('---\ntitle: x\n\nBody text here');
  assert.ok(has(open, /never closes/, 'error'));
  assert.equal(open.corrected, '---\ntitle: x\n---');
  assert.equal(fm.check('Just a note.\nNo properties.').found, false);
});

test('tag rules, case and the # comment trap', () => {
  const r = fm.check('---\ntags:\n  - #plan\n  - Plan\n  - bad$tag\n  - 1984\n  - a/b\n---');
  assert.ok(has(r, /unquoted # starts a comment/, 'warning'));
  assert.ok(has(r, /case-insensitive/, 'warning'));
  assert.ok(has(r, /character tags can’t contain/, 'error'));
  assert.ok(has(r, /only numbers/, 'error'));
  assert.equal(msgs(r).filter((m) => /“a\/b”/.test(m)).length, 0);
  assert.match(r.corrected, /  - plan\n/);
});

test('list properties on one line become lists; look-alike dates only fix when unambiguous', () => {
  assert.equal(fm.check('---\ntags: a, b\n---').corrected, '---\ntags:\n  - a\n  - b\n---');
  const amb = fm.check('---\nd: 03/04/2020\n---');
  assert.ok(has(amb, /03\/04\/2020/, 'warning'));
  assert.equal(amb.corrected, '---\nd: 03/04/2020\n---');
  assert.match(fm.check('---\nd: 2020/8/5\n---').corrected, /d: 2020-08-05/);
  assert.ok(has(fm.check('---\nd: 2020-02-31\n---'), /isn’t a real calendar date/, 'warning'));
});

test('nested properties warn, Publish names are information, colons need quotes', () => {
  const r = fm.check('---\nmeta:\n  a: 1\npublish: true\ntitle: Note: a thing\n---');
  assert.ok(has(r, /nested properties/, 'warning'));
  assert.ok(has(r, /Obsidian Publish/, 'info'));
  assert.match(r.corrected, /title: "Note: a thing"/);
  assert.equal(r.props.find((p) => p.name === 'meta').type, 'Nested');
});

test('properties without fences are accepted and fenced in the corrected block', () => {
  const r = fm.check('title: x\nstatus: done');
  assert.ok(has(r, /start on line 1/, 'error'));
  assert.equal(r.corrected, '---\ntitle: x\nstatus: done\n---');
});
