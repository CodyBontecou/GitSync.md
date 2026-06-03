#!/usr/bin/env python3
"""
Set the GitSync.md app price via the App Store Connect REST API.

Usage:
    python3 scripts/set_price_paid.py              # defaults to 9.99 USD
    PRICE=14.99 python3 scripts/set_price_paid.py

Credentials are read from the same .p8 key used by fastlane.
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from decimal import Decimal
from pathlib import Path

import jwt as pyjwt

# ── Credentials ───────────────────────────────────────────────────────────────
BUNDLE_ID = "bontecou.Sync-md"
KEY_ID = "T7KGDK4Y4V"
ISSUER_ID = "6c3b3640-c6bf-40a9-b6e5-57cda2c7776e"
KEY_PATH = Path("/Users/codybontecou/.private_keys/AuthKey_T7KGDK4Y4V.p8")
BASE_URL = "https://api.appstoreconnect.apple.com"
BASE_TERRITORY = "USA"
TARGET_PRICE = Decimal(os.environ.get("PRICE", "9.99"))

# ── Helpers ───────────────────────────────────────────────────────────────────

def make_token() -> str:
    private_key = KEY_PATH.read_text()
    now = int(time.time())
    return pyjwt.encode(
        {"iss": ISSUER_ID, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        private_key,
        algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )


def api(method: str, path: str, body=None) -> dict:
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        method=method,
        data=json.dumps(body).encode() if body else None,
    )
    req.add_header("Authorization", f"Bearer {make_token()}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {"ok": True}
    except urllib.error.HTTPError as e:
        err = e.read().decode()
        print(f"  HTTP {e.code}: {err[:400]}", file=sys.stderr)
        raise


def decode_price_id(price_id: str) -> dict:
    padding = "=" * (-len(price_id) % 4)
    return json.loads(base64.b64decode(price_id + padding))


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # 1. Find the app.
    print(f"Looking up app: {BUNDLE_ID} …")
    resp = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}&fields[apps]=name,bundleId")
    apps = resp.get("data", [])
    if not apps:
        sys.exit(f"App not found for bundle ID: {BUNDLE_ID}")
    app_id = apps[0]["id"]
    app_name = apps[0]["attributes"]["name"]
    print(f"  → {app_name}  (id={app_id})")

    # 2. Find the target price point for this app in the base territory.
    print(f"Looking up {TARGET_PRICE} price point ({BASE_TERRITORY}) …")
    price_point_id = None
    next_path = (
        f"/v1/apps/{app_id}/appPricePoints?filter[territory]={BASE_TERRITORY}&limit=200"
        f"&fields[appPricePoints]=customerPrice,proceeds"
    )
    while next_path and not price_point_id:
        resp = api("GET", next_path)
        for price_point in resp.get("data", []):
            customer_price = Decimal(str(price_point["attributes"]["customerPrice"]))
            if customer_price == TARGET_PRICE:
                price_point_id = price_point["id"]
                break
        next_url = resp.get("links", {}).get("next")
        next_path = next_url.replace(BASE_URL, "") if next_url else None

    if not price_point_id:
        sys.exit(f"Could not find a {TARGET_PRICE} price point for this app.")

    print(f"  → price point id={price_point_id}  decoded={decode_price_id(price_point_id)}")

    # 3. POST a new appPriceSchedule, replacing the existing manual price schedule.
    print(f"Setting app price to {TARGET_PRICE} …")
    result = api("POST", "/v1/appPriceSchedules", {
        "data": {
            "type": "appPriceSchedules",
            "relationships": {
                "app": {"data": {"type": "apps", "id": app_id}},
                "baseTerritory": {"data": {"type": "territories", "id": BASE_TERRITORY}},
                "manualPrices": {"data": [{"type": "appPrices", "id": "${target-price}"}]},
            },
        },
        "included": [{
            "type": "appPrices",
            "id": "${target-price}",
            "attributes": {"startDate": None},
            "relationships": {
                "appPricePoint": {"data": {"type": "appPricePoints", "id": price_point_id}},
            },
        }],
    })

    # 4. Verify.
    new_price_id = result["data"]["relationships"]["manualPrices"]["data"][0]["id"]
    decoded_new = decode_price_id(new_price_id)
    print(f"  → new manualPrice id decoded: {decoded_new}")
    print(f"✅ App price is now {TARGET_PRICE} in {BASE_TERRITORY}")


if __name__ == "__main__":
    main()
