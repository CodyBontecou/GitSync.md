# Obsidian reconnect repair — 17 October 2026

Reproduced and repaired on Cody's connected iPhone 17 Pro, in the affected Gitsync vault. Obsidian 1.14.5, Git 2.41.1, GitSync.md Connect 0.1.2 with the local fix installed. Subsequently published as [GitSync.md Connect 0.1.3](https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.3).

## Failure and diagnosis

Reconnect opened GitSync.md and returned to Obsidian without a connection notice. Both `user.name` and `user.email` were missing from the vault's Git config, although the native repository configuration contained both author fields. A fresh native request and matching recipient public key existed after return; the native binding remained pending. This isolates the interruption to the second outbound app launch, before native credential export.

A probe changing only `window.open` dispatch to a 750 ms timer completed the handshake and produced the connection notice. This supports an iOS app-transition timing failure. The exact OS mechanism was not instrumented.

## Fix and verification

The companion defers app launches by 750 ms, replaces any queued launch, and cancels pending launch timers when unloaded. No native app changes are required for this fix.

- Before the fix, the phone Git-config assertion failed: `Reconnect returned to Obsidian with Git author name and email still unset`.
- The new automated lifecycle regression failed before the source change. It exercises manual renewal, the prepared callback, deferred outbound launch, encrypted response import, replacement of existing credentials, both author fields, success notice, and timer cancellation on unload.
- `cd obsidian-plugin && npm run build && npm test`: 13 passing tests, including Swift-to-JavaScript encryption and reproducible bundle generation.
- The timing probe completed reconnect on the phone; reading the affected vault's Git config confirmed both author fields were saved. Private identity values and credentials are omitted.
- Installed the final generated `obsidian-plugin/main.js`, replacing the probe. The recorded flow then replayed uninterrupted on the physical phone: **8 passed, 0 failed, 0 errored, 0 skipped**, ending with `Obsidian Git is connected to GitSync.md.`

Replay: `argent flow run obsidian-reconnect-timing-probe-20261017 --device <connected-phone-udid> --platform ios`.

The flow assumes the affected Gitsync vault opens on its Hello World note, both plugins are enabled, and GitSync.md is signed in. It renews credentials without editing notes. Its raw downward swipe is Obsidian's command-palette gesture. The exploratory settings path had unreliable selector capture and was discarded; it is not a passing QA flow. No commit/push or token-revocation test was performed.

## Published release

The standalone repository release is commit `2e8adcb80b7789f6c10985e4634106be16646b49`, tag `0.1.3`. Only the reconnect timing change and its regression test were promoted; the monorepo's unrelated native and missing-metadata work remains separate. Standalone and exported-source tests each passed all 12 checks. [GitHub Actions](https://github.com/CodyBontecou/gitsync-obsidian-connect/actions/runs/39027771125) passed, including asset attestation. Downloaded draft assets matched the local build byte for byte; `gh attestation verify` passed for both. The release was then published, and GitHub's latest-release endpoint confirmed public, non-prerelease version 0.1.3 with only `main.js` and `manifest.json` attached.
