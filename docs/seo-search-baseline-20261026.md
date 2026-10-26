# Search Console baseline — October 26, 2026

Property: [sc-domain:gitsync.md](https://search.google.com/search-console?resource_id=sc-domain%3Agitsync.md). Inspected using the authorized `cody@isolated.tech` account. This is an initial crawl/discovery record, not evidence of traffic growth after the release.

## Search performance

The Web search performance report, with its default three-month range and no additional filters, says “Processing data, please check again in a day or so.” Export is disabled and no query rows are available. Clicks, impressions, CTR, and average position are unavailable, not zero. Establish the quantitative baseline once processing finishes; compare complete reporting periods as described in [seo.md](seo.md).

## URL inspection

| URL | Google index result | Current live result / action |
| --- | --- | --- |
| `https://gitsync.md/` | Indexed. Google-selected canonical is the inspected URL; declared canonical matches. Last crawl October 26, 2026 by Googlebot smartphone. | Stored crawl allows crawling and indexing, with a successful fetch. No indexing request needed. |
| `https://gitsync.md/blog/obsidian-git-iphone-ipad` | Alternate page with proper canonical tag. Stored crawl from October 25 still declares the old `.html` URL, which Google also selected. | Live test successfully fetched the corrected extensionless canonical and confirmed crawling/indexing allowed. Indexing request accepted into the priority crawl queue. |
| `https://gitsync.md/blog/obsidian-git-not-working-iphone` | URL unknown to Google; no stored crawl or referring sitemap reported yet. | Google tested whether the live URL could be indexed and accepted the indexing request into the priority crawl queue. |
| `https://gitsync.md/compare/gitsync-vs-working-copy` | URL unknown to Google; no stored crawl or referring sitemap reported yet. | Google tested whether the live URL could be indexed and accepted the indexing request into the priority crawl queue. |

The homepage inspection showed a temporary processing error in its sitemap association, while the sitemap detail report separately confirmed successful processing with 19 discovered pages. The main guide's stored canonical predates the SEO release; its successful live test confirms Google can see the correction, but Google has not yet selected a canonical from the refreshed crawl.

## Follow-up priorities

1. Confirm Google recrawls the main Obsidian guide and selects its extensionless canonical. Avoid repeated indexing requests while it is queued.
2. Confirm discovery and indexing of the troubleshooting and Working Copy comparison pages after their accepted requests. An unknown URL immediately after publication does not establish a crawl block. Queue acceptance does not guarantee indexing.
3. Once reports populate, record query/page metrics and inspect exclusions before creating more overlapping content. Prioritize pages with relevant impressions and a clear gap in their answer or conversion path.

No automatic follow-up or scheduled monitoring was configured.
