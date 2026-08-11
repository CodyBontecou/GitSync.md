#!/usr/bin/env python3
"""Build final per-locale and overview contact sheets for the dark campaign."""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path("marketing/app-store-dark-campaign-v1")
OUTPUT = ROOT / "contact-sheets-final"
QUEUE = ROOT / "queue.json"
BACKGROUND = (7, 8, 10)
CARD = (16, 18, 22)
WHITE = (244, 246, 248)
GRAY = (147, 154, 164)
BLUE = (10, 132, 255)
FONT_PATH = "/System/Library/Fonts/Supplemental/Arial.ttf"


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_PATH, size)


def contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    copy = image.copy()
    copy.thumbnail(size, Image.Resampling.LANCZOS)
    return copy


def locale_sheet(locale: str, assets: list[dict[str, object]]) -> Image.Image:
    width, height = 1320, 1510
    sheet = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(sheet)
    draw.text((54, 38), locale, font=font(54), fill=WHITE)
    draw.text((54, 103), "GitSync.md · localized dark App Store campaign", font=font(23), fill=GRAY)
    draw.rounded_rectangle((54, 153, 282, 160), radius=3, fill=BLUE)

    iphone_assets = [a for a in assets if a["formFactor"] == "iphone"]
    ipad_assets = [a for a in assets if a["formFactor"] == "ipad"]

    draw.text((54, 188), "IPHONE · 10", font=font(25), fill=WHITE)
    iphone_w, iphone_h = 218, 474
    gap_x, gap_y = 28, 28
    start_x, start_y = 54, 234
    for index, asset in enumerate(iphone_assets):
        row, column = divmod(index, 5)
        x = start_x + column * (iphone_w + gap_x)
        y = start_y + row * (iphone_h + gap_y)
        with Image.open(asset["output"]) as source:
            thumb = contain(source.convert("RGB"), (iphone_w, iphone_h))
        card = Image.new("RGB", (iphone_w, iphone_h), CARD)
        card.paste(thumb, ((iphone_w - thumb.width) // 2, (iphone_h - thumb.height) // 2))
        sheet.paste(card, (x, y))

    ipad_y = start_y + 2 * iphone_h + gap_y + 64
    draw.text((54, ipad_y), "IPAD · 4", font=font(25), fill=WHITE)
    ipad_y += 46
    ipad_w, ipad_h = 282, 376
    for index, asset in enumerate(ipad_assets):
        x = 54 + index * (ipad_w + 28)
        with Image.open(asset["output"]) as source:
            thumb = contain(source.convert("RGB"), (ipad_w, ipad_h))
        card = Image.new("RGB", (ipad_w, ipad_h), CARD)
        card.paste(thumb, ((ipad_w - thumb.width) // 2, (ipad_h - thumb.height) // 2))
        sheet.paste(card, (x, ipad_y))

    return sheet


def overview(sheets: list[tuple[str, Path]]) -> Image.Image:
    columns = 4
    tile_w, tile_h = 318, 365
    gap = 24
    rows = math.ceil(len(sheets) / columns)
    width = 54 * 2 + columns * tile_w + (columns - 1) * gap
    height = 170 + rows * tile_h + (rows - 1) * gap + 54
    canvas = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    draw.text((54, 36), "GitSync.md · all localized campaigns", font=font(46), fill=WHITE)
    draw.text((54, 94), "26 locales · 364 final App Store images", font=font(23), fill=GRAY)
    draw.rounded_rectangle((54, 134, width - 54, 141), radius=3, fill=BLUE)

    for index, (locale, path) in enumerate(sheets):
        row, column = divmod(index, columns)
        x = 54 + column * (tile_w + gap)
        y = 170 + row * (tile_h + gap)
        draw.rounded_rectangle((x, y, x + tile_w, y + tile_h), radius=12, fill=CARD)
        with Image.open(path) as source:
            thumb = contain(source.convert("RGB"), (tile_w - 20, tile_h - 52))
        canvas.paste(thumb, (x + (tile_w - thumb.width) // 2, y + 12))
        draw.text((x + 15, y + tile_h - 34), locale, font=font(20), fill=WHITE)
    return canvas


def main() -> None:
    if OUTPUT.exists():
        raise SystemExit(f"refusing to overwrite existing output: {OUTPUT}")
    OUTPUT.mkdir(parents=True)

    assets = json.loads(QUEUE.read_text(encoding="utf-8"))["assets"]
    locales = sorted({asset["locale"] for asset in assets})
    sheets: list[tuple[str, Path]] = []
    for locale in locales:
        path = OUTPUT / f"{locale}.jpg"
        locale_sheet(locale, [a for a in assets if a["locale"] == locale]).save(
            path,
            "JPEG",
            quality=88,
            optimize=True,
        )
        sheets.append((locale, path))

    overview_path = OUTPUT / "overview.jpg"
    overview(sheets).save(overview_path, "JPEG", quality=88, optimize=True)
    manifest = {
        "localeCount": len(locales),
        "assetCount": len(assets),
        "overview": str(overview_path),
        "sheets": [{"locale": locale, "path": str(path)} for locale, path in sheets],
    }
    (OUTPUT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
