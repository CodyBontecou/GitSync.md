#!/usr/bin/env python3
"""Generate an auditable regional app-price plan from saved Apple/WB data.

This only writes a plan and API request body. It does not change store prices.
"""

import base64
import csv
import json
import math
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/evidence/regional-pricing-20261025"
TIERS = [4.99, 6.99, 7.99, 9.99, 12.99, 14.99, 17.99, 19.99]


def read(name):
    return json.loads((EVIDENCE / name).read_text())


def territory(point):
    identifier = point["id"]
    return json.loads(base64.b64decode(identifier + "=" * (-len(identifier) % 4)))["t"]


def main():
    latest = {}
    for row in read("worldbank-gni-ppp.json")[1]:
        code = row["countryiso3code"]
        if row["value"] is not None and (code not in latest or row["date"] > latest[code]["date"]):
            latest[code] = row
    countries = {r["id"]: r for r in read("worldbank-countries.json")[1]}
    grids = {}
    for tier in TIERS:
        grids[tier] = {territory(r): r for r in read(f"equalizations-{tier:.2f}.json")["data"]}
    before = read("before-automatic.json")["prices"]  # --resolved includes US manual price
    assert len({r["territory"] for r in before}) == 175
    us = next(r for r in before if r["territory"] == "USA")
    assert us["customerPrice"] == "19.99" and us["manual"]
    manual_before = read("before-manual.json")["data"]
    assert len(manual_before) == 1, "Review existing manual/scheduled overrides before replacing schedule"
    manual_id = manual_before[0]["id"]
    decoded = json.loads(base64.b64decode(manual_id + "=" * (-len(manual_id) % 4)))
    assert decoded["t"] == "USA" and decoded["sd"] == 0 and decoded["ed"] == 0
    rows = []
    for old in before:
        code = old["territory"]
        wb_code = {"XKS": "XKX"}.get(code, code)
        country = countries.get(wb_code, {})
        datum = latest.get(wb_code)
        # Data more than three years old is too stale for this starting policy.
        if datum and int(datum["date"]) >= 2023:
            raw = 19.99 * math.sqrt(datum["value"] / latest["USA"]["value"])
            target = max(4.99, min(19.99, raw))
            tier = min(TIERS, key=lambda price: (abs(price - target), price))
            basis = "World Bank GNI per capita PPP; square-root scaling vs US"
        else:
            group = country.get("incomeLevel", {}).get("id")
            tier = {"LIC": 4.99, "LMC": 6.99, "UMC": 9.99}.get(group, 19.99)
            basis = f"World Bank income-group fallback ({group})" if group else "No reliable comparable data; retain Apple automatic price"
        if code == "USA":
            tier = 19.99
            basis = "Owner-authorized US price"
        point = grids[tier].get(code)
        price = point["attributes"]["customerPrice"] if point else old["customerPrice"]
        proceeds = point["attributes"]["proceeds"] if point else old["proceeds"]
        point_id = point["id"] if point else old["pricePointId"]
        assert Decimal(price) > 0 and Decimal(price) <= Decimal(old["customerPrice"]), code
        override = Decimal(price) < Decimal(old["customerPrice"])
        rows.append({
            "territory": code, "country": country.get("name", code),
            "analytics_territory": country.get("iso2Code", {"TWN": "TW", "AIA": "AI", "MSR": "MS"}.get(code, code)),
            "currency": old["currency"], "before_price": old["customerPrice"],
            "after_price": price, "after_proceeds": proceeds,
            "discount_percent": round((1 - float(Decimal(price) / Decimal(old["customerPrice"]))) * 100, 1),
            "mode": "manual" if override or code == "USA" else "automatic",
            "benchmark_tier_usd": f"{tier:.2f}",
            "gni_ppp": datum["value"] if datum else None,
            "data_year": datum["date"] if datum else None,
            "basis": basis, "price_point_id": point_id,
        })
    included = []
    for row in rows:
        if row["mode"] == "manual":
            included.append({
                "type": "appPrices", "id": f"${{regional-{row['territory']}}}",
                "attributes": {"startDate": None, "endDate": None},
                "relationships": {"appPricePoint": {"data": {
                    "type": "appPricePoints", "id": row["price_point_id"],
                }}},
            })
    payload = {"data": {"type": "appPriceSchedules", "relationships": {
        "app": {"data": {"type": "apps", "id": "6758960270"}},
        "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
        "manualPrices": {"data": [{"type": p["type"], "id": p["id"]} for p in included]},
    }}, "included": included}
    (EVIDENCE / "plan.json").write_text(json.dumps(rows, indent=2) + "\n")
    (EVIDENCE / "apply-request.json").write_text(json.dumps(payload, indent=2) + "\n")
    rollback = {"data": {"type": "appPriceSchedules", "relationships": {
        "app": {"data": {"type": "apps", "id": "6758960270"}},
        "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
        "manualPrices": {"data": [{"type": "appPrices", "id": "${rollback-us}"}]},
    }}, "included": [{"type": "appPrices", "id": "${rollback-us}",
                        "attributes": {"startDate": None, "endDate": None},
                        "relationships": {"appPricePoint": {"data": {
                            "type": "appPricePoints", "id": us["pricePointId"],
                        }}}}]}
    (EVIDENCE / "rollback-request.json").write_text(json.dumps(rollback, indent=2) + "\n")
    with (EVIDENCE / "prices.csv").open("w") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Planned {sum(r['mode'] == 'manual' for r in rows) - 1} regional discounts; US remains $19.99.")
    examples = {"USA", "GBR", "CAN", "DEU", "FRA", "ESP", "PRT", "POL", "IND", "BRA", "TUR", "JPN", "KOR", "CHN", "IDN", "MEX", "ZAF", "EGY", "NGA", "PAK", "UKR", "TWN", "VEN", "YEM"}
    for r in rows:
        if r["territory"] in examples:
            print(f"{r['territory']}: {r['currency']} {r['before_price']} -> {r['after_price']} ({r['discount_percent']}% lower; {r['mode']})")


if __name__ == "__main__":
    main()
