#!/usr/bin/env python3
"""
Set the Sync.md app price to Free via the App Store Connect REST API.

Usage:
    python3 scripts/set_price_free.py

Credentials are read from the same .p8 key used by fastlane.
"""

import time, json, base64, sys
import jwt as pyjwt
import urllib.request, urllib.error
from pathlib import Path

# ── Credentials ───────────────────────────────────────────────────────────────
BUNDLE_ID = "bontecou.Sync-md"
KEY_ID    = "T7KGDK4Y4V"
ISSUER_ID = "6c3b3640-c6bf-40a9-b6e5-57cda2c7776e"
KEY_PATH  = Path("/Users/codybontecou/.private_keys/AuthKey_T7KGDK4Y4V.p8")
BASE_URL  = "https://api.appstoreconnect.apple.com"

# ── Helpers ───────────────────────────────────────────────────────────────────

def make_token() -> str:
    private_key = KEY_PATH.read_text()
    now = int(time.time())
    return pyjwt.encode(
        {"iss": ISSUER_ID, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        private_key, algorithm="ES256", headers={"kid": KEY_ID, "typ": "JWT"}
    )

def api(method: str, path: str, body=None) -> dict:
    req = urllib.request.Request(
        f"{BASE_URL}{path}", method=method,
        data=json.dumps(body).encode() if body else None
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

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # 1. Find the app
    print(f"Looking up app: {BUNDLE_ID} …")
    resp = api("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}&fields[apps]=name,bundleId")
    apps = resp.get("data", [])
    if not apps:
        sys.exit(f"App not found for bundle ID: {BUNDLE_ID}")
    app_id   = apps[0]["id"]
    app_name = apps[0]["attributes"]["name"]
    print(f"  → {app_name}  (id={app_id})")

    # 2. Find the free price point for this app in the USA territory
    print("Looking up Free price point (USA) …")
    resp = api("GET", f"/v1/apps/{app_id}/appPricePoints?filter[territory]=USA&limit=50"
                      f"&fields[appPricePoints]=customerPrice,proceeds")
    free_pp_id = None
    for pp in resp.get("data", []):
        if float(pp["attributes"]["customerPrice"]) == 0.0:
            free_pp_id = pp["id"]
            break
    if not free_pp_id:
        sys.exit("Could not find a Free (0.00) price point for this app.")
    decoded = json.loads(base64.b64decode(free_pp_id + "=="))
    print(f"  → price point id={free_pp_id}  decoded={decoded}")

    # 3. POST a new appPriceSchedule, replacing the existing one.
    #    The API allows CREATE even when a schedule already exists — it replaces manualPrices.
    print("Setting price to Free …")
    result = api("POST", "/v1/appPriceSchedules", {
        "data": {
            "type": "appPriceSchedules",
            "relationships": {
                "app":           {"data": {"type": "apps",        "id": app_id}},
                "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
                "manualPrices":  {"data": [{"type": "appPrices",  "id": "${free-price}"}]},
            }
        },
        "included": [{
            "type": "appPrices",
            "id":   "${free-price}",
            "attributes": {"startDate": None},
            "relationships": {
                "appPricePoint": {"data": {"type": "appPricePoints", "id": free_pp_id}}
            }
        }]
    })

    # 4. Verify
    new_price_id = result["data"]["relationships"]["manualPrices"]["data"][0]["id"]
    decoded_new  = json.loads(base64.b64decode(new_price_id + "=="))
    print(f"  → new manualPrice id decoded: {decoded_new}")
    if decoded_new.get("p") == "10000":
        print("✅ App price is now FREE (Tier 0)")
    else:
        print(f"⚠️  Unexpected price tier: {decoded_new}")

if __name__ == "__main__":
    main()
