#!/usr/bin/env python3
"""Build the proposed 2.5.2 metadata set without contacting or mutating ASC."""

from __future__ import annotations

import json
from pathlib import Path

from prepare_release_notes import ASC_LOCALE_TARGETS


def main() -> None:
    current = Path("localization/app-store/metadata/current/version/2.5.1")
    release_notes = Path("localization/app-store/release-notes/ssh-forgejo")
    proposed = Path("localization/app-store/metadata/proposed/version/2.5.2")
    proposed.mkdir(parents=True, exist_ok=True)

    for locale in ["en-US", *ASC_LOCALE_TARGETS]:
        metadata = json.loads((current / f"{locale}.json").read_text(encoding="utf-8"))
        metadata["description"] = metadata["description"].replace("Sync.md", "GitSync.md")
        metadata["whatsNew"] = (release_notes / f"{locale}.txt").read_text(
            encoding="utf-8"
        ).strip()
        (proposed / f"{locale}.json").write_text(
            json.dumps(metadata, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )

    manifest = {
        "sourceVersion": "2.5.1",
        "proposedVersion": "2.5.2",
        "requiresExplicitASCMutationApproval": True,
        "mutatesASCWhenRun": False,
        "changes": [
            "Use GitSync.md consistently in version descriptions",
            "Replace stale repository-removal whatsNew with SSH/Forgejo release notes",
        ],
        "preserved": ["keywords", "marketingUrl", "supportUrl"],
    }
    (proposed.parent / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
