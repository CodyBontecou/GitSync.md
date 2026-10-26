# Regional app pricing — October 25, 2026

The owner authorized region-specific iPhone/iPad prices aimed at improving
affordability and sales, keeping the US one-time price at $19.99. This is a
starting experiment; the existing baseline sample cannot establish
country-level demand curves. Private sales totals remain in local evidence.

Raw API receipts and analytics exports referenced below are intentionally kept
local and excluded from version control.

## Applied and verified

App Store Connect accepted the full price schedule. All 175 storefront prices
and manual/automatic settings were read back and matched the prepared plan:
153 non-US storefronts have lower custom prices, 21 retain automatic pricing,
and the US remains manually fixed at $19.99. Android pricing was outside this change.

| Storefront | Previous automatic price | New one-time price |
| --- | ---: | ---: |
| US | USD 19.99 | USD 19.99 |
| UK | GBP 19.99 | GBP 17.99 |
| Germany | EUR 22.99 | EUR 19.99 |
| France | EUR 22.99 | EUR 19.99 |
| Spain / Portugal | EUR 22.99 | EUR 17.99 |
| Poland | PLN 99.99 | PLN 69.99 |
| India | INR 1,999 | INR 699 |
| Brazil | BRL 129.90 | BRL 59.90 |
| Mexico | MXN 399 | MXN 199 |
| Indonesia | IDR 399,000 | IDR 149,000 |
| China mainland | CNY 148 | CNY 68 |
| Japan | JPY 3,000 | JPY 2,500 |
| South Korea | KRW 33,000 | KRW 29,000 |
| South Africa | ZAR 399.99 | ZAR 149.99 |
| Nigeria | NGN 29,900 | NGN 9,900 |
| Pakistan | PKR 4,900 | PKR 1,300 |
| Türkiye | TRY 999.99 | TRY 799.99 |

The complete schedule, prior state, API request/response, rollback request, and
verification are in `docs/evidence/regional-pricing-20261025/`. The full table is
`prices.csv`. The rollback request restores the immediately preceding US $19.99
base with automatic global equalization; it does not restore the older $9.99 base.

## Starting policy

Source: [World Bank GNI per capita at purchasing power parity](https://data.worldbank.org/indicator/NY.GNP.PCAP.PP.CD),
retrieved October 25. The source reports an October 8, 2026 update. Use the latest
available country observation from 2023–2025, generally 2025. The US reference
observation is 89,490 international dollars per person for 2025.

Calculate `19.99 × sqrt(country GNI PPP per capita / US GNI PPP per capita)`,
clamp the benchmark between $4.99 and $19.99, and round to the nearest of the
$4.99, $6.99, $7.99, $9.99, $12.99, $14.99, $17.99, or $19.99 benchmark tiers.
Use Apple's current equalization for that benchmark to select a valid localized
price point. This retains local price conventions and Apple's current tax and
currency treatment while lowering the benchmark according to purchasing power.
The square-root curve and tier floor are judgment calls, not estimated optima.
National averages are an imperfect proxy for this app's actual buyers.

When comparable recent data is unavailable, use World Bank income-group fallback
benchmarks: low income $4.99, lower middle income $6.99, upper middle income
$9.99, high income unchanged. This sets Yemen to $4.99 and Venezuela to $6.99.
Kosovo maps Apple's `XKS` code to World Bank `XKX`. Taiwan, Anguilla and Montserrat
retain automatic pricing because this source lacks comparable observations or
classifications. No storefront price was increased by this regional change.

Apple's [pricing documentation](https://developer.apple.com/help/app-store-connect/manage-app-pricing/set-a-price)
explains that custom storefront prices stop receiving automatic tax/exchange-rate
adjustments. Review these manually managed prices periodically. The existing
global price script now refuses to replace regional overrides without an
explicit reset flag.

## Measurement and maintenance

Use `scripts/pricing_analytics.py` and the existing baseline under
`docs/evidence/pricing-20261025/baseline/`. Its `by_territory` output includes
country-level purchases, USD proceeds, page views, attributed purchase rate,
and page-attributed proceeds per view. Map two-letter analytics codes with
`analytics_territory` in `plan.json`. Refund reporting remains unavailable.

The existing Sunday 11:00 America/Puerto_Rico heartbeat now monitors regional
results separately from the US $19.99 test. Compare equal-length event-date
windows, exclude October 25, allow reporting delay, and account for missing rows,
privacy thresholds, acquisition mix, and small samples. Review countries and
reasonable purchasing-power cohorts before interpreting worldwide averages.
The first full 28-day window ends November 22. Check for material currency/tax
changes monthly and flag needed price maintenance; do not silently change prices.

The existing website already labels the $19.99 offer as the US App Store price
and states that local store prices may vary, so this change needs no website
deployment. Customer-facing store caches may take time to refresh.
