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

## Navigation correction and migration audit

A later same-day review checked the newer production snapshot, which contains 22 HTML pages and 21 sitemap URLs, including About and Contact. The source build matched that production snapshot before this correction.

Removed accidentally inserted About/Contact links from privacy-policy sentences on the homepage, About, Contact, and background-sync guide. Added those links to the four pages' footers, including the current-page indicator on About and Contact. Regenerated their Markdown representations. The release changes only those four HTML files and four generated Markdown files; the sitemap and router are unchanged.

- Preview: `523b65d3-9ca3-40a9-9f89-3da5b6cd3ef0`, https://523b65d3.syncmd.pages.dev
- Production: `7d3abb77-3cb8-414a-9585-25dd71f9c7d8`, https://7d3abb77.syncmd.pages.dev
- Prior production / rollback: `8f605676-5bdd-4029-a2e1-597d86577882`, https://8f605676.syncmd.pages.dev
- Source and generated-site SEO checks passed; 12 Python site tests passed. Preview and production response/content checks passed for all indexable pages, sitemap, robots, and the missing-page 404.
- Footer layout was visually checked at desktop and 390px phone width.
- The first production check encountered HTTP 409 on the unchanged Working Copy comparison page. A direct recheck returned HTTP 200 with HTML matching the release snapshot; production verification was then repeated.
- All 26 live redirect checks passed: 21 `.html` aliases redirect permanently to their canonical paths, and five alternate/legacy domains redirect permanently to `gitsync.md`, preserving the tested path and query string. [Detailed HEAD-request evidence](evidence/seo-20261026/redirects.json).

The earlier Search Console discovery count of 19 is a historical observation, not a claim about this later 21-URL sitemap. Google has not been rechecked for the additional pages in this correction.

## Article navigation and author identity

Added visible Home → Guides → current page breadcrumbs to all 15 guides and comparisons. Standardized each page to one matching `BreadcrumbList`, including the seven pages that already had schema but no visible trail. Added linked author bylines to the About page and the same URL to each article schema's author. Existing publication and modification dates are unchanged. The SEO checker now validates the author link, breadcrumb presence, list positions, and canonical hierarchy, with regression coverage for old `.html` URLs and missing visible navigation.

- Preview: `95f1a67f-b0dd-49eb-81c8-8ed9e0dcfbb9`, https://95f1a67f.syncmd.pages.dev
- Production: `680c801f-5ab2-4574-b282-1f54b7d932cb`, https://680c801f.syncmd.pages.dev
- Prior production / rollback: `7d3abb77-3cb8-414a-9585-25dd71f9c7d8`, https://7d3abb77.syncmd.pages.dev
- Source/build SEO checks and all 14 Python site tests passed. Preview and production content checks cover 22 HTML pages, 21 sitemap URLs, robots, and the missing-page 404. The shared CSS was rebuilt into page HTML; generated article Markdown was refreshed.
- The article header was visually checked at desktop and 390px phone width.
- Google's [production Rich Results Test](https://search.google.com/test/rich-results/result?id=mIx-VSyw1U_D_jy-bTh0gQ) successfully crawled the troubleshooting guide and detected two valid items: one Article and one BreadcrumbList. The preview test fetched the page but correctly reported its platform-managed `X-Robots-Tag: noindex` header.
- The Article item retains four optional warnings: `datePublished` and `dateModified` use recorded calendar dates, without exact times/timezones. No publication timestamp was invented to remove those warnings. Valid structured data does not guarantee indexing, ranking improvements, or rich-result display.

## Page-specific sharing previews

Replaced tall screenshot/square-icon sharing metadata with a distinct 1200×630 PNG card for each of the 22 HTML pages. Each card contains the existing app icon, page title, category, and website identity. Added matching Open Graph/Twitter image URLs and image descriptions, plus Open Graph dimensions and PNG type. Article content images and Article schema images remain unchanged.

The authoring generator is `scripts/build_social_cards.py`; it uses Pillow and the bundled, OFL-licensed Manrope font. Generated images are in `site/social/` and total under 1 MB. Normal site builds, checks, and page visits do not load the generator or font. The SEO checker now verifies actual PNG dimensions against metadata, image descriptions, matching preview URLs, and live image bytes. CI path filters cover the generator and font assets.

