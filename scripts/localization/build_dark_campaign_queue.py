#!/usr/bin/env python3
"""Build the resumable ImageGen queue for the localized dark App Store campaign."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


ASC_LOCALES = [
    "en-US", "ar-SA", "da", "de-DE", "es-ES", "fi", "fr-FR", "he", "hi",
    "hu", "id", "it", "ja", "ko", "nl-NL", "no", "pl", "pt-BR", "ru",
    "sv", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--campaign-root", required=True, type=Path)
    parser.add_argument("--english-style-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def load_copy(campaign_root: Path, source: dict, locale: str) -> tuple[str, list[dict]]:
    if locale == "en-US":
        return "ltr", [
            {"id": asset["id"], "headline": asset["headline"], "subheadline": asset["subheadline"]}
            for asset in source["assets"]
        ]
    reviewed = json.loads(
        (campaign_root / "copy" / "reviewed" / f"{locale}.json").read_text()
    )
    return reviewed["direction"], reviewed["assets"]


def main() -> None:
    args = parse_args()
    source = json.loads((args.campaign_root / "copy" / "source.json").read_text())
    specs = {asset["id"]: asset for asset in source["assets"]}
    english_styles = sorted(args.english_style_root.glob("*.png"))
    if len(english_styles) != 10:
        raise SystemExit(f"expected 10 English iPhone fallback style images, found {len(english_styles)}")
    ipad_styles = sorted(
        Path("marketing/app-store-localization-tests/es-ES/ipad-dark-v2").glob("*.png")
    )
    if len(ipad_styles) != 4:
        raise SystemExit(f"expected 4 approved Spanish iPad style images, found {len(ipad_styles)}")

    queue = []
    for locale in ASC_LOCALES:
        direction, copy_assets = load_copy(args.campaign_root, source, locale)
        copy_by_id = {asset["id"]: asset for asset in copy_assets}
        for asset_id, spec in specs.items():
            form_factor = spec["formFactor"]
            copy = copy_by_id[asset_id]
            source_root = Path("marketing") / form_factor / locale
            ui_references = [str(source_root / name) for name in spec["uiReferences"]]
            if form_factor == "iphone":
                slot = int(asset_id.split("-")[1]) - 1
                safe_style = args.campaign_root / "es-ES" / "iphone" / spec["output"]
                if asset_id == "iphone-08":
                    safe_style = args.campaign_root / "references" / "iphone-08-safe-fr-FR.png"
                style_reference = str(safe_style if safe_style.exists() else english_styles[slot])
                geometry = (
                    "Portrait 1320×2868 iPhone App Store canvas. Match the reference's exact "
                    "panel hierarchy, scale, overlaps, blue connector logic, and generous black margins."
                )
            else:
                slot = int(asset_id.split("-")[1]) - 1
                style_reference = str(ipad_styles[slot])
                geometry = (
                    "Portrait 2048×2732 iPad App Store canvas. Use one or two unmistakably wide "
                    "4:3 landscape iPad panels in the lower two-thirds; never use narrow phone frames "
                    "or an iPhone status bar."
                )
            alignment = (
                "Use a native right-to-left headline and subheadline block, right-aligned with correct "
                "Arabic/Hebrew shaping and natural bidirectional handling of Latin Git terms. Preserve "
                "every word and token in the verbatim copy; do not drop, reverse, or duplicate mixed-script tokens."
                if direction == "rtl"
                else "Use the same left-aligned headline and subheadline block as the campaign reference."
            )
            safe_content = {
                "iphone-02": "If author fields are visible, use author name EXAMPLE and email example@example.com only.",
                "iphone-03": "Show only the fictional app-launch.md editor content from the localized capture; never show CLAUDE.md or copied wiki/source metadata.",
                "iphone-04": "The diff must be for fictional app-launch.md content only; never show CLAUDE.md, a personal name, or copied wiki text.",
                "iphone-07": "The editor panel must show fictional app-launch.md checklist content only, paired with the fictional second-brain repository.",
                "iphone-08": "Use fictional app-launch as the connected repository and a3f8c1d as its hash, or omit the connected-repository card entirely.",
                "iphone-10": "Show only the fictional app-launch.md editor content from the localized capture; never show CLAUDE.md or copied wiki/source metadata.",
            }.get(asset_id, "Use only the fictional second-brain, engineering-docs, team-wiki, app-launch, and meeting-notes identifiers from the localized captures.")
            prompt = f"""Use case: ads-marketing and text-localization/compositing.

Create one finished localized {form_factor} App Store marketing image for GitSync.md. Match the supplied campaign style reference closely while using the supplied {locale} app screenshot capture(s) as the authoritative UI content.

Render this marketing headline exactly, with no translation, paraphrase, omissions, or spelling changes:
{copy['headline']}

Render this subheadline exactly, with no translation, paraphrase, omissions, or spelling changes:
{copy['subheadline']}

Composition: {spec['composition']}
Geometry: {geometry}
Text layout: {alignment}

Visual system: near-black subtly textured background; shallow rounded GitSync.md header with the supplied app icon treatment; bold high-contrast white headline; smaller gray monospaced subheadline; thin white/gray panel outlines; electric-blue connectors and callouts; semantic green/red only inside the supplied Git UI. Every app screenshot and device panel must be rendered in dark mode with near-black surfaces and light text, even when a supplied localized capture uses a light appearance. Keep the same premium developer-tool mood, spacing, typographic hierarchy, and editorial polish as the reference. Any auxiliary marketing callout copied from the style reference must be translated naturally into {locale}; omit it if an exact localized rendering is uncertain. Never leave auxiliary prose in English unless it is a protected technical term.

Content invariants: preserve the localized screenshot language and supplied fictional repository data. Keep GitSync.md, GitHub, Git, Obsidian, iPhone, iPad, Markdown, x-callback-url, branch names, hashes, filenames, code syntax, pull, push, commit, diff, stage, stash, tag, and vault exactly as written in the supplied copy or UI. Do not translate the copy again. Do not invent UI, extra marketing copy, a new logo, portrait/avatar, personal name, username, email, real repository, watermark, or signature. Do not reproduce any personal content that may appear only in the campaign style reference. Explicitly exclude Cody Bontecou, CodyBontecou, Andrej Karpathy, karpathy, Claude, llm-wiki, health-md, and any real profile/avatar; use only the fictional example names and app-launch/meeting-notes content from the localized UI captures.

Asset-specific safe content: {safe_content}

Explicitly forbidden visible strings anywhere in the image: CLAUDE, Cody, CodyBontecou, Andrej, Karpathy, karpathy, llm-wiki, Ilm-wiki, health-md, isolated.tech, 414b110, 532a52e, and c890239. Avoid garbled text, malformed characters, distorted device geometry, or cropped headline text."""
            output = args.campaign_root / locale / form_factor / spec["output"]
            queue.append(
                {
                    "locale": locale,
                    "id": asset_id,
                    "formFactor": form_factor,
                    "headline": copy["headline"],
                    "subheadline": copy["subheadline"],
                    "prompt": prompt,
                    "references": [style_reference, *ui_references],
                    "output": str(output),
                }
            )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"assets": queue}, ensure_ascii=False, indent=2) + "\n")
    print(f"wrote {len(queue)} assets to {args.output}")


if __name__ == "__main__":
    main()
