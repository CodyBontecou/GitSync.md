#!/usr/bin/env python3
"""Build locale/device contact sheets for screenshot review."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from audit_screenshots import EXPECTED
from prepare_release_notes import ASC_LOCALE_TARGETS


def make_sheet(locale: str, form_factor: str) -> Path:
    paths = sorted((Path("marketing") / form_factor / locale).glob("*.png"))
    columns = 5 if form_factor == "iphone" else 4
    thumb_box = (220, 480)
    label_height = 54
    rows = (len(paths) + columns - 1) // columns
    canvas = Image.new("RGB", (columns * thumb_box[0], 70 + rows * (thumb_box[1] + label_height)), "#f4f4f4")
    draw = ImageDraw.Draw(canvas)
    draw.text((20, 20), f"GitSync.md | {locale} | {form_factor} | {len(paths)} images", fill="black", font=ImageFont.load_default(size=22))
    for index, path in enumerate(paths):
        image = Image.open(path).convert("RGB")
        image.thumbnail((thumb_box[0] - 16, thumb_box[1] - 16))
        cell_x = (index % columns) * thumb_box[0]
        cell_y = 70 + (index // columns) * (thumb_box[1] + label_height)
        x = cell_x + (thumb_box[0] - image.width) // 2
        y = cell_y + (thumb_box[1] - image.height) // 2
        canvas.paste(image, (x, y))
        draw.text((cell_x + 8, cell_y + thumb_box[1] + 8), path.stem, fill="black")
    output = Path("marketing/contact-sheets") / f"{locale}-{form_factor}.jpg"
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, quality=88, optimize=True)
    return output


def main() -> None:
    records: list[dict[str, object]] = []
    for locale in ["en-US", *ASC_LOCALE_TARGETS]:
        for form_factor in EXPECTED:
            output = make_sheet(locale, form_factor)
            records.append(
                {
                    "locale": locale,
                    "formFactor": form_factor,
                    "path": str(output),
                }
            )
            print(output)
    manifest = Path("localization/reports/contact-sheets.json")
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        json.dumps({"count": len(records), "contactSheets": records}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(manifest)


if __name__ == "__main__":
    main()
