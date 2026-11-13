# Companion directory check fixes — 14 October 2026

Published [0.1.1](https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.1) with the requested manifest/release fixes. Its first completed directory review cleared the description error, confirmed the main.js attestation, and removed unsupported-asset recommendations. Adding a lockfile enabled build verification, which then exposed a missing system zip dependency in the review environment.

Published [0.1.2](https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.2) as an immutable follow-up rather than rewriting the published 0.1.1 tag. The standard build now uses only Node; optional ZIP generation runs only in release preparation. Eleven tests pass, including a clean build with empty PATH that reproduces the committed bundle.

## Release verification

- Description: “Connect the Git plugin using your existing GitSync.md GitHub sign-in.”
- Both releases attach exactly main.js and manifest.json. Optional ZIP/checksum files remain local/CI artifacts.
- GitHub's tagged workflow uses actions/attest@v4 and has the required OIDC/attestation permissions.
- Downloaded both supported assets for both releases; gh attestation verify passed. Provenance identifies their respective tags and source commits.
- 0.1.1 source: f994683e202f1f3aea4da7916c1eedec15f3b0f3; [workflow](https://github.com/CodyBontecou/gitsync-obsidian-connect/actions/runs/38580746882).
- 0.1.2 source: 333ed21ed6398513961ae4929db9ae79d825a583; [workflow](https://github.com/CodyBontecou/gitsync-obsidian-connect/actions/runs/38581638809).
- Runtime main.js is unchanged from the physical iPhone-tested build: SHA-256 c9023da321216ca81365f420196aa56e312a921755d1915dcc4503da103d12b2.
- Evidence: [0.1.1](evidence/obsidian-companion-0.1.1/release-verification.json), [0.1.2](evidence/obsidian-companion-0.1.2/release-verification.json).

## Directory verification

The 0.1.1 scan completed with a build warning (zip missing). After the refresh cooldown, manually requested a scan of 0.1.2. Its review completed successfully on 14 October 2026 for commit 333ed21. The current directory release is now 0.1.2.

All reported checks pass: main.js verified artifact attestation; no suspicious network patterns; vault read/write behavior; no vulnerable dependencies; and build reproduction of the release main.js byte-for-byte. No errors, warnings, or recommendations were present in the expanded 0.1.2 review. Earlier releases remain in collapsed review history.

[Passing review screenshot](evidence/obsidian-companion-0.1.2/community-checks-passed.jpg). [Community listing](https://community.obsidian.md/plugins/gitsync-connect).

The native GitSync.md 2.8.0 app update remains prepared in App Store Connect; publishing these companion fixes does not release the native app.
