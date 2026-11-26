---
status: merged
covers: [pr#83]
tested: f446a2c44b4f178dc4a00fec59069bd33baa4feb
updated: 2026-11-26
---

Merged in PR #83 as da6315cc2d803a96cc376b4194658de681db0436 on 2026-11-26.

# Fresh onboarding → GitHub → paywall purchase → Publish a Folder (iOS 3.0.1, physical iPhone)

End-to-end check that a new, non-premium user can get from first launch to a pushed Git repo. The run found copy issues in Publish a Folder; this PR fixes two of them and re-tests on the device (see **Fix verification**). The first run used the release head `46e7876d`; the fix re-test used `f446a2c` (this PR).

## Setup

- Device: iPhone 17 Pro, iOS 27.0.1, wired, driven with Argent (xcuitest runner).
- Build: `release/ios-3.0.1-b2` at `46e7876d` (iOS 3.0.1, build 202611162034 contents), Debug, installed with `xcrun devicectl device install app`. Real StoreKit sandbox, not the `GitSync.storekit` file (that only applies when you run from Xcode).
- The app was not installed beforehand, so this was a genuinely fresh first launch.
- Sandbox: the device's sandbox Apple Account was the `cody@…` tester. The paywall appeared and nothing was restored, so it had no lifetime entitlement. Purchase history for the `cody+sandbox1` tester (which owned the unlock) was also cleared via `asc sandbox clear-history`.

## Result: PASS

| Step | Result | Evidence |
| --- | --- | --- |
| First launch shows the welcome screen | Pass | `screens/01-welcome.png` |
| Continue with GitHub → github.com OAuth ("Sign in to GitHub to continue to GitSync.md") → back in app signed in as @CodyBontecou | Pass (the user completed the password, Face ID and 2FA on the device) | `screens/02-save-location.png` |
| Default save location → Skip for Now → home "No vaults yet" | Pass | `screens/03-home-empty.png` |
| Publish an existing folder shows the paywall (account not unlocked) | Pass | `screens/04-paywall.png` |
| Unlock GitSync.md → sandbox sheet "GitSync.md Lifetime, $9.99, one-time charge, testing only" → confirmed with side button | Pass. The flow continued straight into Publish a Folder (step 1 of 3). | — |
| The unlock survives an app relaunch (Publish opens with no paywall) | Pass | `screens/05-publish-after-relaunch.png` |
| Choose a test folder (`On My iPhone/GitSync.md/onboarding-qa-export`, one README.md), private repo, name free | Pass | `screens/06-publish-review.png` |
| Create private repo & push | Pass. Local commit `2dd01e7` was saved, the repo was created and pushed in about 4 s, and home showed "All in sync" | `screens/07-creating-remote-header.png`, `screens/08-home-in-sync.png` |
| GitHub check (`gh api`) | Pass: `CodyBontecou/onboarding-qa-export` private, default `main`, commit `2dd01e7c86d752af918488b7ef100387d144fba5` "Initial commit" by Cody Bontecou, file `README.md` | — |

## Findings (first run, `46e7876d`)

1. **Recovery header shown during a normal create.** *Fixed in this PR.* While the repo is being created, step 3 reads "Check GitHub / before retrying." along with "GitHub may have created the repository before the response was lost…", even though nothing failed. `FolderPublicationView.headline` maps `.creatingRemote` and `.creationUnknown` to the same copy (`Sync.md/Views/FolderPublicationView.swift:179`). Only `.creationUnknown` should get the recovery wording. See `screens/07-creating-remote-header.png`.
2. **Plural copy.** *Fixed in this PR.* "1 files", "Show all 1 files" and "1 notes & images" appear on the publish review screen and in the commit card.
3. **Paywall copy doesn't fit the Publish entry point.** *Not changed: it's marketing copy that needs a decision and new translations.* Opened from "Publish an existing folder", the paywall still says "Your vault, waiting to be cloned into Obsidian" / "Cloned straight into Obsidian".
4. **No "Folder published." completion screen was seen** on the first run, which started from the empty-home "Publish an existing folder" shortcut. The sheet closed straight to home. It did appear on the re-test, which started from Add vault › Publish a folder, so it doesn't block the flow. Not investigated further.
5. Test tooling only, not an app bug: in the Files picker's search mode, the XCUITest tree goes empty, so Argent couldn't read results. Browsing by folder works.

## Fix verification (`f446a2c`, same iPhone)

Published a new one-file folder `copy-fix-qa` (private repo `CodyBontecou/copy-fix-qa`, commit `b64f351`):

- Review screen: "copy-fix-qa · 1 file", "1 note or image", "Show 1 file" (`screens/09-fix-singular-review.png`).
- While the repo was being created: headline "Ready to publish.", status "Local commit prepared", note "This uploads the saved commit to a new private GitHub repository…", commit card "1 file · main". The recovery copy no longer appears (`screens/10-fix-creating-remote-header.png`).
- Completion: "Folder published." with a link to the repo (`screens/11-fix-folder-published.png`).
- The recovery copy still applies to `.creationUnknown`, and to a `.creatingRemote` record with no request running (for example, after the app was killed mid-create). That path wasn't exercised on the device.
- `SyncMDTests` on the iPhone 18 Pro simulator (iOS 27.0): 550 tests, 0 failures.

## Not covered

- Restore purchase, Ask to Buy / pending, purchase cancel.
- Clone from GitHub and Find clones (only Publish was used as the git export path).
- Commit and push of a later edit after the first publish.

## Cleanup

- `CodyBontecou/onboarding-qa-export` and `CodyBontecou/copy-fix-qa` (both private) are throwaway QA output and can be deleted.
- The test folders are still on the device under `On My iPhone/GitSync.md/`.
