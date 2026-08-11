#!/usr/bin/env python3
"""Audit deterministic locale/device screenshot coverage and dimensions."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image

from prepare_release_notes import ASC_LOCALE_TARGETS


EXPECTED = {
    "iphone": {"count": 10, "size": (1320, 2868)},
    "ipad": {"count": 4, "size": (2048, 2732)},
}


def main() -> None:
    root = Path("marketing")
    failures: list[dict[str, object]] = []
    matrix: dict[str, dict[str, object]] = {}
    for locale in ["en-US", *ASC_LOCALE_TARGETS]:
        matrix[locale] = {}
        for form_factor, expectation in EXPECTED.items():
            directory = root / form_factor / locale
            paths = sorted(directory.glob("*.png"))
            sizes: list[list[int]] = []
            hashes: list[str] = []
            for path in paths:
                with Image.open(path) as image:
                    sizes.append([image.width, image.height])
                hashes.append(hashlib.sha256(path.read_bytes()).hexdigest())
            matrix[locale][form_factor] = {
                "count": len(paths),
                "sizes": sizes,
                "uniqueImages": len(set(hashes)),
            }
            if len(paths) != expectation["count"]:
                failures.append(
                    {
                        "locale": locale,
                        "formFactor": form_factor,
                        "reason": f"expected {expectation['count']} images, found {len(paths)}",
                    }
                )
            expected_size = list(expectation["size"])
            for path, size in zip(paths, sizes):
                if size != expected_size:
                    failures.append(
                        {
                            "locale": locale,
                            "formFactor": form_factor,
                            "file": path.name,
                            "reason": f"expected {expected_size}, found {size}",
                        }
                    )
            if paths and len(set(hashes)) != len(paths):
                failures.append(
                    {
                        "locale": locale,
                        "formFactor": form_factor,
                        "reason": "duplicate screenshot content within story",
                    }
                )

    report = {
        "appStoreLocales": ["en-US", *ASC_LOCALE_TARGETS],
        "expectedPerLocale": EXPECTED,
        "matrix": matrix,
        "failures": failures,
        "passed": not failures,
    }
    output = Path("localization/reports/screenshot-audit.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": not failures, "failureCount": len(failures)}, indent=2))
    raise SystemExit(0 if not failures else 1)


if __name__ == "__main__":
    main()
