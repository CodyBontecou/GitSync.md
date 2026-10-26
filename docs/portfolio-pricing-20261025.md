# Health.md, Vox.md, and iso.me regional unlock pricing

On October 25, 2026, the owner authorized the same purchasing-power policy as
GitSync.md for these three apps and explicitly confirmed this US structure for
each: Individual $19.99, Family $39.99, Family Upgrade $19.99. Existing purchase
entitlements and Family Sharing eligibility are unchanged. Android pricing is
outside this App Store Connect change.

Raw API receipts and analytics exports referenced below are intentionally kept
local and excluded from version control.

## Saved and verified

| App | App ID | Individual IAP | Family IAP | Upgrade IAP |
| --- | --- | --- | --- | --- |
| Health.md | 6757763969 | 6761057963 | 6780235271 | 6781658621 |
| Vox.md | 6758967337 | 6761033996 | 6796785976 | 6796786086 |
| iso.me | 6761960794 | 6807491383 | 6761962715 | 6807491599 |

All nine schedules were applied and read back successfully across 175
storefronts each: 1,575 price points verified. Full requests, responses, previous
state, rollback bodies, country price CSVs, and verification receipts are in
`docs/evidence/portfolio-pricing-20261025/`, grouped by app and IAP ID.

The CLI's `iap pricing ... --resolved` output returned incorrect amounts for
some storefronts despite using the correct underlying price-point IDs. Final
verification instead uses raw API `manualPrices` and `automaticPrices` with
`include=inAppPurchasePricePoint,territory`, matching included objects by their
complete IDs. It checks price, full price-point ID, manual/automatic mode,
175-country coverage, and immediate open-ended dates. Use this raw route for
future price verification; do not rely on the CLI's resolved IAP amounts.

## Regional policy

Individual and Upgrade use the benchmark tiers selected for GitSync.md in
`docs/regional-pricing-20261025.md`, based on World Bank GNI per capita at
purchasing power parity. Each product uses its own current Apple price-point
IDs. Family benchmarks are approximately twice Individual, capped at the
authorized $39.99 US base. In 13 storefronts, Apple equalization rounded Family
more than 1% above Individual plus Upgrade; the nearest available local Family
price within 1% of that sum was selected instead. This keeps the direct Family
purchase reasonably aligned with the upgrade path.

| Storefront | Individual | Family | Upgrade |
| --- | ---: | ---: | ---: |
| US | USD 19.99 | USD 39.99 | USD 19.99 |
| India | INR 699 | INR 1,399 | INR 699 |
| Brazil | BRL 59.90 | BRL 119.90 | BRL 59.90 |
| UK | GBP 17.99 | GBP 34.99 | GBP 17.99 |
| Japan | JPY 2,500 | JPY 5,000 | JPY 2,500 |

The saved per-product CSVs and verification receipts are authoritative. Custom
storefront prices require manual tax/currency maintenance. These starting
prices are affordability hypotheses, not proven sales optima.

## Baseline and monitoring

The baseline event-date window is September 27 through October 24. October 25
is excluded from comparisons as the transition day. Apple report delays,
privacy thresholds, missing event dates, and changes in acquisition mix limit
interpretation. Private baseline purchase/proceeds totals remain in the local evidence directory
and are omitted from this public note.

Those reports are not audited accounting or complete refund-net sales. Missing purchase rows must not be treated as evidence of zero demand.
Each app's baseline includes `by_content` with product-level country summaries.
Discovery/page-view events provide acquisition context; they do not establish
in-app paywall conversion or a purchase funnel for existing users.

Ongoing analytics requests:

- Health.md: `601e9f5f-fbdc-499a-abc4-5348e254f915`
- Vox.md: `0b629f40-5276-4c4d-bf9f-10c183f3429e`
- iso.me: `333d2728-a14c-4416-9056-2cfef3647b27`

For example, capture Health.md's first full experiment week with:

```sh
python3 scripts/pricing_analytics.py \
  --request-id 601e9f5f-fbdc-499a-abc4-5348e254f915 \
  --content-id 6761057963 --content-id 6780235271 --content-id 6781658621 \
  --start 2026-10-26 --end 2026-11-01 \
  --output docs/evidence/portfolio-pricing-20261025/healthmd/monitor/2026-11-01
```

The portfolio heartbeat checks weekly on Sundays at 11:00 America/Puerto_Rico.
It compares product-specific purchases and proceeds by country or sensible
purchasing-power cohorts, stays quiet on insufficient/unchanged data, and flags
meaningful changes or maintenance needs. The first complete 28-day window ends
November 22, subject to reporting delays. It cannot change prices without
further owner instruction. GitSync.md's existing monitor remains separate.

Development StoreKit catalogs and current repository pricing documentation were
updated to the new US prices. Production paywalls use localized StoreKit prices;
no app binary release is required for the live pricing update. iso.me's public
pricing page and Vox.md's documentation are updated separately through their
existing Cloudflare Pages projects.
