# PR44 — additional real iOS callback-host acceptance

Candidate unchanged `0d15ed5e199f62499c62621f0e9cdff0e0f19681`, product source tree `424216557435fa2c1a3d5d43bdb90c2c7e2ba161`; installed isolated QA dylib SHA256 `68fe868e9ae63baf169757ac979acb4227c520a1034468eebe65cd05af733b6a`. No app/UI/unit/workflow/project source changes for this follow-up. Additional artifacts are local sidecars, not yet committed into this candidate; do not mislabel them as CI-executed device tests.

## Direct phone/OS evidence

- On physical iPhone13 Pro Max/iOS27.2, delivered `syncmd://x-callback-url/pull` **explicitly to `bontecou.Sync-md.prqa`**, avoiding ambiguous shipping scheme resolution. Existing real coordinator lease stayed held across delivery; actual native Pull queued and exposed normal Cancel/progress.
- Normal Cancel produced actual localized interruption guidance. App's real `x-error` URL reached an owned private-LAN HTTP receiver and Safari displayed **status=error, updated=false, no SHA**, with exact manual-retry guidance, not raw Swift error text. Receiver only echoes actual received query parameters; it does not inject app outcomes or modify a repository.
- Callback cleanup returns to Home. Reentering Vault recreates its DEBUG view-local lease marker; the fixture's idempotent hold operation reattaches the existing holder before explicit release. This is test-fixture normalization, not a customer cancellation/result override.
- Only after explicit lease release, manually repeated the real callback Pull. Native completion showed Already up to date; app's actual `x-success` arrived with **status=ok, updated=false, SHA matching actual native HEAD**. Dirty README remained intact. Screenshots `callback-error-native.png` / `callback-success-native.png` independently inspected; both identify the QA app in Safari's return affordance.
- A fresh receiver baseline page is positively established before each case, so a stale prior callback page cannot satisfy the next result gate. Native cross-app inspection activates Safari only after the app's source-controlled banner/redirect delay; app-generated URL delivery is independently checked in the receiver ledger.

## Repeated complete flow

Final unchanged `/tmp/gitsync-pr44-qa/.argent/e2e/qa-pr44-native-callback-final.yaml` passed **66/66 steps twice consecutively** (63 non-echo steps + three echoes), **58.385s / 59.771s**, scoped fresh device services first then immediate warm replay. Zero failures/errors/skips/readiness warnings. Receipts `callback-final-pass1.json`, `callback-final-pass2.json`, `callback-final-pair-fresh-services.json`.

Actual receiver ledger after final-pair baseline contains exactly **error → success → error → success**. Error entries have no SHA and exact interruption message; success SHAs differ legitimately across independent reseeds (`8ee85f76cdba37f4ec6517fb33018a039bae5a33`, `6d10c918a0c1fd15e077d037c27168bd0c3fbcac`). Last SHA independently matches copied native HEAD.

Independent live-take disk oracle: **all28 working/Git files byte-identical on cancellation**, including native index/refs/notes. After explicit callback retry **only `.git/FETCH_HEAD` changed**. Post-final-pair copy confirms exact unpublished README, nonempty real FETCH_HEAD, empty staged diff and callback/native HEAD match (`callback-final-native-disk.json`). No cross-reseed index-byte equality claim.

## Preserved failed takes and limits

- Raw recording initially assumed return-to-Vault; actual callback cleanup returned Home. Two unmet waits retained in `native-callback-walkthrough.incomplete.yaml.txt`; correct Home/reentry span freshly observed/recorded. That raw take is not counted as a complete pass.
- First polished replay activated Safari before CallbackURLHandler's deliberate2s error-banner delay and never received the callback. Failed23-pass receipt and fresh-baseline failure tree retained (`callback-premature-safari-failed.json`). Restored the recorded foreground handoff contract with source-grounded2.5s/2s waits for the2s/1.5s error/success delays, then retained all positive receiver/data gates. Both final passes use the same unchanged flow. No product source/assertion weakened.
- Initial live receiver ledger contains a repeated error GET before success. Its exact cause is unestablished; it is not classified as automatic Git retry or a separate app execution. A bookkeeping oracle that broke merely when total rows reached two was corrected to wait for the actual success result. Raw ledger retained. Final pair has exactly the expected four deliveries.
- This is actual iOS URL callback routing/return-channel proof, **not a real Shortcuts AppIntent run**, induced physical post-update LFS interruption, human26-locale/RTL/count-grammar review, original incident root-cause reproduction or Obsidian interoperability.

## Exact-head CI audit now complete

`43515488866` SUCCESS on candidate0d15ed5: **528 total, two explicit hosted StoreKit skips, zero failures**; Release BUILD SUCCEEDED, privacy manifest OK and StoreKit resource exclusion inspected. Build guard43515488479SUCCESS. Hosted synthetic `9ae19c9ed5d9590a14e3db82e424702969fd8637` has expected current-main27146b7/head0d15ed5 parents and whole tree `0c7b9017814de6a7e9cabc659f2b9e3accba00ff`, identical to candidate and verified in checkout log. Shipping project identifiers intact. Artifacts `pr44-final-exact-head-ci.json`, `final-exact-head-ci.log.gz`, `pr44-final-tree-audit.json` in root evidence.

PR44 remains draft/unmerged while other stated required gates remain unestablished. No deployment/release/automatic issue closure. Receiver process stopped after acceptance; no recorder active; phone returned to QA Home, holder released. Overall goal incomplete.
