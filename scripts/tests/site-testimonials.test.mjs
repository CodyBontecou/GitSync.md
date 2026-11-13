import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const html = await readFile(new URL('site/index.html', root), 'utf8');
const css = await readFile(new URL('site/reviews.css', root), 'utf8');
const script = await readFile(new URL('site/reviews.js', root), 'utf8');
const fixture = JSON.parse(await readFile(new URL('fixtures/app-store-reviews.json', import.meta.url), 'utf8'));
const section = html.match(/<section class="reviews-section[\s\S]*?<\/section>/)?.[0] ?? '';
const cards = [...section.matchAll(/<figure class="review-card" data-review-id="([^"]+)">([\s\S]*?)<\/figure>/g)];
const normalize = value => value.replace(/\s+/g, ' ').trim();

test('six independent public reviews keep their exact wording, attribution, dates and ratings', () => {
  assert.equal(cards.length, 6);
  assert.equal(new Set(cards.map(([, id]) => id)).size, 6);
  for (const [ , id, card] of cards) {
    const source = fixture.reviews.find(review => review.id === id);
    assert.ok(source, id);
    const quote = card.match(/<blockquote><p>“([\s\S]*?)”<\/p><\/blockquote>/)?.[1];
    assert.ok(quote && normalize(source.body).includes(normalize(quote)), id);
    assert.ok(card.includes(`<span class="review-author">${source.author}</span>`));
    assert.ok(card.includes(`datetime="${source.date}"`));
    assert.equal(source.rating, 5);
    assert.ok(card.includes('role="img" aria-label="5 out of 5 stars"'));
  }
  assert.ok(section.includes('wording is unchanged'));
  assert.ok(section.includes(`id${fixture.appId}?see-all=reviews`));
  assert.ok(html.indexOf(section) < html.indexOf('<section class="download-section'));
  assert.doesNotMatch(section, /aggregateRating|<img|@/);
});

test('wall has static fallbacks, hidden inert copies, opposite motion and pause controls', () => {
  assert.equal((section.match(/class="reviews-group"/g) ?? []).length, 2);
  assert.ok(html.includes('href="reviews.css"'));
  assert.ok(html.includes('src="reviews.js" defer'));
  assert.ok(section.includes('aria-pressed="false" hidden>Pause scrolling'));
  assert.ok(script.includes("copy.setAttribute('aria-hidden', 'true')"));
  assert.ok(script.includes("copy.setAttribute('inert', '')"));
  assert.ok(script.includes("button.setAttribute('aria-pressed', String(paused))"));
  assert.ok(script.includes('IntersectionObserver'));
  assert.ok(css.includes('animation-direction: reverse'));
  assert.ok(css.includes('.reviews-wall:hover .reviews-track'));
  assert.ok(css.includes('.reviews-section:focus-within .reviews-track'));
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)[\s\S]*animation: none/);
  assert.match(css, /@media \(max-width: 800px\)[\s\S]*grid-template-columns: 1fr/);
});
