# Changelog

## Unreleased

- Explain when a vault has no GitSync.md connection metadata instead of showing the generic connection error.
- GitSync.md now prepares connections for published folders and repairs missing metadata for existing GitHub HTTPS repositories when the app opens.

## 0.1.3 — 2026-10-17

- Fix iPhone/iPad reconnect stopping after returning from GitSync.md. Defer app launches until the incoming URL transition finishes so credentials and missing Git author details can be imported.
- Cancel queued app launches when the plugin unloads.
- Add a regression test covering renewal, replacement of existing credentials, encrypted author import, and delayed-launch cleanup. Verified reconnect on a physical iPhone with Obsidian 1.14.5 and Git 2.41.1.

## 0.1.2 — 2026-10-14

- Make the standard build work with Node alone; move optional ZIP packaging into release preparation.
- Add a regression check that builds with an empty PATH and compares the generated bundle to the committed bundle.
- Keep the attested two-file release format introduced in 0.1.1. The plugin runtime remains unchanged.

## 0.1.1 — 2026-10-14

- Include a dependency lockfile so automated build verification can run.
- Correct the manifest description for the community directory.
- Generate GitHub build provenance attestations for main.js and manifest.json in the tagged release workflow.
- Publish only supported installer files as release attachments. Local ZIPs and checksums remain development/CI artifacts.
- Include the README authentication flow diagram.

The plugin runtime is unchanged from the iPhone-tested 0.1.0 bundle. Native handoff support still requires GitSync.md 2.8.0, which is prepared in App Store Connect and not yet publicly released.

## 0.1.0 — 2026-10-14

- Connect Obsidian Git to an existing GitSync.md GitHub sign-in on iPhone and iPad.
- Transfer credentials through an encrypted, expiring handoff without putting tokens in URLs or tracked vault files.
- Fill missing Git author name/email so mobile pull and commit work after cloning.
- Preserve existing credentials unless the user explicitly reconnects.
- Renew expired connection requests without cloning again.

Requires a GitSync.md app build containing the companion handoff. Tested with Obsidian 1.14.5 and Git 2.41.1 on a physical iPhone; compatibility with newer Git credential APIs is covered by fixtures, not a device test. No background token refresh is included.
