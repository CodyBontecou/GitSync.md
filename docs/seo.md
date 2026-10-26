# Website SEO workflow

The static website lives in `site/`. Cloudflare Pages project `syncmd` serves it through the existing `gitsync.md` site router.

## Validate a change

Run from the repository root:

```bash
python3 scripts/check_site_seo.py
python3 -m unittest discover -s scripts/tests -p 'test_site_seo.py'
node --test scripts/tests/site-testimonials.test.mjs
```

The checker verifies canonical URLs, social metadata, article JSON-LD identities, unique titles and IDs, local links, anchors, image and CSS assets, sitemap coverage, robots declaration, and navigation reachability. The non-indexed `404.html` is deliberately excluded from the sitemap. `.github/workflows/site.yml` runs these checks for relevant pushes and pull requests; it does not publish the website.

Cloudflare serves `blog/topic.html` at `/blog/topic`, while `blog/index.html` uses `/blog/`. Use those public paths for links, canonical tags, social URLs, JSON-LD, and sitemap entries. Some articles have a same-named image directory beside their HTML file; the checker accounts for Pages routing in that case.

## Add a guide

1. Assign a distinct reader task. Improve an existing guide when the intent already has a useful page.
2. Answer the question immediately. Include platform requirements, current limitations, and a way to verify success.
3. Verify product and competitor claims against released behavior or primary documentation. Label documentation comparisons and illustrative examples accurately.
4. Add incoming contextual links from relevant pages and a guide card in `site/blog/index.html`. Update its `Blog` structured data.
5. Add the canonical URL to `site/sitemap.xml`. Use a truthful publication/modification date.
6. Reserve a unique page campaign token in `site-router/ATTRIBUTION.md`, using the existing website redirect route. Do not treat store clicks as installs.
7. Run the checks and inspect the page at desktop and phone widths. Tables and code blocks may scroll within their container; the page should not overflow.

## Verify a deployment

Record the current production deployment before release:

```bash
wrangler pages deployment list --project-name syncmd --json
```

Upload a validated static snapshot to a preview branch first. Check its returned deployment origin against that same snapshot:

```bash
python3 scripts/build_site.py /tmp/gitsync-release
python3 scripts/check_site_seo.py --site /tmp/gitsync-release
wrangler pages deploy /tmp/gitsync-release --project-name syncmd --branch codex/site-preview --commit-dirty=true
python3 scripts/check_site_seo.py --site /path/to/release-snapshot --live-base-url https://DEPLOYMENT.syncmd.pages.dev
```

Use a fresh output directory for each build. The build copies public assets and inlines each page's small local stylesheets to remove render-blocking network requests. Keep editing the shared CSS in `site/`; deploy the generated snapshot rather than the source directory. The build rejects CSS with relative asset URLs or imports until URL rebasing is supported. Tutorial players load YouTube only after a visitor activates Play; without JavaScript, the same control links directly to the video. The build also generates Markdown from each HTML page’s main content. The site router serves that representation when a client explicitly prefers `Accept: text/markdown`, keeps `Vary: Accept` on negotiated responses, and preserves HTTP 404 for missing URLs. Publish the generated Pages assets before a router release that depends on them. Public agent guidance lives in `site/llms.txt` and `site/agent-instructions.md`; keep product scope and platform limits aligned with the guides.

Live mode checks every indexable page's response and HTML content (allowing only Cloudflare's managed analytics-beacon injection), plus robots, sitemap, and an unknown URL that must return HTTP 404 with `noindex`. It does not click campaign links, prove indexing, or test app installation.

Publish the same snapshot to the project's production branch and repeat live verification against `https://gitsync.md`. Keep the prior Pages deployment ID as the rollback target. A static-site release does not require deploying the router, relay, or iOS app.

## Review search performance weekly

Use authorized Google Search Console access or an exported report. No Search Console credentials are stored in this repository.

The [domain property for gitsync.md](https://search.google.com/search-console?resource_id=sc-domain%3Agitsync.md) was verified for `cody@isolated.tech` on October 26, 2026 through an apex DNS TXT record. Retain the verification record. Google successfully processed `https://gitsync.md/sitemap.xml` and discovered 19 pages that day. Initial performance and indexing reports were still processing; establish the first measured baseline when those reports become available. See [the release record](seo-release-20261026.md) for setup details.

The [initial URL inspection record](seo-search-baseline-20261026.md) confirms the homepage is indexed and documents a successful live canonical check and accepted recrawl request for the primary Obsidian guide. Review stored crawl dates when diagnosing canonical issues; a pre-release crawl can still reflect the former `.html` URL.

- Record the date range, property, search type, country/device filters, and page URL. The October 8, 2026 domain migration means old-domain and new-domain history may need separate review.
- Compare the most recent complete 28 days with the preceding 28 days. Search Console reporting can lag; compare complete periods.
- Review non-brand queries and their landing pages. Look for relevant impressions, position, clicks, and a mismatch between the query and the answer.
- Improve a useful existing page first. Write a separate page when the reader's task needs a materially different answer.
- Follow search clicks with aggregate page views, page-specific campaign clicks, and available App Store campaign downloads/purchases. Keep these measurements separate; reporting windows and privacy thresholds differ.
- Recheck product facts, screenshots, source links, and pricing when updating a page. A new date alone is not an improvement.

The initial expansion adds eight topics: troubleshooting, Windows/iPhone syncing, Working Copy comparison, Obsidian Sync comparison, conflict recovery, background-sync behavior, cloning, and ignore policies. Let observed query and conversion data determine the next expansion and any localization.
