# SEO website release — October 26, 2026

## Published artifacts

- Production site: https://gitsync.md/
- Guide library: https://gitsync.md/blog/
- Pages project: `syncmd`
- Production deployment: `e64c2761-8f0b-44c8-b168-aa2505bceb14`
- Production artifact: https://e64c2761.syncmd.pages.dev
- Preview deployment: `a957a1d9-45df-4d18-aa02-4049cd9fa631`
- Validated preview: https://a957a1d9.syncmd.pages.dev
- Previous production / rollback target: `cd8b854a-a016-4270-9c1d-f1fb0703dcbd`
- Previous artifact: https://cd8b854a.syncmd.pages.dev

The same static snapshot was uploaded to preview and production. It contained 70 files after excluding `.DS_Store`, `.gitignore`, and `.vercel`. Production branch metadata is `main`; preview branch metadata is `codex/seo-release`. This was a direct static upload from a dirty workspace, not a new Git commit. The site router and app binaries were not redeployed.

For rollback, use the existing Pages project's rollback action on the previous production deployment above. Preserve the site's domain/router configuration.

## Content and technical changes

The initial expansion adds eight topics: Obsidian Git troubleshooting, Windows/iPhone syncing, Working Copy comparison, Obsidian Sync comparison, conflict recovery, background-sync behavior, GitHub cloning, and Obsidian ignore policies. Existing setup pages now distinguish the general workflow, Connect authentication, and manual GitHub token setup.

The release also aligns extensionless canonical URLs, internal links, social URLs, and sitemap entries; improves homepage and guide-library metadata; adds page-specific campaign redirects; and adds a branded, non-indexed `404.html` so unknown paths do not fall back to the homepage.

## Verification

- Local SEO check: 20 HTML pages, 19 indexable sitemap URLs. Metadata, JSON-LD, local references, anchors, CSS assets, unique titles, and navigation reachability passed.
- Preview: all indexable HTML, sitemap, robots, and unknown-path HTTP 404 behavior passed live checks.
- Production: the same live checks passed against `https://gitsync.md`.
- Cloudflare injects a managed analytics beacon into some production HTML responses. The verifier removes only that recognized external script before comparison; unrelated scripts and changed page content still fail. A fixture covers this allowance.
- Eight SEO-checker fixture tests passed; two existing testimonial tests passed.
- Seventeen campaign routes were verified with curl HEAD requests: HTTP 302 to the expected App Store app with page-specific `ct`, provider `pt`, and `mt=8`, without a custom product page. HEAD requests did not record campaign click rows or open the App Store.
- The authored Git examples were checked earlier in disposable local repositories: publication and two-way edits, fast-forward refusal on divergence, an example merge conflict and combined resolution, both ignore policies, and tracked-file behavior. These checks do not claim Windows or native-device QA.
- New pages were visually inspected at phone width, including horizontal comparison-table scrolling and code-block containment.

Run instructions and the ongoing review process are in [seo.md](seo.md). The repository includes `scripts/check_site_seo.py`, its fixtures, and `.github/workflows/site.yml`; the workflow becomes active after the source changes are pushed to GitHub.

## Search Console setup

On October 26, 2026, the user approved verifying the domain property for `cody@isolated.tech` and submitting the sitemap. Ownership verification succeeded using a Google verification TXT record at the domain apex in Cloudflare DNS. Keep this record to retain verification. Existing DNS records were preserved; no Google-to-Cloudflare account integration was authorized.

Property: [sc-domain:gitsync.md](https://search.google.com/search-console?resource_id=sc-domain%3Agitsync.md).

Submitted `https://gitsync.md/sitemap.xml`. The sitemap detail report confirmed **Sitemap processed successfully**, last read October 26, 2026, with **19 discovered pages** and zero discovered videos. The initial list briefly showed “Couldn't fetch” before the detail report confirmed successful processing; no resubmission was needed.

The overview reported “Processing data, please check again in a day or so” for performance, indexing, experience, and enhancements. Discovered pages are not proof of indexing. No organic traffic, impressions, rankings, or indexed-page count is available as a baseline yet.

The same-day [URL inspection follow-up](seo-search-baseline-20261026.md) confirms the homepage is indexed with the correct canonical. The primary Obsidian guide's stored crawl still reflected its former `.html` canonical; a live Google test saw the corrected extensionless canonical and confirmed the page can be indexed. Google accepted its indexing request. Newly published priority pages were also inspected for discovery.

Next: confirm recrawling and establish the quantitative baseline when performance reporting is available before using search data to prioritize further content or localization. Follow the weekly review process in [seo.md](seo.md).
