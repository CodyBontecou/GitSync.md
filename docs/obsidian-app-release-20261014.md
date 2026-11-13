# GitSync.md 2.8.0 companion update

Prepared on 14 October 2026. Signed build `202610141413` was uploaded to App Store Connect, processed as VALID, and attached to the 2.8.0 App Store draft. Release type is MANUAL. Submitted to Apple App Review on 14 October 2026 at 15:51 Lisbon time. The version and submission are WAITING_FOR_REVIEW. Release remains MANUAL and requires a separate release action after approval. It has not been released publicly.

- App: `6758960270`, `bontecou.Sync-md`.
- Version ID: `70b25f0a-2d35-4fc8-9d98-950f86d042ec`.
- Build ID: `633675fe-5f36-408e-aee8-571acec625ca`.
- [App Store Connect](https://appstoreconnect.apple.com/apps/6758960270/distribution/ios/version/2.8.0).
- Companion: [GitSync.md Connect 0.1.2](https://github.com/CodyBontecou/gitsync-obsidian-connect/releases/tag/0.1.2).

## Source and verification

The release was built from base `2e054050daeda9bae9cc88384bb214227a41e523` plus only `AppState.swift`, `Sync_mdApp.swift`, the new `ObsidianCredentialHandoff.swift`, and project version/build changes. Other uncommitted premium and website changes were excluded from the source snapshot at `/tmp/gitsync-obsidian-app-202610141413`.

- All 10 companion protocol/lifecycle tests passed.
- Signed physical-device Release archive and App Store IPA export succeeded.
- Code signature verified using the macOS signing trust store.
- PrivacyInfo.xcprivacy is present; no StoreKit test configuration is bundled.
- Native handoff SHA-256: `cf6873fc90b60098689d46b981c2fe1eeec55e1020acec3192708d74da510448`, matching the physical iPhone-tested implementation.
- IPA SHA-256: `30052ceec0eec511132771233a3adb724906e734e817b79d9e9c454d6196157c`.
- Listing metadata copied from 2.7.0. New release notes saved for all 26 locales, with companion testing instructions in Apple review notes.
- `asc validate --app 6758960270 --version 2.8.0 --ipa <IPA>` returned zero errors/blockers. Fifteen warnings remain for existing keyword repetition and subscription metadata; App Privacy publication requires a separate web check. No new subscription changes were included.
- This archive was not installed for an additional physical-phone pass; the same handoff source and plugin were already verified in the [iPhone QA report](obsidian-iphone-qa-20261014.md).

Private build artifacts, source patch, release plan and API receipts are in `.asc/release/obsidian-connect/`. The project version/build now matches the uploaded artifact.

## Obsidian community submission

Verified the authenticated account page at https://community.obsidian.md/account/plugins/gitsync-connect after the user authorized the developer policy and ongoing-support acknowledgments. It reports: “Your entry is live. An automated review is in progress.” Version 0.1.0, source commit `d8e7928`, review status Pending.

[Public listing](https://community.obsidian.md/plugins/gitsync-connect). [Submission status screenshot](evidence/obsidian-iphone-20261014/community-submission-pending.jpg).

An entry marked live with pending automated review does not establish review approval or in-app installer availability. The native app update still needs Apple review/release before advertising general compatibility. No duplicate community submission was made when inspection showed the entry already existed.

## App Review submission confirmation

- Submission ID: `cb893845-4654-4313-932f-ce8e3431e330`.
- Apple submission time: `2026-10-14T14:51:44.408Z`.
- Confirmed version and submission state: `WAITING_FOR_REVIEW`.
- Confirmed after submission via the App Store Connect API: `releaseType = MANUAL`.
- Reviewer instructions now reference the checked companion 0.1.2 and its individual main.js/manifest.json install assets.
- Readiness checks, including deep validation, reported zero blockers. Existing subscription/keyword warnings were retained; no subscription products were added to this companion-support submission.
- Existing unrelated stale review submission was skipped, not canceled.
- Private API receipts: `.asc/release/obsidian-connect/review-submit.json`, `post-submit-version.json`, and `post-submit-submission.json`.

## Branding patch: 2.8.1, 16 October 2026

On 16 October, Apple's public lookup reported 2.8.0 released at `2026-10-15T20:03:26Z`; its review submission was COMPLETE. That released version cannot accept a replacement build. The user authorized submitting the branding correction as 2.8.1 with manual release after approval.

- Version ID: `aede74ec-7e12-421b-9135-921fed0ca4d4`.
- Build: `202610162040`; upload ID: `8a3cacef-fce0-49c4-92eb-550b8fa2b7ab`.
- Source: `/tmp/gitsync-branding-app-202610162040`, copied from the isolated 2.8.0 release source. Only SetupView.swift, OnboardingView.swift, and version/build settings differ. The handoff, AppState and app entry point match the released source exactly.
- Branding fix: `4b395589c34862f5f4579763fef3c0e4dda5dcb8`. The sign-in screen was visually verified in the simulator and the blog screenshot updated.
- Signed archive and exported IPA verified as 2.8.1 / 202610162040, with a valid signature, privacy manifest, and no StoreKit test configuration. IPA SHA-256: `0b281b3fdef5e0dbbde5d24ca20a6f7c485ba05884f76c2abc36f74a8e610afc`.
- A first local archive retained the old version due to quoted target-specific build settings. The local audit rejected it; it was never uploaded. The corrected archive and IPA carry a `-corrected` suffix.
- Metadata copied from 2.8.0; branding release notes applied to all 26 locales. Review instructions explain that the correction can be checked without signing in. `releaseType = MANUAL` verified after version creation.
- Submitted on 16 October 2026 at 4:46 p.m. Puerto Rico time (`2026-10-16T20:46:11.739Z`). Apple confirms version and submission state WAITING_FOR_REVIEW. Submission ID: `727149e6-9de8-463f-9648-53f2c534806e`.
- Build processing is VALID. Final readiness checks: zero errors/blockers, 15 existing warnings. No subscription products were added.
- Verified after submission: `releaseType = MANUAL`; a separate release action is required after approval. Private artifacts and receipts: `.asc/release/branding-2.8.1/`.

## Published Obsidian vault fix: replacement 2.8.1 build, 17 October 2026

The user authorized including the published-vault connection fix in the next app update. The older 2.8.1 submission was withdrawn after the replacement build processed successfully, and the same version was resubmitted on 17 October at 6:38 a.m. Puerto Rico time (`2026-10-17T10:38:53.895Z`). Apple confirms `WAITING_FOR_REVIEW`; release remains `MANUAL` and is not yet public.

- Build: `202610171030`; build ID: `302fc5af-6aa1-4064-bb5b-f660e05b1d6a`; processing state: `VALID`.
- Version ID remains `aede74ec-7e12-421b-9135-921fed0ca4d4`. New review submission: `7474766e-29f1-49bf-a60e-3449d05fe971`.
- Publishing an Obsidian folder now prepares the companion's connection metadata. Opening GitSync.md repairs missing metadata for existing supported repositories while preserving existing handoffs. A new clone or companion plugin update is not required.
- Source was copied from the isolated branding release. Only `AppState.swift`, the published-folder regression test, and project build settings differ. Other workspace changes were excluded. The durable source snapshot, signed archive, IPA, hashes, and API receipts are under `.asc/release/published-vault-2.8.1/`.
- The fix was verified in the existing Showcase vault on a physical iPhone 17 Pro using released Connect 0.1.2, Git 2.41.1, and Obsidian 1.14.5. Git Fetch completed without a credential prompt. All 12 companion tests and the native published-folder regression passed. The authenticated-fetch replay passed all seven steps; its idle warning came from the focused search field's caret.
- Archive and IPA audits verified version/build, signing, privacy manifest, widget version consistency, and absence of StoreKit test configuration. IPA SHA-256: `734be4393c2c7f6b7e37537405710a6b2dd1b5d01ee8c0049409b71844d45cd3`.
- Release notes and reviewer instructions were updated and read back after submission. All 26 locales include the fix and retain the branding note. Readiness validation returned zero errors/blockers and 16 warnings: existing keyword/subscription warnings plus an unavailable cached Apple web session, which prevented the additional deep web checks. No subscription products were added.
- Apple's first submission request timed out after creating its review item. The preserved draft was inspected and submitted successfully, without creating a duplicate submission or canceling unrelated stale submissions.

[Submission evidence](evidence/obsidian-published-folder-20261017/release-submission.json). [App Store Connect 2.8.1](https://appstoreconnect.apple.com/apps/6758960270/distribution/ios/version/2.8.1).
