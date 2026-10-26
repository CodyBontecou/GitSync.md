import assert from 'node:assert/strict';
import test from 'node:test';
import router, { prefersMarkdown } from '../src/index.ts';

test('honors explicit Markdown preferences, quality values, and browser wildcards', () => {
  for (const accept of ['text/markdown', 'TEXT/MARKDOWN; charset=utf-8', 'text/html;q=0.5,text/markdown;q=1']) assert.equal(prefersMarkdown(accept), true, accept);
  for (const accept of ['', '*/*', 'text/html,*/*;q=.8', 'text/markdown;q=0', 'text/markdown;q=.4,text/html;q=.8', 'text/markdown;q=.5,*/*;q=1']) assert.equal(prefersMarkdown(accept), false, accept);
});

function upstream(t, handler) {
  const original = globalThis.fetch;
  globalThis.fetch = handler;
  t.after(() => { globalThis.fetch = original; });
}

test('streams browser HTML unchanged and preserves existing Vary values', async t => {
  let requests = 0;
  upstream(t, async request => {
    requests++;
    assert.equal(new URL(request.url).hostname, 'syncmd.pages.dev');
    return new Response('<h1>GitSync</h1>', { headers: { 'content-type': 'text/html', vary: 'Accept-Encoding' } });
  });
  const response = await router.fetch(new Request('https://gitsync.md/', { headers: { accept: 'text/html,*/*;q=.8' } }), {}, {});
  assert.equal(await response.text(), '<h1>GitSync</h1>');
  assert.equal(response.headers.get('vary'), 'Accept-Encoding, Accept');
  assert.equal(requests, 1);
});

test('serves generated Markdown at the canonical URL without forwarding HTML validators', async t => {
  const urls = [];
  upstream(t, async request => {
    urls.push(new URL(request.url).pathname);
    assert.equal(request.headers.has('if-none-match'), false);
    return urls.length === 1 ? new Response('<h1>Guide</h1>', { headers: { 'content-type': 'text/html' } }) : new Response('# Guide\n', { headers: { etag: 'markdown-version' } });
  });
  const response = await router.fetch(new Request('https://gitsync.md/blog/guide', { headers: { accept: 'text/markdown', 'if-none-match': 'old-version' } }), {}, {});
  assert.equal(await response.text(), '# Guide\n');
  assert.deepEqual(urls, ['/blog/guide', '/blog/guide.md']);
  assert.equal(response.headers.get('content-type'), 'text/markdown; charset=utf-8');
  assert.equal(response.headers.get('vary'), 'Accept');
  assert.equal(response.headers.get('etag'), 'markdown-version');
});

test('keeps missing URLs at 404 with Markdown recovery guidance', async t => {
  upstream(t, async request => new URL(request.url).pathname === '/404.md' ? new Response('# Not found\nSee [guides](https://gitsync.md/blog/).') : new Response('Missing', { status: 404, headers: { 'content-type': 'text/html' } }));
  const response = await router.fetch(new Request('https://gitsync.md/missing', { headers: { accept: 'text/markdown' } }), {}, {});
  assert.equal(response.status, 404);
  assert.match(await response.text(), /guides/);
  assert.equal(response.headers.get('x-robots-tag'), 'noindex');
});

test('HEAD uses the same representation headers with no response body', async t => {
  const urls = [];
  upstream(t, async request => {
    assert.equal(request.method, 'HEAD');
    urls.push(new URL(request.url).pathname);
    return new Response(null, { headers: { 'content-type': 'text/html' } });
  });
  const response = await router.fetch(new Request('https://gitsync.md/blog/', { method: 'HEAD', headers: { accept: 'text/markdown' } }), {}, {});
  assert.deepEqual(urls, ['/blog/', '/blog/index.md']);
  assert.equal(response.headers.get('content-type'), 'text/markdown; charset=utf-8');
  assert.equal(await response.text(), '');
});

test('missing generated assets fall back to the intact HTML response', async t => {
  upstream(t, async request => new URL(request.url).pathname.endsWith('.md') ? new Response('Missing', { status: 404 }) : new Response('Real HTML', { headers: { 'content-type': 'text/html' } }));
  const response = await router.fetch(new Request('https://gitsync.md/', { headers: { accept: 'text/markdown' } }), {}, {});
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'Real HTML');
  assert.equal(response.headers.get('content-type'), 'text/html');
});

test('assets, legacy redirects, and campaign HEAD links retain their behavior', async t => {
  let requests = 0;
  upstream(t, async () => { requests++; return new Response('image', { headers: { 'content-type': 'image/png' } }); });
  const asset = await router.fetch(new Request('https://gitsync.md/icon.png', { headers: { accept: 'text/markdown' } }), {}, {});
  assert.equal(asset.headers.get('content-type'), 'image/png');
  assert.equal(requests, 1);
  const redirect = await router.fetch(new Request('https://www.gitsync.md/blog/?q=one'), {}, {});
  assert.equal(redirect.status, 301);
  assert.equal(redirect.headers.get('location'), 'https://gitsync.md/blog/?q=one');
  const campaign = await router.fetch(new Request('https://gitsync.md/v/web-homepage-100', { method: 'HEAD' }), {}, {});
  assert.equal(campaign.status, 302);
  assert.equal(new URL(campaign.headers.get('location')).searchParams.get('ct'), 'web_homepage_100');
});
