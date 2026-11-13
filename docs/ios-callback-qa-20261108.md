# iOS callback URL QA — 2026-11-08

Argent exploratory QA on isolated iOS 27 simulator `25D7DB92-8559-40B8-9C72-97A8247158E1`, bundle `bontecou.Sync-md`. This pass covers Shortcut/Obsidian callback routing, beyond the earlier callback error-presentation checks. Live actions use read-only status on the disposable git-tools fixture; no remote pushes or production repositories are involved.

## Fixed: case-sensitive scheme and host silently ignored valid links

Opening `SYNCMD://X-CALLBACK-URL/status?repo=git-tools-fixture` reached the native “Open in GitSync.md?” confirmation. After Open, the app remained on Home and never navigated to the fixture. [Before](evidence/ios-callback-qa-20261108/before-uppercase-ignored.png). `canHandle` compared scheme and host case-sensitively. It now normalizes those two components; action names and repository names keep their existing exact matching.

## Fixed: ambiguous callback names selected the first vault

Callbacks identify a vault by `vaultFolderName`. Two vaults in different locations can share that name. The handler used `first(where:)`, so a link could run against whichever entry appeared first. A synchronous regression supplied two distinct cloned vault IDs with the same folder name: the handler navigated to the first and set syncing state instead of reporting ambiguity.

The handler now rejects multiple matches before navigation, syncing state or scheduling an action. It reports the error through the existing x-error/app-error path and asks the user to open the intended vault. This does not add a new callback selector format.

Both regressions failed against the original handler. [Failing assertions](evidence/ios-callback-qa-20261108/regressions-before.txt). The duplicate-name baseline test uses status and a fake local Git repository; no mutating action was triggered to prove the routing problem.

## Verification

Four focused XCTest tests passed on the final build: mixed-case routing/negative host cases, ambiguous vault rejection before navigation/work, and existing pull plus push/sync outcome mappings. [Passing results](evidence/ios-callback-qa-20261108/regressions-after.txt). The first two tests failed on the unchanged handler. Full logs: `/tmp/gitsync-callback-before.log` and `/tmp/gitsync-callback-after.log`. Scoped `git diff --check` passed.

Argent confirmed the uppercase status link navigated to the fixture and displayed “Status Complete.” That check also exposed the singular wording “1 changes”; the final build now displays [“main · 1 change”](evidence/ios-callback-qa-20261108/fixed-uppercase-status.png). A callback missing `repo` displayed the expected [immediate error](evidence/ios-callback-qa-20261108/missing-repo-error.png). Native URL-open confirmations were accepted using accessibility-discovered button frames. The duplicate-name rejection was verified with the handler regression, not a live multi-vault setup. No passing Argent replay or pixel-layout comparison is claimed.

After QA the disposable debug fixture was reset and the app returned to Home. No credential, account, remote or production repository was modified, and no push or commit was triggered by a live callback. The fixed app remains installed; changes are uncommitted.
