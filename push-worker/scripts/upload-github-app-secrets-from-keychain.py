#!/usr/bin/env python3
"""Atomically upload GitHub App secrets from the local macOS Keychain.

The script never writes credentials to disk or prints them. The bootstrap stores
multiline PEM data as Keychain bytes; macOS `security -w` may render those bytes
as hexadecimal, so the private key is decoded and validated before upload.
"""

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ACCOUNT = "syncmd-github-app"
SECRET_NAMES = (
    "GITHUB_APP_CLIENT_SECRET",
    "GITHUB_APP_WEBHOOK_SECRET",
    "GITHUB_APP_PRIVATE_KEY_PKCS8",
)


def read_keychain_secret(name: str) -> str:
    result = subprocess.run(
        [
            "security",
            "find-generic-password",
            "-a",
            ACCOUNT,
            "-s",
            f"syncmd-push:{name}",
            "-w",
        ],
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Keychain item unavailable: {name}")

    value = result.stdout.decode("utf-8").rstrip("\n")
    if name == "GITHUB_APP_PRIVATE_KEY_PKCS8":
        compact = value.strip()
        if re.fullmatch(r"[0-9a-fA-F]+", compact) and len(compact) % 2 == 0:
            value = bytes.fromhex(compact).decode("utf-8")
        if not (
            value.startswith("-----BEGIN PRIVATE KEY-----\n")
            and value.rstrip().endswith("-----END PRIVATE KEY-----")
        ):
            raise RuntimeError("Keychain private key is not PKCS#8 PEM")
    elif not value or "\n" in value or len(value) > 512:
        raise RuntimeError(f"Keychain item has an invalid wire shape: {name}")
    return value


def main() -> int:
    config = (ROOT / "wrangler.toml").read_text()
    required_public_vars = (
        "GITHUB_APP_ID",
        "GITHUB_APP_CLIENT_ID",
        "GITHUB_APP_SLUG",
        "GITHUB_APP_CALLBACK_URL",
    )
    if not all(f"{name} = " in config for name in required_public_vars):
        print("Refusing upload: add the public GitHub App variables to wrangler.toml first.", file=sys.stderr)
        return 2

    try:
        secrets = {name: read_keychain_secret(name) for name in SECRET_NAMES}
    except (RuntimeError, UnicodeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2

    result = subprocess.run(
        ["npx", "wrangler", "secret", "bulk"],
        cwd=ROOT,
        input=json.dumps(secrets).encode("utf-8"),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if result.returncode != 0:
        print("Cloudflare rejected the GitHub App secret update.", file=sys.stderr)
        return result.returncode

    print("Uploaded three GitHub App secrets from Keychain without writing plaintext files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
