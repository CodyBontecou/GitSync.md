# GitSync.md $19.99 price experiment

The owner authorized raising the iPhone/iPad one-time purchase from $9.99 to
$19.99 on October 25, 2026 and monitoring analytics. Existing customers keep
access. Android pricing was outside this change.

Raw API receipts and analytics exports referenced below are intentionally kept
local and excluded from version control.

## Applied and verified

- App Store Connect app: `6758960270`, US base territory `USA`.
- Previous US price: $9.99 USD.
- Verified new US price: $19.99 USD, effective immediately in
  App Store Connect on October 25. Customer-facing storefront caches may lag.
- Initially other territories followed Apple's automatic pricing equalizations.
  Later on October 25, the owner authorized purchasing-power-based regional
  pricing. The current policy is in `docs/regional-pricing-20261025.md`; 153
  non-US storefronts now have custom lower prices, and 21 retain automatic pricing.
- Website production deployment: `https://cd8b854a.syncmd.pages.dev`.
- Previous production deployment: `0fa231de-576b-4d54-a816-3f5686f63d03`.
- The website deployment reused the live production assets and changed only
  eight HTML files containing prices. An unrelated local change in
  `site/blog/obsidian-git-ios-setup.html` was preserved locally and excluded.
- The live homepage was checked for all three $19.99 labels and USD offer
  structured data. The remaining seven price-bearing pages were updated too.

## Baseline and measurement

The baseline covers event dates September 27 through October 24, 2026, using
the existing ongoing Apple analytics request
`e7228014-7aac-40cc-937d-7e9ad32121e4`. Raw purchase and discovery reports and
the summary are in `docs/evidence/pricing-20261025/baseline/`.

Private purchase/proceeds totals remain in the local evidence directory. They are omitted from this public note. Missing event dates and reporting thresholds limit interpretation.
Discovery currently ends October 23; purchases end October 24.

Compare US and all-territory purchases and proceeds per day over equal-length
windows. Check traffic and source mix alongside page-attributed purchases per
page view and page-attributed proceeds per page view. Page-view counts are
events, not unique visitors, and this attributed rate is not Apple's dashboard
conversion rate. Rates use event dates present in both reports; other totals
use all available dates. Do not count missing data as zero.

The script resolves overlapping daily report snapshots by keeping the latest
processed snapshot for each event date. Signed download URLs are not saved.
Refund counts require a separate reporting route and are currently unavailable;
the Apple web session is not signed in and no sales-report vendor number is
configured. The purchase/discovery API works without either prerequisite.

Run a report with:

```sh
python3 scripts/pricing_analytics.py \
  --start 2026-10-26 --end 2026-11-01 \
  --output docs/evidence/pricing-20261025/monitor/2026-11-01
```

Exclude October 25 as a transition day. Allow for Apple's reporting delay.
The first full 28-day experiment window is October 26 through November 22;
assess it once the reports arrive. Evaluate net proceeds alongside purchases,
refunds, territory, and traffic mix. Small samples and acquisition changes limit conclusions.

## Monitoring

An active weekly chat heartbeat, `watch-gitsync-md-19-99-pricing`, checks Sundays
at 11:00 in America/Puerto_Rico. It stays quiet for unchanged, insufficient, or
non-actionable data and notifies on meaningful changes, an actionable 28-day
assessment, or a reporting failure requiring owner attention. It does not change
prices or publish further changes.

The baseline summary now includes `by_territory`. Monitoring must compare regional
prices separately, using `analytics_territory` in the regional plan to map Apple's
two-letter analytics codes to three-letter storefront identifiers. The regional
change shares the October 25 transition day with the US increase.
