# Companion release candidate — 14 October 2026

GitSync.md Connect 0.1.0 is published for manual installation. The companion needs the GitSync.md app handoff changes released alongside it; public availability of that native build remains unconfirmed.

## Deliverables

- `obsidian-plugin/dist/gitsync-connect.zip`: install folder containing main.js, manifest.json, and MIT license.
- `obsidian-plugin/dist/main.js` and `manifest.json`: assets expected by Obsidian's installer.
- `obsidian-plugin/dist/gitsync-connect-source.zip`: independent repository source with root manifest, documentation, license, tests, native protocol snapshot, and draft-release workflow.
- `obsidian-plugin/dist/SHA256SUMS`: SHA-256 checksums for all four assets.
- `obsidian-plugin/RELEASE.md`: maintainer publication and matching-app requirements.
- `.github/workflows/obsidian-connect.yml`: app-monorepo validation and candidate artifact upload.

## Validation

- App-monorepo companion tests: 10 passed, including actual Swift/CryptoKit producer → JavaScript consumer.
- Source ZIP extracted into an independent temporary directory: all 10 tests passed; release assets rebuilt without the app checkout; all four checksums passed.
- Installation ZIP inspected: exactly the gitsync-connect folder and its three files, with no test or credential data.
- Generated main.js syntax check passed. SHA-256 `c9023da321216ca81365f420196aa56e312a921755d1915dcc4503da103d12b2` matches the bundle exercised in the [physical iPhone test](obsidian-iphone-qa-20261014.md).
- Both workflow files parsed as YAML. Hosted GitHub Actions passed on the published source. BRAT and community directory installation have not been verified.
- `git diff --check` passed.

## Publication completed

- Public repository: https://github.com/CodyBontecou/gitsync-obsidian-connect
- Source commit: `d8e7928dd0be39d13ff0b160610b306ead2dab3c`.
- Published release: https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.0 (14 October 2026, 13:58 UTC).
- Hosted validation: https://github.com/CodyBontecou/gitsync-obsidian-connect/actions/runs/38576201631 — passed tests, packaging, and artifact upload.
- Five published attachments: main.js, manifest.json, install ZIP, source ZIP, SHA256SUMS.
- Downloaded all five published attachments and verified all four SHA-256 entries and ZIP integrity. Published main.js is byte-for-byte identical to the physical iPhone-tested plugin.
- Prepared submission: https://github.com/CodyBontecou/gitsync-obsidian-connect/blob/main/SUBMISSION.md

## Community submission remaining

The submission material includes the exact repository URL, owner, metadata, reviewer description, account/network/cost disclosures, and compatibility limitations. No directory entry was submitted. The current process requires the maintainer's Obsidian account linked to GitHub, agreement to developer policies and ongoing support, and a working native app build reviewers can access. Confirm matching app availability and repeat the clean published-package iPhone installation check before submitting.

Official requirements checked against [Obsidian's submission guide](https://docs.obsidian.md/plugins/releasing/submit-plugin) on 14 October 2026: repository-root README/LICENSE/manifest, matching version tag, and attached main.js/manifest.json.
