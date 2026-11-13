Lighthouse audit — GitSync.md — October 26, 2026

Published to https://gitsync.md/
Final Pages artifact: https://9c04b0f6.syncmd.pages.dev
Source snapshot: /tmp/gitsync-lighthouse-inline
Rollback target before this task: e64c2761-8f0b-44c8-b168-aa2505bceb14

Lighthouse 13.5.0, standard simulated throttling, isolated headless Chrome.
No blocked requests, skipped audits, or custom scoring thresholds.
All 19 indexable production URLs: mobile Performance, Accessibility,
Best Practices, and SEO each scored 100. See mobile-summary.json.
The non-indexed 404 page is excluded from scored SEO claims.

Homepage before (mobile): 89 / 96 / 100 / 100.
Homepage after (mobile): 100 / 100 / 100 / 100.
Homepage after (desktop): 100 / 100 / 100 / 100.
FCP: 2.0 s -> 1.0 s; LCP: 3.4 s -> 1.0 s; CLS: 0 -> 0.
Before and after homepage HTML and JSON reports are included.

Changes: optimized screenshots/poster as WebP; fixed contrast; click-to-play
YouTube with keyboard and no-JS fallbacks; generated inline stylesheets.
Use scripts/build_site.py to build future deployment snapshots. See docs/seo.md.
Existing app, router and unrelated source changes were preserved. No commit made.

Verification: source and generated-site SEO/link checks; exact live content
comparison for all 20 HTML pages; 8 SEO fixture tests; 2 testimonial tests;
build guard smoke checks; phone/desktop layout and screenshot-tab checks;
keyboard video activation and no-JS fallback; no initial YouTube requests.

Reproduce homepage audits:
npx --yes lighthouse@13.5.0 https://gitsync.md --chrome-flags="--headless --no-sandbox" --output=html --output=json --output-path=/tmp/gitsync-mobile
npx --yes lighthouse@13.5.0 https://gitsync.md --preset=desktop --chrome-flags="--headless --no-sandbox" --output=html --output=json --output-path=/tmp/gitsync-desktop

Scores describe the recorded lab runs; future runs and field data can vary.
