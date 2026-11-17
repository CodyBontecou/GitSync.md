---
status: merged
covers: [pr#81]
tested: 502e44befb0c3253fec53e1eca20281a60b3bff0
updated: 2026-11-16
---

# Clone into Obsidian's folder, and GitHub author identity: QA 2026-11-16

> PR #81 merged on 2026-11-16 as `1feaa0517ffbf30fc78e3a098b0ee5a9d7aed50c`. It ships in iOS 3.0.1 build 202611162034.

## Reported bug

On iPhone, cloning a vault into Obsidian's folder failed with:

> The folder "myself.md-vault" is outside this vault's location. Choose a different folder name.

## Cause and fixes

**1. Clone target rejected as "outside" its location (`92533728`).** A bookmark into another app's container resolves as `/private/var/mobile/...`. Foundation strips the `/private` prefix only from paths that exist. The existing Obsidian folder became `/var/...`, while the not-yet-cloned target kept `/private/var/...`. `VaultFolderName.isContained` then saw a parent and child with different prefixes. Clones into the app's own Documents never hit this, because `FileManager` returns those paths without `/private`. The fix is `VaultFolderName.canonicalPath`: it resolves symlinks on the deepest existing ancestor and re-appends the missing components. `AppState.canonicalFilePath` uses it too, so the other-vault and moved-copy comparisons also handle paths that don't exist yet.

**2. Author name and email didn't reliably come from GitHub (`502e44be`).** This was found during this QA; the user had also asked for the fields to fill from GitHub.
- The add-vault form only filled *empty* fields when the account identity changed, so a value prefilled from an earlier identity (another account, or the demo user) never followed a new sign-in. It now replaces a field that is empty or still equals the previous default, and keeps text the user typed (`AuthorFieldDefault`).
- When GitHub doesn't share an email (private email, or `/user/emails` fails), the author email stayed empty and the form blocked the clone with "Missing Required Fields". It now falls back to the account's `<id>+<login>@users.noreply.github.com` address. A failed email lookup also no longer aborts sign-in, because it was a `try` inside `activateGitHubAccount`.

## Automated tests

`SyncMDTests` on iPhone 18 Pro simulator (iOS 27.0): **all pass**, including three new tests:
- `testVaultFolderContainmentAcceptsMissingChildUnderPrivateSymlinkedParent`: a missing child under a real `/private/tmp/...` parent is contained, and its canonical path matches the `/tmp/...` form.
- `testAuthorFieldFollowsIdentityChangesButKeepsTypedValues`
- `testGitHubNoreplyEmailUsesIdWhenKnown`

## Physical device QA (Argent)

Device: iPhone 17 Pro, iOS 27.0.1 (`00008150-001405DA2188401C`), wired.

Isolation: an isolated **GitSync PR QA** build (`bontecou.Sync-md.prqa`) installed beside the App Store app, which wasn't modified. The QA-only changes are in `evidence/qa-isolation.patch.txt`: alternate bundle ids, plus `hardPaywallEnabled = false` so the clone wasn't blocked by the $9.99 unlock. The build used no entitlements and the display name "GitSync PR QA". None of this touches the clone, path or author code under test.

| # | Steps | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Onboarding → Continue without GitHub → Default save location → picked Files › On My iPhone › **Obsidian** | Location stored as `/private/var/mobile/Containers/Data/Application/5199F4C3-…/Documents`. This reproduces the `/private/var` precondition | `01-default-location-obsidian-private-var.png` |
| 2 | Signed in with GitHub (the user approved the login on the phone) while the add-vault sheet stayed open with a typed author name | Email filled from GitHub, but the name kept the old text. This led to fix 2 | `02-…txt` |
| 3 | Rebuilt at `502e44be`, then Add vault → entered `<owner>/<private-vault>` | Author name and email both prefilled from GitHub. Clone target: `/private/var/…/Documents/<private-vault>` | `03-…txt` |
| 4 | Check & clone | Vault check: 361 KB, 18 files, read · write, Obsidian vault and Obsidian Git plugin found. Clone completed with **no "outside this vault's location" error**; home shows "All in sync"; debug log shows "Clone complete, branch: main" and no errors | `04-clone-result.txt` |

The user's private vault was cloned into Obsidian's folder with the user's approval. Repository, owner and identity values are redacted in the evidence, and screenshots showing them are not committed.

## Not covered

- The "Not Cloned → Clone" button on an existing vault (the user's exact screen) was not driven. It calls the same `AppState.clone` → `cloneTargetAssessment` check that step 4 exercised.
- iCloud Drive and other File Provider locations were not tried. The fix applies to any bookmark that resolves under `/private`.
- The no-reply email fallback is covered by unit tests only. The test account exposes its email.
- Android is unaffected: it has no `/private/var` symlink, and its clone path doesn't use this check.

## Device state left behind

- **GitSync PR QA** remains installed, signed in, and tracks the cloned vault.
- The clone sits in Obsidian's folder as `<private-vault>`. The App Store app was not modified.