- Preview: `37475d9e-b105-4367-9c1f-789da875d6ef`, https://37475d9e.syncmd.pages.dev
- Production: `9ca816a1-5526-403b-b728-19d116290078`, https://9ca816a1.syncmd.pages.dev
- Prior production / rollback: `680c801f-5ab2-4574-b282-1f54b7d932cb`, https://680c801f.syncmd.pages.dev
- Source and build checks passed; all 16 Python site tests passed. Preview and production checks passed for the pages and all 22 image responses. Representative homepage, comparison, long-title, About, and Shortcuts cards were visually inspected. The generator avoids truncating titles and moves an orphaned last word onto a line with its preceding word.

The metadata follows the [Open Graph image properties](https://ogp.me/#structured). This release improves shared-link presentation; no ranking or click-through uplift is claimed. Existing social-platform caches may continue displaying an older image until refreshed.

## iCloud comparison content expansion

Published [iCloud vs Git for Obsidian](https://gitsync.md/compare/icloud-vs-git-obsidian) to answer a distinct method-selection question that the Windows guide previously addressed only in a short FAQ. This is an editorial gap assessment, not a measured keyword-volume or ranking claim. The comparison covers Apple and Windows workflows, folder locations, commit history, backups, privacy settings, cost considerations, and a migration verification checklist. It identifies when iCloud is sufficient and points readers to Obsidian Sync when they want integrated syncing. Migration steps are described as an approach, not a tested one-click import.

Primary sources checked on October 26: [Obsidian sync guidance](https://obsidian.md/help/sync-notes), [Obsidian backup guidance](https://obsidian.md/help/backup), [Apple iCloud security](https://support.apple.com/en-us/102651), and [Git version control documentation](https://git-scm.com/book/en/v2/Getting-Started-About-Version-Control). The page discloses that it is published by GitSync.md’s developer and makes no measured performance or reliability claim.

Added contextual incoming links from the main Obsidian guide, Windows setup guide, and Obsidian Sync comparison, plus a card and Blog entry in the guide library. Added the canonical sitemap entry, generated Markdown and sharing card, and public guide-discovery link. Reserved campaign `web_homepage_117`; a HEAD request confirmed HTTP 302 to the correct App Store app with `ct=web_homepage_117`, `pt`, and `mt=8`, without generating a campaign click.

- Preview: `9af1771e-7f4d-4802-bcc0-d8d8919be574`, https://9af1771e.syncmd.pages.dev
- Production: `b3b9e943-0be6-4bf5-8cf6-d65d2720cc7f`, https://b3b9e943.syncmd.pages.dev
- Prior production / rollback: `9ca816a1-5526-403b-b728-19d116290078`, https://9ca816a1.syncmd.pages.dev
- Source/build checks and all 16 Python site tests passed. Preview and production content checks passed with 23 HTML pages and 22 indexable sitemap URLs, including sharing images.
- Visually inspected desktop and 390px mobile layouts. The document stayed 390px wide; the 720px comparison table scrolled inside a 346px container, and the Git column was reachable by horizontal scrolling.
- Search Console initially reported the new URL as unknown to Google. Its live indexability check completed and Google accepted the indexing request into the priority crawl queue. Queue acceptance does not establish indexing or search impressions.

## Public source-link audit

Audited all 52 unique public external destinations linked from HTML pages using credential-free HEAD requests. Every destination returned HTTP 200 after redirects; this verifies HTTP reachability, not page semantics, anchors, video availability, or signed-in access. [Audit evidence](evidence/seo-20261026/external-links.json) records each URL, result, final destination, and referring pages.

Updated the Gitignore article to link directly to GitHub's current `/en/get-started/git-basics/ignoring-files` documentation rather than its former redirected path. Retained expected App Store locale selection and GitHub token-settings sign-in redirects. Added `scripts/check_site_external_links.py` as an optional manual audit command with separate broken (404/410) and unverified/network/access outcomes; it does not run in required CI.

- Preview: `d1bbec40-941f-4a0b-9a30-9c4d1b05c430`, https://d1bbec40.syncmd.pages.dev
- Production: `94d33843-4145-4084-8b68-804bf0f3c30f`, https://94d33843.syncmd.pages.dev
- Prior production / rollback: `b3b9e943-0be6-4bf5-8cf6-d65d2720cc7f`, https://b3b9e943.syncmd.pages.dev
- Only the Gitignore HTML and its generated Markdown changed in the deployed snapshot. Local source/build, preview, and production SEO checks passed.
