# Android Git LFS transport fixtures

`lfs_device_fixture.py` runs disposable loopback HTTPS Git/LFS and OpenSSH servers
on macOS. It creates a new bare repository and test object under `/private/tmp`,
uses the fake encrypted SSH identity in the AndroidTest assets, and requires no
GitHub account. Run it from the repository root:

```sh
python3 tools/testing/lfs_device_fixture.py
```

The process remains running. Metadata in `/tmp/syncmd-lfs-device-fixture.json`
contains its PID, paths, ephemeral ports, public host key and test certificate.
Keep the process alive until instrumentation finishes. Build the debug app and
AndroidTest APKs, then install them sequentially on the dedicated GitSync
emulator before running the following Python script from a second terminal:

```python
import json
import subprocess

fixture = json.load(open('/tmp/syncmd-lfs-device-fixture.json'))
adb = '/Users/codybontecou/Library/Android/sdk/platform-tools/adb'
device = 'emulator-5556'  # Confirm this is the dedicated GitSync AVD first.

for port in (fixture['httpPort'], fixture['sshPort']):
    subprocess.run([adb, '-s', device, 'reverse', f'tcp:{port}', f'tcp:{port}'], check=True)

subprocess.run([
    adb, '-s', device, 'shell', 'am', 'instrument', '-w',
    '-e', 'class', 'com.bontecou.syncmd.LfsTransportDeviceTest',
    '-e', 'sshFixtureUrl', fixture['sshUrl'],
    '-e', 'sshFixtureHostKey', fixture['hostKey'],
    '-e', 'lfsFixtureUrl', fixture['httpsUrl'],
    '-e', 'lfsFixtureCertificate', fixture['cert'],
    'md.gitsync.md.debug.test/androidx.test.runner.AndroidJUnitRunner',
], check=True)
```

Use a **fresh fixture process** for
`LfsEndpointFlowTest#reviewedDownloadAndUploadUseSeparateCredentialsAndRevocationStopsLaterTransfers`
instead of the transport test above: each test expects the initial remote
content and publishes a different commit. That test needs only `lfsFixtureUrl`
and `lfsFixtureCertificate`. It
uses real Compose approval/download/settings screens, fake separately encrypted
endpoint credentials, a real upload/verify and Git push, cancellation and
direction-specific revocation. Its screenshots are saved in the debug app's
external-files `parity-evidence` directory. The fixture rejects Git credentials
at `/custom-lfs`; download and upload require different fake credentials.

Before cleanup, independently inspect the bare remote's `main:asset.bin`
pointer, hash/compare its corresponding `objects/<oid>` file, and read
`counts.json`. For the private endpoint test, expect one custom download batch,
one custom upload batch, one upload, one verification, and zero escaped Git
credentials. The ordinary transport test expects the original 16,384-byte
object plus `[91, 92, 93]`; the private endpoint test expects it plus
`[71, 72, 73, 74]`.

For deterministic connection-loss recovery, start another fresh fixture with
`--drop-first-upload`, then select
`LfsEndpointFlowTest#interruptedUploadKeepsCommitAndRetriesUsingApprovedCredentials`.
It requires the first real upload to fail with an I/O error, verifies the
remote ref/index/local commit/working bytes, and retries the same commit
without a new approval. Expect `injectedUploadDrops: 1`, two custom upload
batches, one successful upload/verify, and zero escaped Git credentials.
Select exactly one endpoint-flow method per fixture; running both methods
against the same already-modified remote is invalid.

`requests.jsonl` records method, path, connection, response status and lengths
to distinguish transfer/verification/Git publication order. It deliberately
omits authorization, URL queries, bodies and exception messages. The injected
drop occurs after the body is received and checked, before it is stored or any
response is sent.

For the production lock-blocked-push screen, start a fresh fixture with
`--foreign-lock` and run `com.bontecou.syncmd.LfsLockDeviceTest`, passing
`lfsFixtureUrl` and `lfsFixtureCertificate`. The server returns the foreign
`asset.bin` lock on the second verification page. The test navigates through
the real app/DI, checks the blocked-push dialog, unchanged remote and exact
local commit/index/contents, removes only the fake fixture lock, then retries
through the app's Push Current Branch control. A normal unlock must fail for
the foreign owner; the fixture-only explicit force unlock succeeds. Expect
three verification requests, one unlock and one object upload/verification.
Pass `lockDark=true` for the dark theme. Use a separate fresh fixture per run.
The test restores app preferences and removes only its UUID-scoped working
copy and encrypted credential entry. Screenshots are saved in `parity-evidence`.

`LfsHttpSequenceDeviceTest` is a transport diagnostic requiring the same URL
and certificate arguments. With a normal fresh fixture it performs 100 direct
HTTPS upload/verify pairs; `httpMixedGit=true` also performs the real push
advertisement before each pair. `httpIterations` accepts 1–200. The generated
objects and all credentials are synthetic. It deliberately bypasses UI/Git
publication to isolate Android's HTTP stack and is not a feature acceptance
test. Its last completed iteration is saved in `parity-evidence/lfs-http-sequence.json`.
The fixture logs protocol/connection-policy/TLS version/session reuse and
sanitized TLS-accept error codes without auth headers, URL queries or bodies.

For interaction recordings, launch `screenrecord` and instrumentation from
the same orchestration process. Separate tool calls can spend the recording
window waiting to start the test. Verify multiple video frames with `ffprobe`
and actually inspect the frames before counting a recording as evidence.
Recordings of the lock test include a test-only API removal of the fake lock;
they must not be presented as the app automatically unlocking foreign files.

For branch-checkout hydration and partial-success recovery, run
`com.bontecou.syncmd.LfsCheckoutDeviceTest` against a fresh ordinary fixture,
passing `lfsFixtureUrl` and `lfsFixtureCertificate`. It creates and publishes an
`assets` branch using synthetic content, evicts only its cached object, then
switches through the production Git screen. A later switch with rejected
credentials must retain that branch and show a completed-Git/download-attention
notice. The test restores the connection and retries through the Git LFS screen,
without another checkout or commit. Expect one upload, one verification, three
downloads, zero escaped credentials, unchanged `main`, and a 16,390-byte object
on `assets` (the original 16,384 bytes followed by `[41, 42, 43, 44, 45, 46]`).
Pass `checkoutDark=true` for dark mode. For the large-text check, set system
font scale to 1.6 before launching instrumentation and restore its original
value afterward. Use a separate fresh fixture per run. The test waits for
asynchronous branch loading and stable native dialog frames before capture,
restores preferences, and deletes its UUID-scoped repository/credential entry.

After instrumentation, remove the two exact `adb reverse` rules and send
SIGTERM to the fixture PID **after checking its process command**. Wait for
the fixture process to exit and confirm both listener ports closed. SIGTERM
also shuts down its OpenSSH child. Temporary repositories and counters remain
available for evidence; remove only the fixture directory you created when
it is no longer needed. Do not change app permissions or replace APKs while
instrumentation is running. These tests use app-owned storage and do not need
the all-files storage permission.

For the manual lock-management UI, use a fresh `--foreign-lock` fixture and run
`com.bontecou.syncmd.LfsLockManagementDeviceTest` with `lfsFixtureUrl` and
`lfsFixtureCertificate`. The production flow lists without granting write
controls, verifies paginated ownership, requires force-release acknowledgement,
cancels without a write, rejects a credential record changed during confirmation,
then force releases, creates an owned lock and normally releases it. The create
confirmation is submitted twice synchronously to check the duplicate-write gate.
The test compares the exact working bytes, index, local HEAD and remote HEAD.
Expected counters: `creates: 1`, `unlocks: 2`, `uploads: 0`, `verifies: 0`,
`downloads: 1`, and no escaped Git credentials. `lockChecks` is 13 with the
current two-page fixture. Each replay needs a fresh server state.

Pass `lockDark=true` and set actual system font scale to 1.6 before starting
instrumentation for the dark large-text pass; restore the captured original
scale in a finally block. Screenshots include the acknowledged force dialog,
stale-review error and owned-lock card. Start screen recording immediately
before instrumentation and stop it after completion. These are debug-emulator
interaction diagnostics, not release-device frame-rate certification.

## Callback authorization across real process restarts

After installing matching debug and AndroidTest APKs, run the three methods of
`RepositoryCallbackColdStartTest` separately. Use one fresh UUID as
`callbackFixture` for all three phases, and force-stop **only**
`md.gitsync.md.debug` between phases. Confirm the target is the dedicated GitSync
emulator first; do not target a physical phone or another project's AVD.

```python
import pathlib
import subprocess
import uuid

adb = '/Users/codybontecou/Library/Android/sdk/platform-tools/adb'
device = 'emulator-5554'
avd = subprocess.check_output([adb, '-s', device, 'emu', 'avd', 'name'], text=True)
assert avd.splitlines()[0] == 'gitsync-paid-review-20261026'
fixture = str(uuid.uuid4())
phases = [
    'preparePersistedAuthorization',
    'executeInFreshProcessAndPersistRevocation',
    'revokedLinkRemainsDisabledInAnotherFreshProcess',
]
for index, phase in enumerate(phases):
    if index:
        subprocess.run([adb, '-s', device, 'shell', 'am', 'force-stop',
                        'md.gitsync.md.debug'], check=True)
    result = subprocess.run([
        adb, '-s', device, 'shell', 'am', 'instrument', '-w',
        '-e', 'callbackFixture', fixture,
        '-e', 'class', f'com.bontecou.syncmd.RepositoryCallbackColdStartTest#{phase}',
        'md.gitsync.md.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    log = pathlib.Path('/tmp') / f'syncmd-callback-{fixture}-{phase}.log'
    log.write_text(result.stdout)
    assert result.returncode == 0 and 'OK (1 test)' in result.stdout, str(log)
    print(f'{phase}: passed ({log})')
```

Each continuation asserts that its PID differs from the preceding phase. The
test verifies no startup replay, one explicitly invoked real local-bare-Git
push, durable revocation, preservation of a later draft and final cleanup. It
uses UUID-prefixed preferences and an app-private synthetic-token handoff; it
does not change account/catalog preferences belonging to the installed app.
On failure, stop and inspect that UUID's logs and retained fixture rather than
rerunning preparation against it. The final successful phase removes its own
workspace and preference namespaces. This does not simulate death during an
active commit or push, or claim interrupted external-result delivery recovery.

## Obsidian companion interoperability

`ObsidianConnectionTest` uses namespaced fake accounts, encrypted repository
credentials and local working copies. It performs no GitHub API or Git network
operation. The public-folder method also exercises an app-owned Android/media
vault without changing the all-files permission.

For independent wire verification, pass `-e obsidianInterop true` when running
`ObsidianConnectionTest#nativeProviderPublishesCompatibleCiphertextOnceAndKeepsGitAndDrafts`.
After instrumentation finishes, retrieve only its synthetic fixture:

```sh
adb -s emulator-5554 exec-out run-as md.gitsync.md.debug cat files/parity-evidence/obsidian-native-interop.json > /tmp/syncmd-obsidian-native-interop.json
node tools/testing/obsidian_compat.mjs /tmp/syncmd-obsidian-native-interop.json ../gitsync.md/obsidian-plugin/src/connection.js
```

Confirm emulator-5554 is the dedicated GitSync AVD before running those commands.
The Node script calls the actual companion module's `decrypt()` and
`installCredentials()`, verifies request binding/expiry, and reports its source
SHA-256. It uses only Node built-ins. The JSON includes a **synthetic** recipient
private key and fake token envelope: never supply a user's vault/key/credentials.
The fixture is intentionally retained under parity-evidence until retrieved;
remove that exact file afterward. Normal test workspaces/preferences are removed
by teardown. Screenshots remain available for visual review.

The JVM crypto test can emit the same fixture format when
`SYNCMD_OBSIDIAN_INTEROP` names an isolated directory containing an
`owned-fixture` marker file. Run `:core:test --rerun-tasks` if Gradle would reuse
an earlier test result, then pass `android-handoff.json` to the same Node script.
The test always verifies its envelope with independent Bouncy Castle HKDF even
when export is not requested.

This verifies protocol compatibility and Android's actual crypto/filesystem
path. Installed Obsidian, community-plugin app switching, Obsidian's own folder
grant, and interrupted/cold-process delivery remain separate integration checks.

## Push Sync

`PushSyncTest` uses UUID-scoped encrypted preferences, fake messaging/relay
interfaces and owned local Git fixtures. It covers registration caching,
rotation, opt-out races, notification revocation, one-use GitHub App callbacks,
real WorkManager worker classes, event invalidation and native consent/unlink
controls. It does not send notifications or contact GitHub/the live relay.
`FileNavigationTest` also exercises the actual Settings → Push Sync route,
activity recreation and hardware Back.

The app-wide Push Sync model initializes from encrypted local state without a
relay request. Entering/resuming the screen reads opted-in connection status
without requiring an SDK token or re-registering; a successful in-flight action
may finish first. Stop/failure results are preserved instead of silently queuing
a retry. Unknown status remains distinct from a verified empty installation list,
so setup guidance appears only after a completed check. Opt-out/revocation clears
visible connections; transient reads retain verified cards with an error.

Manual refresh still maintains registration. A typed registration failure permits
an independent status read for that same consent generation while retaining the
registration error; a status-read failure does not immediately retry itself.
A 30-second SDK deadline is a registration failure; actual user cancellation
remains Stop and issues no fallback request. Missing-device responses recheck
consent before resetting the cached registration; a current 404 permits explicit
re-registration, while stale responses/fallbacks cannot publish for replacement
consent. An unavailable build permits opting out/unregistering an enabled device,
while enabling/linking remain disabled. Native cases exercise these paths using
fake relay/messaging interfaces and owned encrypted/Git fixtures, including the
real SDK-wait deadline, production screen controls and lifecycle transitions.
They do not replace browser authorization or live webhook qualification.

The two live tests require explicit `liveFirebase=true`. They are restricted to
the approved personal Firebase project `gitsync-md-android-20261028`, whose human
owner is `bontecouc@gmail.com`. Never use CPI credentials. The registration test
issues/deletes an actual SDK token while preserving app opt-out. The delivery
script runs `PushSyncLiveDeliveryTest` with a fresh UUID:

```sh
python3 tools/testing/push_live_delivery.py --live-firebase --device emulator-5556 --avd gitsync-paid-review-20261026
```

Before running, install matching debug/test APKs on the dedicated
`gitsync-paid-review-20261026` AVD (pass its identity-verified serial explicitly) and pre-grant the debug app's
notification runtime permission through Argent's permission setup tool. Record
and restore the original grant/flags afterward. Stop scoped Argent accessibility
services before instrumentation to avoid competing UIAutomation clients. The
script verifies the AVD and explicitly uses `gcloud --account=bontecouc@gmail.com`;
it never changes the globally active account or prints an access/registration
token. A token-bearing handoff exists only in the owned app-private fixture and
host process memory; do not dump that file or request body.

The delivery test anonymously clones the public `octocat/Hello-World` repository
and pins an explicit no-credentials policy to its owned canonical path. It
requires unused Push Sync state and no unfinished maintenance job, then places a
one-day barrier ahead of all maintenance work so **no production relay call**
can occur. A real data-only FCM message selects that repository/actual upstream
branch with a deliberately false head hint. The actual background event worker
fetches Git state; the test checks HEAD/index preservation, opens the real
notification, verifies one up-to-date foreground result, and opens the repository
through the production route. The test restores exact preference snapshots,
finishes its activities, cancels its work/notification, deletes its credential
policy/workspace and SDK token. The backup exists in RAM: do not force-stop the
runner during this fixture. This proves live FCM + WorkManager + navigation;
it does not prove a deployed GitHub webhook relay or process-death recovery.


After an approved production relay deployment, run
`FirebasePushRegistrationTest#deployedRelayRegistersAndRemovesAnActualPersonalProjectAndroidToken`
with `-e liveRelay true`. This separate opt-in makes real register/status/unregister
calls using a fresh device secret, then verifies removal and deletes its SDK
token in finally. No GitHub installation is connected and no notification route
is retained. The ordinary live SDK registration test still requires only
`-e liveFirebase true` and never contacts the relay.


## Background Sync

`BackgroundSyncRunnerTest` runs real disposable Git repositories without Android.
It covers independent pull/push consent, included policies, branch/folder/remote/
credential/account binding, dirty/diverged preservation, network/power deferral,
revocation while queued and retained-commit retry after an upload failure.

`BackgroundSyncTest` uses UUID-prefixed preferences and owned local Git folders,
with a validator permitting only its exact local bare remote. Its scheduler
callbacks are counters; its WorkerFactory drives the real worker/backend classes
against that namespace without scheduling any global work. The native publishing,
review and reset confirmations are captured in dark mode at 160% local Compose
text density; global font settings are unchanged.

`BackgroundSyncSchedulerTest` separately exercises actual WorkManager scheduling
and the production Hilt worker. Confirm the dedicated GitSync AVD first. The test
requires globally inactive Background Sync and no unfinished work under its two
unique names. It backs up the exact catalog/background preference maps in RAM,
installs only an owned file-transport checkout/policy with explicit no-credentials
and local fixture author, checks periodic metadata, enqueues one actual check and
verifies remote acknowledgement/one-commit history. Finally it disables/cancels
its work, waits for the repository lease and restores/removes its exact fixtures.
Do not force-stop instrumentation while that in-memory backup is needed. It makes
no live GitHub request. This proves scheduling and the production worker, rather
than actual 15-minute OS timing or process-death recovery.

Build/install matching debug and AndroidTest APKs sequentially, stop scoped
Argent accessibility services, then run the two native test classes with
AndroidJUnitRunner on `emulator-5554`. `FileNavigationTest` additionally covers the
actual Settings → Background Sync route, activity recreation and hardware Back
with both modes off. Do not enable automatic publishing on an actual saved vault
to reproduce a synthetic fixture test.

## Protecting preferences across instrumentation crashes

Some production-route fixtures temporarily replace global debug-app catalog and
policy maps. Their ordinary in-memory `@After` backups do not survive a process
crash. Before any broad/global-fixture run, invoke
`FixtureSafetyTest#capturePrivateBaselineBeforeSuite` with
`-e privateFixtureBaseline capture`. After completion **or a crash**, invoke
`FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash` with
`-e privateFixtureBaseline restore`, and verify its authoritative success log.
The baseline must be restored before another capture; capture refuses to overwrite
an outstanding journal. Use only the dedicated GitSync debug AVD.

Prefer the protected runner after installing matching debug and AndroidTest APKs:

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --avd gitsync-paid-review-20261026
```

The serial in examples is illustrative: obtain the current serial for the named
AVD from device inventory and pass it explicitly after any restart. Serial ports
can be reassigned to other projects. The runner verifies the selected serial's
exact owned AVD identity and Android API before mutation, stops only the debug/test apps, runs the regression, and restores in a
`finally` block. `--classes` accepts explicit comma-separated classes/methods for
focused runs. `--raw-status` records each test's start/end status for diagnosis.
The runner reports verified restoration from its `finally` block even when the
original run times out. Its default overall timeout remains 900 seconds; run
smaller selections in separate protected invocations when a broad run exceeds
that limit. If an owned `force-stop` command outlasts its observation window, the
runner waits on that same process before restoration instead of killing the
command or skipping recovery. An actual command failure still requires recovery
of the existing snapshot before another capture. The capture requires automatic Background Sync and Push Sync to be
off, with no pending unregister operation. Logs contain ordinary test output;
preference values and recovery journals remain inside the target app.

The snapshot remains AES-GCM encrypted under an Android Keystore key, inside the
target app's private **no-backup** directory. It preserves typed app-owned
preference maps and is removed only after checked restoration of every map. A
failed restore retains it for retry. No preference/credential value or encrypted
journal is exported to the host. It does not back up Git working-copy files,
launcher state, clipboard contents or system settings; fixtures must continue to
own/clean their paths and avoid real remote mutations. After a timeout, stop the
owned debug process before recovery so a timed-out fixture cannot keep mutating
state; stop it again after restoration. Do not clear app data or uninstall while a
journal is needed.

`FixtureSafetyTest` includes two isolated synthetic round-trip/corrupt-ciphertext
checks. Select these by method when adding them to a regression; **do not run the
whole class** for coverage counts: maintenance methods skip without explicit
opt-in arguments and are not functional coverage.
`FixtureStateAuditTest` requires `-e fixtureAudit true` and emits only
catalog/fixture counts and ownership flags.
`removeOnlyTheOwnedInterruptedCallbackFixture` is a one-off guarded recovery for
the identified callback fixture; it cannot reconstruct an already lost in-memory
snapshot and is not a general catalog reset.


## Pull upstream and ignored-content Android fixtures

`SafePullDeviceTest` uses UUID-scoped app-private working copies and local bare
remotes with explicit no-credentials policies. It does not replace catalog,
account or feature-policy preferences. Its three cases exercise the production
JGit pull implementation on Android: configured upstream/release with an
unusable origin, ignored-content/index preservation followed by explicit retry,
and a branch switch during credential resolution without updating either local
branch. The protected runner includes this class by default. No real remote or
saved account is used; teardown removes only the owned workspace.


## Reviewed remote merge and conflict editor

`RemoteMergeScreenTest` uses real UUID-scoped Git repositories/local bare remotes
and the production screen/ViewModel/service. It covers review/cancel, local and
published results, 160% dark rendering, stale confirmation, same-commit push
retry, persistent conflicts, abandoned review and actionable uncommitted-edit
errors. `FileNavigationTest` additionally exercises the actual app/DI route,
recreation/Back, then remote merge through the production manual editor and
merge completion. `ConflictViewerLayoutTest` reproduces the production bounded
dialog/body nesting that previously measured an inner scrolling Text with
infinite height. Its regression keeps whole-file newline content intact.

After a process crash, the protected runner restores preference maps but does
not delete working copies. For an identified owned FileNavigation UUID, use
`--cleanup-navigation-id <exact-uuid>` with one explicit
`--classes com.bontecou.syncmd.FileNavigationTest#methodName`. Native cleanup
verifies the UUID, private parent, nonsymlink root/metadata, optional exact owned
catalog entry and local bare remote before removing only that fixture. Never
substitute a broad directory deletion, clear app data or uninstall to recover.


Merge screenshots use `CommittedScreenshot`: it waits for the focused app window
(including a separate native dialog) to commit a frame, then captures the device.
Copying a dialog semantics node on the cached Compose version can instead capture
the activity underneath. The 128-case broad run precedes this test-only helper;
the seven-case screen replay verifies the final captures without adding distinct
coverage. Local 160% density tests the main composition; a real system-font-scale
dialog/landscape/accessibility pass remains separate qualification.


## Repository selection and asynchronous ViewModel regressions

`RepositoryViewModelTest` runs twelve controlled-response cases on Android's real
Main dispatcher. Delayed responses deliberately outlive cancellation; barriers
and joined coroutine jobs prove stale completion/busy ordering. UUID-scoped
SampleRepository paths satisfy the production storage/lease policy, while fake
service responses avoid credentials and external network operations. The cases
cover read ownership, duplicate mutation gating, A → B → A callback identity,
lease-queued cancellation, captured drafts, changed conflicts, failed snapshots
and error acknowledgement. They do not certify every external Git/filesystem race.

`RepositoryOperationScreenTest` renders the production Vault screen and verifies
Pull is disabled while running, an automatic refresh retains its failure, and
Dismiss acknowledges it without another mutation. The existing conflict-dialog
regression additionally verifies disabled/re-enabled input with draft retention.
The production navigation regression waits for `git-merge-active` before scrolling
to loaded conflict controls, then still edits/stages/completes the real merge.
All fixtures clear their ViewModel scopes, release barriers, wait for jobs/leases
and remove only their owned private directories; they do not replace user policy
or account preferences. The protected runner includes both new classes.

`NavigationLifecycleTest` also covers transition-time forward and Back requests,
duplicate queued taps, and cancellation when the source is left (including a
synchronous leave-and-return). Requests wait for their own source entry to resume,
then leave the lifecycle dispatch turn before changing the back stack. The seven
lifecycle cases and both production Git navigation paths pass together. The navigation-phase
protected suite passed all 145 distinct checks. Callback automatic-return tests
wait for returned state and read delivery counters after main-thread completion:
DONE publishes the result before performing the opted-in synchronous return.
Exactly-once and no-retry assertions remain intact.


`HistoryViewModelTest` adds thirteen controlled-response checks for coherent
inventories, latest-read ownership, selection-bound callbacks, synchronous write
gates, lease-queued cancellation, positional stash identity, exact annotated-tag
identity, captured arguments, retained errors/drafts and failed-snapshot gating.
The production `FileNavigationTest#stashMessageAndAnnotatedSelectedTagUseTheProductionGitControls`
uses real local Git through the navigation shell: entered stash message, apply
without removal, optional tag annotation and exact selected-tag publication.
It verifies unchanged remote branch, unpublished other local tag, retained draft
contents and controls re-enabled after refresh. The protected default includes
both classes; no live remote or account is used by these cases.

The controlled initial-history case holds the repository lease before Git navigation,
checks visible disabled input, then releases the read. Tests wait for the header's
loaded-page marker and scroll the control into view again before asserting enabled:
late branch/change content can move the original item offscreen. Failure visibility
and frame-committed screenshot evidence distinguished that cached offscreen item
from an active model failure. Functional Git/ref assertions remain unchanged.

The history-phase protected suite passed 160 distinct native checks after
those additions (23 production navigation cases and 13 history-state cases).
Maintenance baseline capture/restore/audit and repeated focused runs add no
functional coverage. The final production screenshot waits for loaded state,
scrolls the tag into view and checks enabled controls before capture.


`BranchViewModelTest` includes thirteen cases using owned real-Git workspaces:
latest/coherent snapshots, full current/target identities, selection-bound
callbacks, queued cancellation, captured original creation path/HEAD, duplicate
gating, failed-refresh action readiness and retained checkout/LFS notices. It
includes the two earlier branch checks and releases barriers/cancels/joins jobs
before removing leased owned paths. The protected default now includes this class.
`FileNavigationTest#branchCreateSwitchMergeAndDeleteUseReviewedProductionControls`
exercises the real native branch form and rows, local creation/checkout/merge/delete,
foreground refresh after an owned external commit, retained working contents and
unchanged remote refs. Its screenshot waits for loaded/visible controls.

The current protected default passes **174 distinct checks** (24 production
navigation cases and 13 branch-state cases). It adds twelve new methods plus two
existing branch methods newly included since the 160-case history-phase default.
The final branch capture additionally waits for the merge dialog to disappear and
for native accessibility idle; a focused full workflow replay verifies that
screenshot-only change without adding distinct coverage. Product APKs are unchanged.


`BranchCheckoutDeviceTest` adds four real-Git checks in private and owned
Android/media workspaces: ignored file/directory/prefix collisions, author-time
branch changes and safe three-way deletion results. Cleanup leases only owned
`checkout-protection-UUID` roots and walks without following symbolic links.
`FileNavigationTest#ignoredCheckoutCollisionShowsItsPathAndExplicitRenameAllowsSafeRecovery`
uses production switch/merge dialogs and the file-browser rename flow, then
verifies successful checkout/merge and preserved private content and remote refs.
It captures the settled collision dialog after native idle. The maintenance
count-only audit reports both private and media checkout workspace counts without
exporting paths or preference values. These five focused checks pass together.

The checkout-phase protected default passes **179 distinct native checks**,
including 25 production navigation cases. Its five added methods increase the
prior 174-case default; focused reruns and maintenance audits do not add coverage.
Private baseline restoration and the zero private/media checkout-workspace and
recovery-journal count audit pass after the broad run.


`RepositoryStatusDeviceTest` verifies completion metadata in owned private and
Android/media workspaces, including reopen, relocation and rejected push. A
second check keeps a completed commit successful when optional display metadata
cannot be saved. Cleanup uses leased owned `status-metadata-UUID` roots and does
not follow symbolic links. The count-only audit includes private/media status
workspace counts. `FileNavigationTest#statusCardUsesRealHeadAndPersistsCompletedActivityAcrossRecreation`
checks actual production unknown state, foreground refresh without invented sync,
local commit, real short SHA/time and activity recreation. These three focused
checks pass; the default runner includes both classes.

The metadata-phase protected default passes **182 distinct checks** (26
production navigation cases, two status-storage cases), three more than the
179-case checkout-phase default. Final evidence capture scrolls to lazy item zero
so the whole status card is shown after recreation. A complete focused workflow
replay verifies this test-only framing change; product APK hashes stay unchanged.
Private baseline restoration and count-only zero-workspace/journal audit pass.


`RepositoryOverviewUiTest` covers old-path read suppression, pause/resume refresh,
no reread on ordinary recomposition, failed-read clearing, and light/dark RTL
large-text row wrapping. Its controlled reader never touches a catalog or policy.
`FileNavigationTest#repositoryAndVaultCardsRefreshRealMetadataAndClearUnavailableCopies`
uses the real list/vault and owned Git changes, unavailable/restored metadata and
activity recreation. It restores the owned metadata folder in finally and uses
the unmerged semantics tree for children of clickable repository cards.
`FolderPublicationDeviceTest#realLocalPushRecoversLostReplyAndKeepsTheSameCommit`
now checks absent completion time through lost/unconfirmed replies and a recorded
time only after live reconciliation/tracking. The protected default includes that
existing method and both new overview test paths. Six focused checks pass.

The overview-phase default passes **188 distinct native checks**, including 27
production navigation methods. It adds five new methods plus one preexisting
publication method newly included since the 182-case default. The final stress
fixture reserves system insets and fills its themed surface; a focused replay
passes both unchanged no-overflow checks after that test-only framing correction.
Product APK hashes match the broad run. Protected baseline restoration and the
count-only zero-workspace/recovery-journal audit pass.


The live-update phase adds two real-write lifecycle cases and catalog identity
checks to `RepositoryOverviewUiTest` (seven methods total). They verify updates
while visible, no unrelated-root reread, no reads while stopped, latest values
on resume, and saved names/remote owners without filesystem-derived ownership.
Owned `overview-live-UUID` roots and their parents are leased before no-follow
cleanup after lifecycle destruction; their count is included in maintenance audit.
`FileNavigationTest#visibleListVaultAndGitRefreshWritesAndUseSavedCatalogIdentity`
uses production navigation and real coordinated local writes while each screen
remains foreground. It checks optional metadata-save failure, subsequent durable
time, live branch/HEAD refresh and saved alias/owner. Eight focused checks pass.

The live-update protected default passes **192 distinct native checks**, including
28 production navigation methods and seven overview methods. Four new methods
increase the prior 188-case default. The baseline restores successfully, and the
count-only audit reports zero live-overview/status/checkout/callback workspaces
and recovery journals. No live remote, cloud service or release installation is
used by these cases; no complete cross-process/external-tool/device qualification
is implied by process-local invalidation tests.


`RemoteCredentialMigrationDeviceTest` adds eight owned private/media checks for
encrypted separate-endpoint migration, saved/account/public-policy precedence,
inactive recovery and targeted enablement, retained alternatives with explicit policy swaps, third-credential refusal, anonymous history defaults, config/credential/preference failures,
origin edits/public removal, relocation/AAD binding and queued connection changes.
It uses canonical working roots, private isolated auth preferences, joined model
jobs and leased no-follow cleanup. Maintenance audits include private/media
`url-migration-UUID` workspace counts. No credential or preference file is exported.
`FileNavigationTest#credentialUrlMigrationUsesTheProductionReviewWithoutRevealingSecrets`
uses the real review/return/reopen route, checks the unmerged tree never contains
fixture URL usernames/tokens, and verifies exact retained Git state and endpoints.
The default also includes all seventeen preexisting `RemoteConnectionTest` cases
to guard compatibility. The final focused set also reruns the stash/tag navigation scenario: all 27 cases pass. The first queued-fixture timeout
was a noncanonical-vs-canonical path comparison before mutation, corrected without
weakening its stale-write assertions.


## Logical rename conflicts

`RenameConflictTest` covers graph-verified three-path index grouping, all-path lookup,
side object/mode identity, complete text, binary whole-side bytes, chosen-name manual
resolution, unrelated staged/untracked retention, stale working/index revisions,
index-lock retention and link-target refusal. Real local branch and reviewed remote
merges now stop with an unchanged HEAD instead of committing both rename names;
remote publication remains blocked until resolution. Overlapping tracked names are
refused before working/index mutation. These are ten new core methods.

`FileNavigationTest#renamedConflictEditorChoosesIncomingFilenameAndCompletesTheMerge`
uses the actual app merge engine before navigating through the production editor,
selecting the incoming name, resolving text, retaining an unrelated working file
and committing both parents. The companion binary test checks whole-side actions
and the disabled manual editor against a persisted three-path index.
`ConflictViewerLayoutTest` adds filename wrapping, selection and draft retention at
160% font size in light/dark themes. Captures are synthetic, app-private evidence.
The default protected suite now contains 221 distinct checks; final results are
recorded in FEATURE_PARITY.md. Native fixture roots remain owned by the existing
navigation fixture cleanup and no preference or credential value is exported.

This does not qualify rename/modify/delete merge generation, replay provenance,
all overlapping/copy/ambiguous cases, symbolic-link/submodule resolution, multi-file
crash recovery, or all-device/release/accessibility behavior.

The final protected broad run passes all 221 checks. The first run's stash/tag
fixture observed the stash ref before its working-file reset; the corrected wait
uses the repository lease and checks both the ref and exact restored content,
without increasing its timeout or weakening its subsequent file/index checks.

## Single-sided renames and conflict write safeguards

`RenameDeleteConflictTest` checks both rename orientations, explicit keep/delete
resolution, binary bytes and refusal to publish an unresolved reviewed merge.
`RenameModifyConflictTest` checks independent and overlapping edits at the new
name, equal rename destinations, ignored destination protection, unchanged
binary sides, executable modes and exact local-remote publication. Ordinary
conflict integration tests cover replaced file and parent links, retaining the
index and unrelated file contents when resolution is refused.

Six additional `FileNavigationTest` methods exercise actual merge generation,
the production renamed-file editor, deletion explanations and disabled content
shortcuts for missing sides. `RenameStorageDeviceTest` verifies exact UTF-8
manual resolution and staged blobs on private and owned Android/media storage,
plus media rename/edit and deletion completion. Its UUID-scoped roots are
registered before setup and removed after the repository lease is released.
The protected default now contains **229 distinct native checks**, including
37 production-navigation methods. Final outcomes and evidence are recorded in
FEATURE_PARITY.md.

For a specifically identified interrupted navigation fixture, the runner's
`--cleanup-navigation-id` requires a canonical UUID and exactly one explicit
`FileNavigationTest#method`. The test helper verifies its private parent,
nonlinked paths, catalog ownership when present, and local origin pointing to
that same fixture's bare repository before removal. If the overview test was
interrupted while hiding `.git` as `.git-overview-owned`, it first verifies that
retained repository's origin and restores the metadata name. This is scoped
fixture recovery, not general working-copy recovery. The count-only audit now
includes navigation and private/media rename-storage roots.

The repository-action confirmation regression waits for the canceled dialog's
absence before reopening it, checks actionable controls, and reports only
state flags/counts and owned-ref equality on a completion timeout. It still
requires a real result, exact published commit/author/message and original file
preservation. The local-commit/push test waits for the push control to be enabled
after its diff refresh. No individual test timeout was increased.

Advanced replay provenance, ambiguous/overlapping renames,
symbolic-link/submodule resolution, multi-file crash transactions and broader
device/release/accessibility qualification remain open.

## Rename-aware replay

`RebaseRenameTest` adds 15 real-Git methods for rename/edit, rename/delete and
split rename replay, exact picked-parent provenance after an earlier commit is
rewritten, offline abort, continuation, single-parent persisted cherry picks,
invalid/linked metadata, initial ignored-file collisions and author-time changes
at Begin/Continue/Abort. Safe already-integrated upstreams retain an ignored
replacement without unnecessary checkout rejection. All fixtures use local bare
remotes and assert unchanged remote refs.

`RebaseScreenTest` now has 14 native methods, including the production filename
selector, text/name retention after a stale write, a filename-only discard
warning, ViewModel restart, explicit upstream deletion, offline abort and a
blocked ignored destination followed by a successful reviewed retry. Existing
binary, invalid-UTF-8, shared-media, empty-commit skip and duplicate-submit checks
remain included. The protected default now contains **243 distinct checks**.

The focused-dialog capture uses `CommittedScreenshot` after its actual window
frame commits. Copying the Compose root initially captured the activity below
the dialog, so those first images are not layout evidence. Light/dark captures
verify names, role labels and controls. The parent screen's 160% local density
does not propagate into the dialog window; large system-text acceptance remains
open. Device font settings are unchanged.

Rebase fixture teardown clears its ViewModels and acquires the repository lease
before removing its UUID-scoped private/media workspace. The count-only audit
includes those roots. The callback-grant managed-move fixture now joins its owned
ViewModel scope after clearing it, so a non-cancellable move finishes before
directory teardown. Its 15-second execution timeout is unchanged, and timeout
diagnostics include only state/ownership flags. The audit also counts its private
and media roots. No recovery journal or preference values leave the target app.

Multi-parent/external replay provenance, per-step checkout qualification,
recursive virtual bases and the other unfinished FEATURE_PARITY requirements
remain open. Final build and native outcomes are recorded there.

## Vault move recovery across app processes

`RepositoryMoveColdStartTest` exercises a real private-to-app-media relocation
with a synthetic GitHub account and a pending Obsidian connection. Its first
phase stops after credential persistence and before catalog publication. The
second phase requires a different Android process, recovers through the production
Settings ViewModel, completes the original companion request at the moved path,
checks one-time consumption and retained-copy isolation, and removes the fixture.

Use the same fresh UUID for both protected runner invocations, after installing
matching debug app/test APKs on the dedicated GitSync emulator:

```sh
python3 tools/testing/run_native_regression.py --move-fixture-id "$fixture_id" \
  --classes com.bontecou.syncmd.RepositoryMoveColdStartTest#prepareInterruptedPublication
python3 tools/testing/run_native_regression.py --move-fixture-id "$fixture_id" \
  --classes com.bontecou.syncmd.RepositoryMoveColdStartTest#recoverInFreshProcessAndCleanFixture
```

The runner stops the owned debug package between phases and protects the actual
app preferences with an encrypted baseline inside private no-backup storage.
Fixture account/connection preferences are separately named; no credential or
private handoff key is exported. Run both phases before the final fixture audit.

## Clone completion recovery

`CloneCompletionTest` uses a local bare Git remote and separately scoped synthetic
account/settings stores. Its eleven checks cover cancellation of completed clones, prevention of removed-entry resurrection, catalog-save failure/retry, current
folder and credential identities, repeated registration, restored Compose state,
late callbacks after leaving the screen, reset during non-cooperative transport,
and version-bound temporary credential cleanup. Catalog-save recovery checks that
the pending completion marker is removed, the companion can use the prepared
private connection, and restored UI does not renew it again. It is included in the default
protected regression selection.

For a readable recovery recording, `--clone-motion-capture` is accepted only with
`CloneCompletionTest#githubRegistrationFailureCanRetryWithoutRecloningOrDuplicatingTheCatalog`.
It adds a 1.5-second reading pause after each committed light/dark error screenshot.
Ordinary checks have no reading pauses. The clip is fixture UI evidence; it does
not establish release frame rate or full app navigation/accessibility acceptance.

## Interrupted Obsidian preparation

`ObsidianPreparationColdStartTest` has two preparation boundaries. Run either
`prepareBeforePublication` (durable private reservation, no public request yet)
or `prepareAfterPublication` (public request/descriptor and companion recipient,
private completion still pending), then `recoverAndClean` in a separate process.
Use one fresh UUID per two-phase workflow and pass it through
`run_native_regression.py --obsidian-preparation-fixture-id` with the exact method.
The protected runner stops the owned debug package between phases. Recovery runs
through Settings startup, keeps the same request ID and any published recipient,
completes one encrypted handoff, rejects replay and removes its scoped fixture.
No token or private handoff key is exported. Run both phases before the final audit.


## Interrupted clone publication

`ClonePublicationColdStartTest` uses one UUID-scoped private workspace and synthetic
account stores with a local bare Git remote. Run either `prepareStaged` (the real
SafeCloneService callback parks after the durable receipt and before rename) or
`preparePublished` (CloneViewModel has published the working copy, with no catalog
entry yet), then `recoverAndClean` with the same UUID:

```sh
python3 tools/testing/run_native_regression.py --clone-publication-fixture-id "$fixture_id" \
  --classes com.bontecou.syncmd.ClonePublicationColdStartTest#prepareStaged
python3 tools/testing/run_native_regression.py --clone-publication-fixture-id "$fixture_id" \
  --classes com.bontecou.syncmd.ClonePublicationColdStartTest#recoverAndClean
```

Use a fresh UUID for the other preparation boundary. The runner force-stops only
the owned debug package before baseline restoration, terminating the parked
worker without running its ordinary staging cleanup. Recovery requires a new PID,
uses production Settings startup, verifies original HEAD/index/working drafts,
retains one catalog entry/date and companion request, and restores the clone
ViewModel from its private saved ID without a second transport call. Teardown
removes only the UUID-scoped workspace and preference maps. Interrupted network
transfers before a complete receipt are outside this test. Do not omit the
recovery phase before final audit.

The protected runner stops both the owned debug app and its `.test` package before
capture and around restoration, releasing leftover instrumentation processes.
These workflows test app process death; do not load an older AVD snapshot between
phases. A snapshot can restore an older installed app and fixture state. If device
I/O stalls require a reboot, preserve the AVD data and cold-boot with snapshot
loading/saving disabled, then verify matching APKs and the count-only audit.


## Reviewing interrupted clones

`CloneRecoveryManagementTest` uses only UUID-scoped private workspaces, local Git
transport and separately named credential/settings stores. Its seven scenarios
cover damaged-entry isolation, confirmation Cancel/confirm, retained working
copies and connection revisions, retry after a destination conflict, stale review
rejection, symlink preservation, and exclusive ownership in both restored clone
ViewModels. It is included in the default protected selection. The runner refuses
baseline capture if any real-store recovery entry exists, including damaged ones.

For a recording, `--clone-recovery-motion` is accepted only with
`CloneRecoveryManagementTest#cancellingThenConfirmingDismissalKeepsBothFoldersAndSavedConnection`.
It adds one-second reading pauses after the two committed dialog captures. Actual
system-font stress is optional and must restore the exact original scale in a host
finally block; the ordinary suite uses local component density and does not change
device font scale. A recording must include the dialogs and final dismissal; a
clip that caps during instrumentation startup is incomplete evidence. Native
instrumentation owns UI automation, so the platform screen recorder can be used
without starting a competing UI automation backend. Stop only its exact owned PID,
finalize/copy the clip and remove its UUID-scoped device file.

## Clone catalog provenance

`CloneCatalogProvenanceDeviceTest` exercises catalog commit followed by failed
pending-marker cleanup or private-file acknowledgement, removal failure, durable
revocation followed by an unlink failure, explicit dismissal retry, fresh cloning
at a removed path, and corrupt-provenance isolation. Fixtures use separate private
workspaces and scoped preference files. A nonempty owned temporary directory
forces acknowledgement failure without a production fault hook. Permission
changes used for unlink failure affect only the owned private fixture directory
and are restored before cleanup.

Catalog registration and its provenance use the same preferences editor commit.
Removal/replacement revokes retained provenance in that commit; a pending marker
is cleared when no active publication still uses it. Dismissal commits revocation
before private metadata removal. A missing private publication cannot be replaced
by a late cached completion. Obsolete provenance is pruned only after that private
publication disappears. Corrupt retained provenance rejects that registration,
while unrelated catalog work remains available.

`CloneCatalogColdStartTest` requires one fresh UUID per two-phase workflow via
`--clone-catalog-fixture-id`. Run `prepareUnacknowledgedCatalog`, `preparePendingMarker` or
`prepareRemovedCatalog`, then `recoverCatalogAndClean` in a new process using the
same UUID. Recovery verifies the original catalog date or durable revocation,
HEAD/index/draft and credential revision. Do not load AVD snapshots or wipe data
between phases. Matching APKs and protected baseline restoration are required.

## App-language recreation

On API 33+, these `FileNavigationTest` methods exercise real package locale
changes through `LocaleManager.applicationLocales`, rather than a configured
resource context or a manually recreated activity:

- `actualLanguageChangesRecreateSettingsInEverySupportedLocaleAndKeepRepositorySelection`
  visits all declared non-English languages, requires a new resumed activity
  with the requested configuration, checks the localized Settings control and
  back arrow, and preserves the selected repository, catalog, working bytes and
  Git HEAD.
- `actualLanguageChangesKeepTheOpenEditorDraftAndSaveOnlyAfterExplicitAction`
  switches Spanish, Arabic and Turkish while editing a mixed-script document.
  It checks draft retention, English/Arabic paragraph directions and unchanged
  disk/HEAD before explicit Save, then returns through production navigation.

Use `run_native_regression.py --classes` with the fully qualified methods. The
methods restore the original package locale in `finally` and never set the
system locale. The protected suite's encrypted private snapshot also captures
that package locale on API 33+, so its separate-process restore can recover
from a killed instrumentation run. A failed restore retains the snapshot for
retry. Older snapshots without this optional field still restore preferences
without changing the locale. No preference values or credentials are exported.

`FixtureSafetyTest#privateSnapshotRestoresAppLanguageAcrossInstancesWithoutChangingTheSystemLanguage`
checks the locale-bearing snapshot; the existing typed-map and corrupt-snapshot
checks cover older preference-only payloads. Do not clear app data or uninstall
to recover a failed run. These tests verify the framework locale API and app
recreation; opening the system picker is a separate native navigation check,
and selecting a language by tapping the system picker is not automated here.

## Background launcher shortcuts

The shortcut review has an explicit **Run without opening GitSync.md** choice.
Existing schema-1 authorizations keep their foreground route; schema-2 records
carry background consent. Every launcher intent still carries only its opaque
shortcut ID. Background runs use a private, non-sticky `dataSync` service and a
bounded receipt store excluded from backup. A queued receipt is claimable only
in the process that reserved it. Claimed, completed, interrupted and pruned
receipts cannot be replayed. There is no WorkManager retry or boot scheduling.
The saved result is shown under Launcher shortcuts, including retained local
commits whose publication needs attention. Corrupt result history can be cleared
through a confirmation without changing shortcut authorization. Clearing results
is blocked while a run is active.

`RepositoryShortcutLauncherTest` approves and taps unique owned Pixel Launcher
icons on the dedicated API 35 emulator. Its background cases use the actual
service and local bare remotes for Status, Pull, Push, incoming-only Sync,
outgoing Sync and rejected-push retry. It closes MainActivity before each
background launch, checks that the launcher remains visible after completion,
then explicitly opens the production result review and checks there is no replay.
The retry case creates/removes only a fixture-owned remote ref lock and requires
one retained local commit with the same SHA after the next tap publishes it.
The legacy foreground launcher case remains in the class. Each case removes its
own icon and restores the protected catalog/authorization/result preferences.

`RepositoryShortcutRunStoreTest` covers single claim, redirection rejection,
bounded history with an active receipt, failed persistence, interruption and
corrupt-history recovery. `RepositoryActionsScreenTest` covers the checkbox,
review/cancel, precise retained-commit message and confirmed result reset. The
protected runner captures `repository_shortcut_runs` in its encrypted baseline.
Use matching debug/test APKs and the dedicated emulator; no real Git host is
contacted by these fixtures.

For the actual process boundary, run these phases sequentially:

```sh
python3 tools/testing/run_native_regression.py --raw-status \
  --classes com.bontecou.syncmd.RepositoryShortcutRunColdStartTest#prepareQueuedAndClaimedReceiptsBeforeProcessExit
python3 tools/testing/run_native_regression.py --raw-status \
  --classes com.bontecou.syncmd.RepositoryShortcutRunColdStartTest#recoverReceiptsInFreshProcessRejectReplayAndClean
```

The first phase leaves only two synthetic receipt records and an owned private
marker. The runner stops the process between phases. The second requires a
new PID, verifies both records show interruption and reject execution, then
removes only that fixture's preference map and marker. A leftover marker must
be recovered with the second phase before preparing another one. Never clear
app data or uninstall to recover a failed test. This proves receipt handoff,
not interruption inside a Git mutation. Android 28–34/36, other launchers,
notification-enabled behavior, wider storage/transports, active Git mutation
interruption and release accessibility/performance still need qualification.

### Background authorized callbacks

Authorized-link consent now supports `Run without opening GitSync.md`. Background
links disable automatic return; a completed notification returns the frozen
success/error response only after a user tap. Tokens and responses remain in
process memory. Durable execution receipts share the bounded private automation
result store and contain IDs/result enums only. Process loss cannot resume an
invocation or reconstruct a return payload. Existing foreground links retain
their previous behavior.

Run `RepositoryCallbackGrantTest`, `RepositoryCallbackScreenTest` and
`RepositoryCallbackTransportTest` through the protected runner. The production
transport cases use only owned local Git remotes and restore catalog, grants,
result preferences and synthetic credential bindings. They verify background
Status/Pull/Push/Sync without MainActivity, saved-result review, history-intent
rejection and foreground callback compatibility. The consent capture includes
light mode and dark mode at 160% local text size.

`RepositoryCallbackTransportTest#backgroundCompletionRespectsNotificationPermissionAndReturnsOnceWhenEnabled`
checks saved results when notifications are denied. To qualify notification return,
first record the current notification permission, then temporarily grant it on the
dedicated emulator using Argent's permission test setup. Run that selector with
permission granted and restore the original grant state afterward. In the granted
path it completes two invocations before opening either notification, requires two
independent notifications, taps both and verifies each exact SHA/change count and
caller state in a separate receiving process. It rejects return replay and verifies
that MainActivity was never created. A passing denied-permission execution alone
does not prove the notification-return branch.

Returns are kept in a bounded process-memory history of 24 entries. Pruning removes
the owned notification as well. Revoked/changed grants cannot return saved data;
expired/process-lost returns ask the user to review Repository actions. Cold-process
interruption during Git, busy/start-failure return delivery, other Android versions
and broader transport/device qualification remain separate acceptance work.

### Headless Pull All

`PullAllBackgroundStoreTest`, `PullAllScreenTest` and
`PullAllBackgroundTransportTest` cover the saved summary, foreground compatibility,
widget RemoteViews PendingIntent, real private service, result review and Stop.
The transport fixture replaces the catalog only with owned local Git repositories,
preserves dirty/index/remote state and restores original private preferences.
The suite's encrypted baseline also includes `pull_all_background_runs`.

Run the additional scoped API-35 checks with:

```sh
python3 tools/testing/run_pull_all_system_checks.py --cold
python3 tools/testing/run_pull_all_system_checks.py --tile
python3 tools/testing/run_pull_all_system_checks.py --large-text
```

Each prints its `/tmp/syncmd-pull-all-system-*/proof.json` and native log paths.
Cold checks execute queued and claimed-partial preparation/recovery in four separate
processes; they never execute Git or resume an old receipt. An interrupted cold
preparation must be recovered with the named recovery method before preparing again.

Use the wrapper for the tile case. It saves the original secure tile layout before
changing it and restores through `cmd statusbar set-tiles`, checking four stable
reads even after a failed native run. Its recovery JSON contains only system tile
configuration, not app preferences. The native case uses a temporary grid containing
only Internet and the debug app's tile so a similarly named release-app tile cannot
be activated. Directly writing `sysui_qs_tiles` raced System UI's cached grid in an
earlier diagnostic run; its original layout was recovered by the recorded hash.
The final supported-command restoration passes.

Large-text checks set the actual system font to 1.6 and restore its original value
in `finally`. They exercise production widget/result review and the clear-summary
controls; only three owned synthetic PNGs are retrieved. Other devices are untouched.

For notification qualification, temporarily grant the dedicated debug app's
notification permission with Argent, record the grant and run
`PullAllBackgroundTransportTest#actualWidgetPendingIntentRunsTheServiceWithoutMainAndReviewDoesNotPullAgain`.
Its granted branch verifies counts and attention names in the real notification,
then taps a clickable native child after expanding a system group if necessary.
Restore the original permission afterward. A denied-permission pass verifies private
review routing and saved summaries, but does not prove notification display.

The summary stores counts and up to three attention names, matching the iOS shortcut
summary shape. It contains no repository paths or credentials. Admission is consumed
before Git and the service retains its slot until its job ends, including cleanup.
Queued/claimed receipts from another process are interrupted and cannot execute.
Stop PendingIntents are immutable and use distinct per-run identities. Clearing a
summary is explicit and cannot clear an active run.

`concurrentSummaryRefreshCannotTurnHomeReviewIntoAnotherForegroundPull` controls
out-of-order reads: Home must decide from its fresh snapshot even while a newer UI
refresh is pending. The prior state-based decision reproducibly started a foreground
run; the corrected decision leaves Git untouched. The protected runner now rejects
zero-test selections, and still restores the encrypted baseline on that failure.

Launcher-hosted widget placement/resizing, older/newer Android versions, actual
process loss inside a Git mutation, wider transports/storage and release/performance/
screen-reader qualification remain separate acceptance work.

## Background callback rejection checks

`RepositoryCallbackTransportTest#busyBackgroundCallbackReturnsItsOwnErrorWithoutDisturbingTheRunningAction`
holds a real repository lease while an exported status callback waits in the
production service. A second exported push callback must produce its own `busy`
response once, preserve HEAD/index/draft/remote, and leave the first request
running. With notifications granted, the test taps the actual notification and
checks the separate receiver process; otherwise it consumes the private return
boundary directly. It also opens the production saved-result card and checks
the busy reason. Neither branch changes notification permission.

After installing matching debug/test APKs on the dedicated API 35 emulator:

```sh
python3 tools/testing/run_callback_system_checks.py
```

This runs that check with actual system font scale 1.6, captures the synthetic
busy-result card, and restores the original font setting even if the native
check fails. Evidence and restoration proof are written to a new directory
under `/tmp/syncmd-callback-system-*`. Inspect the capture before claiming visual
qualification. Only one instrumentation owner may use the emulator at a time.

`RepositoryCallbackGrantTest` separately injects a failed platform service start
and damaged receipt storage: no dispatch retry, no Git mutation, exact one-shot
error return and preserved authorization/corrupt evidence. The receipt-store
suite checks old schema bytes, durable rejection reasons, retained service
ownership through job completion, and distinct Stop intent identities.

For a launcher fixture interrupted during cleanup, the protected runner accepts
`--cleanup-shortcut-id <exact-owned-UUID>` with one explicit
`RepositoryShortcutLauncherTest#method` selector. Establish ownership from that
run's evidence first; labels alone are insufficient. The fixture checks the
private entry component, removes only that ID through launcher accessibility,
and waits for the pinned record to disappear. The Move-item transition must
settle before resolving the Remove control.


## Localized first-run tour and URL entry

After installing matching debug/test APKs, run these modes sequentially on the
dedicated emulator:

```sh
python3 tools/testing/run_tour_system_checks.py
python3 tools/testing/run_tour_system_checks.py --clone-entry
```

Each mode applies actual system font scale 1.6 and restores the original value
in `finally`. The protected native runner retains preference/locale recovery
inside the app. The first mode changes the real application language while
mid-tour in each of 25 locales, checks all four pages, numeric progress and all
five setup controls, validates full text bounds and header/Back separation,
and checks dark Arabic/Hebrew. Catalog/account/selection preferences stay
unchanged; intentional theme changes may persist their existing interval/debug
defaults and all preferences are restored afterward. The second mode uses the
localized URL choice in each locale, checks the actual destination title and
localized accessible Back control, then proves Back cannot re-enter the
completed tour. It does not submit clones or contact a Git server.

Evidence is written under a fresh `/tmp/syncmd-tour-system-*` directory with
native logs, original/restored font scale and synthetic screenshots. Inspect
the captures before claiming visual qualification. The existing AppGuide,
RemoteConnection, HistoryClone and Localization native suites separately
cover setup navigation, persistence failure, real local clone publication,
credential handling and source/resource formatting. These checks do not
replace linguistic, screen-reader, physical-device or release performance QA.

## Background callback process loss during push and result return

Install matching debug/test APKs, then run:

```sh
python3 tools/testing/run_callback_interruption_checks.py
```

Only the dedicated API 35 GitSync debug emulator is accepted. Automatic
Background/Push Sync must be disabled and clone recovery must be empty. The
controller captures original preferences and app locales in a UUID-owned,
AES-GCM snapshot inside private no-backup storage. A callback bearer handoff
also stays inside the app; no preference, credential or token is exported.
A pre-existing private baseline prevents a new capture.

The owned loopback HTTPS fixture pauses a real Git receive-pack request before
publication or after the remote accepts it but before acknowledgement. A third
case waits for the production service to save completion before killing the
app. Each case uses the exported authorized link and actual private background
service. The controller waits for a verified boundary, force-stops only the
debug app, observes the original instrumentation handle terminate and starts
recovery in a different PID. Preparation ends in an **expected process crash**;
that crash is not counted as a passing JUnit case.

Fresh-process checks cover interrupted/completed saved review, exact local
HEAD/index/draft bytes, no startup replay, rejection of the old execution ID,
expired stale result return and a fresh explicit invocation of the same grant.
The explicit retry publishes the existing commit when necessary. If the
remote already has it, Push returns the iOS-compatible `no_changes` error
without another commit or receive-pack POST. Each remote ends with exactly
the initial commit and one callback commit; `git fsck` and zero unexpected
Authorization headers are checked independently on the host.

The fixture trusts only its temporary loopback certificate in the test process;
hostname verification remains enabled. It uses no GitHub account or external
Git service. Original preference/locale maps are restored inside the app, owned
workspaces and callback authorization are removed, and the exact owned adb
reverse rule is removed. Fixture servers stop; their temporary repositories and
logs remain for review. Do not run another UI/instrumentation owner concurrently.

Evidence and the recovery checkpoint are written to a new directory under
`/tmp/syncmd-callback-interruption-*`. To retry cleanup after a controller is
interrupted:

```sh
python3 tools/testing/run_callback_interruption_checks.py --recover /tmp/syncmd-callback-interruption-EXACT_DIRECTORY
```

Use the exact directory printed by that run. Its controller lock prevents
concurrent recovery. Recovery uses the recorded opaque fixture UUID and only
that encrypted snapshot and reverse rule; do not clear app data or uninstall.
`--mode before-remote --stage-recovery` deliberately leaves one owned fixture
pending so this recovery command can be verified immediately. That staging
mode is a harness-recovery check, not a successful product recovery scenario.

These boundaries do not qualify interruption inside local index/ref replacement,
all external writers, every OS/provider or physical-device behavior. No automatic
retry, force push or duplicate-commit recovery is introduced.

## Localized clone authentication, SSH and folder consent

After installing matching debug/test APKs on the dedicated API 35 emulator:

```sh
python3 tools/testing/run_clone_language_checks.py
```

The wrapper temporarily selects actual 160% system text and restores the original
scale in `finally`. The protected runner keeps preference/locale recovery inside
the app. `CloneLanguageTest` selects each real Android application language,
launches a fresh activity, and renders the production URL-clone form using
isolated preferences, fake accounts and a clone transport that fails if invoked.
Each of 25 locales covers fresh, saved-connection and disconnected-GitHub-account
states (75 form states in one native case). Text-layout assertions check labels,
branch guidance, credential/address scope, SSH examples and fingerprint guidance;
key visibility returns to masked semantics. The folder path checks reconnection,
app-storage/default choices and the native permission explanation. It cancels
that explanation without opening Settings or granting storage access. The shared
storage state must already be denied/default. Notifications remain unchanged.

The fixture uses actual activity locales because a context-only locale wrapper
leaves Compose's separate dialog window using the activity's language. It checks
system text scale rather than substituting a local density. Arabic and Hebrew
use dark RTL presentation. Six synthetic consent/SSH screenshots are saved under
a fresh `/tmp/syncmd-clone-language-*` directory and need visual inspection.
`FixtureStateAuditTest` includes clone-language workspace/preference counters.
No network clone, physical-device, screen-reader, linguistic-quality or release
performance qualification is implied by this case.

## Long paths, file dialogs and landscape keyboard

Use Argent's device inventory to find `gitsync-paid-review-20261026` before
running or installing anything. Serial numbers can change after an emulator
restarts; another project may occupy a serial from an earlier log. Both the
shared protected runner and this controller accept `--device` and check the
exact AVD name before stopping apps or changing settings. This evidence run
used `emulator-5556` after cold recovery (it originally used `5554`).

```sh
python3 tools/testing/run_files_layout_checks.py --device emulator-5556
```

The controller requires the dedicated API 35 AVD and at least 1 GiB of free host
storage. It sets actual system font scale 1.6, runs the production navigation
case, restores the exact previous scale, and saves proof even if restoration
fails. Its ADB operations have bounded deadlines. The protected runner keeps
preference recovery encrypted inside the target app. The native case traverses
eight long directory names, inspects the complete path using horizontal scroll,
edits Unicode text, rotates, opens and dismisses the real keyboard, reviews
unsaved changes, changes theme, renames, cancels reload/delete, then explicitly
saves. It checks visible editing space and complete text-line height, reachable
48 dp recovery controls, unchanged on-disk contents before save/cancellation,
retained drafts across recreation and unchanged Git HEAD/index. Eight synthetic
captures cover portrait, landscape, keyboard, dark mode and file dialogs.
Native accessibility-idle and frame-commit gates avoid rotation/dismissal frames
being presented as steady-state captures.

`--cleanup-navigation-id` is restricted to an exact owned interrupted UUID.
Native setup verifies the canonical workspace, repository origin and catalog
ownership before removing that fixture. Never use an arbitrary label or delete
app data to recover a test. If the emulator cannot respond, preserve userdata,
stop it cleanly, recover the encrypted baseline with
`FixtureSafetyTest#restorePrivateBaselineAfterSuiteOrCrash` and the explicit
`privateFixtureBaseline=restore` argument before another capture/run. The
controller's proof records whether font restoration also needs recovery.
`FixtureStateAuditTest` includes private `file-ui-` cache and
`file-editor-test-` media counters alongside the navigation/recovery counters.
These checks are scoped to the API 35 debug app; they do not establish physical
keyboard, all accessibility-service, other-provider/API or release-performance
qualification.

## GitHub account request ownership

On the current serial for the dedicated GitSync AVD, run:

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --classes com.bontecou.syncmd.GitHubViewModelTest,com.bontecou.syncmd.GitHubAccountsTest,com.bontecou.syncmd.GitHubSessionTest --raw-status
```

The final cohort contains 32 cases: eight ViewModel, 15 account/author/native
control and nine encrypted-session cases. The ViewModel cases use the real
Android auth manager with isolated encrypted preference namespaces and a fake
GitHub client. Controlled responses remain held while an external account is
selected/removed, or while the same account is reselected. The new account must
load and the busy state must settle before releasing the old response. A
post-acceptance case preserves the already validated account while rejecting
its late email/repository publication after a browsing switch. Existing cases
retain successful PAT loading and old-response/sign-out protection. No real
GitHub sign-in or external push occurs.

The ViewModel fixture clears/commits its own preference namespaces, deletes
those files and verifies empty state. The explicit fixture audit also counts
GitHub account workspaces and nonempty/backed-up preference files matching only
the three owned GitHub test prefixes. Native preference recovery remains inside
the app; the audit prints counters, never values.

## Queued repository author saves

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --classes com.bontecou.syncmd.AuthorRequestOwnershipTest --raw-status
```

These four native checks exercise the production `AuthorViewModel`, real Git
config writes and the shared repository coordinator. A held repository lease
allows a repository switch or same-repository reload to supersede a queued save;
releasing it must leave config, index, HEAD and the working draft unchanged.
A current save still succeeds and suppresses duplicate submission. An external
config change while waiting rejects the save and preserves the newer config.
Fixtures use private UUID-scoped workspaces/preferences and fake identities,
clear the ViewModels, join workers and delete only their own state. The explicit
fixture audit counts both workspace and preference remnants for this prefix.

## Account and author language checks

After installing matching debug/test APKs on the dedicated GitSync AVD:

```sh
python3 tools/testing/run_account_language_checks.py --device emulator-5556
```

The controller validates the AVD and API identity before changing settings,
requires 1 GiB of host free space, applies actual 160% system text, runs the
protected fixture, and restores the prior font setting in `finally`. The native
fixture changes the real per-app locale and recreates its activity for all 25
languages. It exercises account switching, a nameless-account removal dialog,
Cancel and confirmed removal, the empty state and add/back actions, and author
config-conflict reload followed by a real config save. Text-layout checks cover
translated titles and explanatory text; screenshot samples cover Arabic,
German, Finnish, Japanese and Ukrainian in alternating light/dark themes.
Only fake accounts and UUID-scoped local repositories/preferences are used.
The system locale, original app locale and private encrypted baseline are
restored; no live GitHub request or external push is made. This is separate
from human linguistic review and release motion/accessibility qualification.

## Completed-clone receipt retention

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --classes com.bontecou.syncmd.CloneRecoveryManagementTest,com.bontecou.syncmd.CloneCompletionTest,com.bontecou.syncmd.CloneCatalogProvenanceDeviceTest,com.bontecou.syncmd.RemoteConnectionTest,com.bontecou.syncmd.GitHubAccountsTest --raw-status
```

The clone-recovery class contains 13 cases, including six boundary checks for
post-receipt failure/cancellation, encrypted binding retention, unrelated/stale
credential receipts, and disposal of both clone ViewModels. The cohort contains
63 distinct native cases. Real local Git staging, Android credential storage,
private receipts and Settings startup recovery are used; transports/accounts are
synthetic. Recovery must retain the index and post-receipt draft and register the
same completed clone without a second network attempt. Ordinary connection edits
keep rollback behavior, and explicit clone Cancel/reset remains separate from
screen-owner disposal. Core receipt/cleanup tests also exercise cancellation and
unavailable ownership checks. Transfers interrupted before any completed-clone
receipt remain a separate pending recovery requirement.

## Clone completion with missing LFS file contents

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --classes com.bontecou.syncmd.CloneCompletionTest,com.bontecou.syncmd.CloneRecoveryManagementTest,com.bontecou.syncmd.CloneCatalogProvenanceDeviceTest,com.bontecou.syncmd.RemoteConnectionTest,com.bontecou.syncmd.GitHubAccountsTest,com.bontecou.syncmd.LfsScreenTest,com.bontecou.syncmd.LocalizationTest --raw-status
```

This cohort contains 78 distinct native cases. The two new completion cases
require an acknowledgement before normal navigation, or send the completed
repository to the production LFS screen. Cached hydration must preserve HEAD,
index object IDs, credential version and a clean production status, with exactly
one Git clone. The separate core HTTPS cases exercise actual corrupt-download
and rejected-redirect responses, hydration retry and concurrent local edits.
Malformed batch responses still fail hydration without replacing pointers or
publishing corrupt cache data; their failure no longer erases completed Git
history. Status assertions use the app's LFS-aware service; raw JGit without its
in-process filters compares hydrated bytes directly to the pointer index.

Warnings are derived from current LFS inspection after checked registration,
rather than a separate persisted warning flag. Review/Continue delivery stays
owned by the visible resumed route. The clone header reflects current state and
uses wrapping translated text. Screenshots sample light and dark/enlarged-density
warning states; they are not a complete release motion/accessibility audit.

## Existing-branch upstream editor

```sh
python3 tools/testing/run_native_regression.py --device emulator-5556 --classes com.bontecou.syncmd.UpstreamScreenTest,com.bontecou.syncmd.RemoteConnectionTest,com.bontecou.syncmd.GitHubAccountsTest,com.bontecou.syncmd.AuthorRequestOwnershipTest,com.bontecou.syncmd.LocalizationTest,com.bontecou.syncmd.SafePullDeviceTest,com.bontecou.syncmd.RemoteCredentialMigrationDeviceTest --raw-status
```

The 58-case cohort includes four upstream checks: the production Connection →
Upstream entry and checked config save, repository switching while a save waits
for its lease, same-repository reload invalidation, and detached-HEAD read-only
state. A 109-character branch value is retained without changing HEAD, index,
working draft, endpoint URLs or encrypted credential revision. The UI fixture
waits for the connection load before finding its navigation control. Captures
cover light and dark/enlarged-density forms; they do not claim a full release
motion audit or an actual system font-setting matrix.

`RepositoryUpstreamTest` also covers config-only persistence, stale config/branch,
invalid targets, absent fetch mappings, external config locks and active Git
operations. A real file-remote case changes the tracking remote/branch, then
proves the existing pull runtime fetches and fast-forwards the chosen branch
without checking out another local branch. The editor itself performs no fetch
or credential update; existing endpoint/account guards still apply to later
network operations. Upstream fixtures are private UUID-scoped workspaces and
preferences and have explicit cleanup-audit counters.


## Explicit Android API qualification

The protected native runner and Pull All system wrapper accept `--device` and
`--avd`. Both verify the exact owned AVD name and its API level before stopping
app processes or changing state. The allowed identities are `gitsync-parity-api28-20261031` (API 28),
`gitsync-parity-api34-20261031` (API 34), the existing
`gitsync-paid-review-20261026` (API 35), and
`gitsync-parity-api36-20261031` (API 36). Serials are assigned at boot; confirm the
current serial with the device inventory and pass it explicitly. Physical
phones and other projects' emulators are rejected.

For example, after installing matching debug and test APKs on the API 36 AVD:

```sh
python3 tools/testing/run_native_regression.py --device emulator-5558 --avd gitsync-parity-api36-20261031 --classes com.bontecou.syncmd.RemoteConnectionTest --raw-status
python3 tools/testing/run_pull_all_system_checks.py --device emulator-5558 --avd gitsync-parity-api36-20261031 --tile
python3 tools/testing/run_pull_all_system_checks.py --device emulator-5558 --avd gitsync-parity-api36-20261031 --large-text
python3 tools/testing/run_pull_all_system_checks.py --device emulator-5558 --avd gitsync-parity-api36-20261031 --cold
```

Private preference baselines remain encrypted inside the target app and are
restored in `finally`; no credential/preference export is needed. The system
wrapper additionally restores the original Quick Settings grid or font scale.
The cold check deliberately uses separate app processes for queued/claimed
summary recovery and rejects replay. These checks qualify their exact selected
flows on that API; they do not prove OS periodic timing, real cloud delivery,
other API levels, or release performance.


## Rebase locale and recreation checks

`run_rebase_language_checks.py` checks the production rebase screen against real,
owned divergent Git repositories in all 25 app locales at actual 160% system
text. It reviews/cancels rewriting, checks conflict labels, retains an unsaved
draft through real activity recreation with the activity-owned ViewModel, and
checks skip/abort confirmations before restoring the original local commit.
The local and bare-remote refs, index and working bytes are checked throughout;
no external repository or credential is used.

```sh
python3 tools/testing/run_rebase_language_checks.py --device emulator-5556 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs first. The wrapper uses the shared AVD/API guard
and protected native runner. Font scale and app locales are additionally
restored by the host's finally blocks, including after native interruption.
Six committed-window captures cover Arabic, German and Japanese confirmation
and editor views. The native log records every completed locale, and an
incomplete locale matrix fails the wrapper. Resource/format checks also cover
translated deletion choices and checkout recovery; those alternate layouts,
dynamic error text and human linguistic review still need broader qualification.

The rebase wrapper also runs `RebaseQuantityTest`: 26 locales including English,
16 quantities per locale (416 rendered states), independently varied local and
upstream counts, and hidden zero extra paths. Expected text uses unescaped
source forms and ICU plural rules rather than generated Android XML. Production
components must render exact locale-formatted counts without text overflow at
actual 160% system text. The real repository workflow separately asserts its
reviewed 1/1 counts. Both locale matrices must be complete for host success.

The rebase locale workflow also triggers real dirty-working-tree,
missing-upstream, detached-HEAD and active-merge planning failures before the successful review.
Each rejection must retain index, config, working bytes and both local/remote
HEADs and render its typed recovery in the active app language without overflow.
Core tests also cover local upstream config and dirty state after a prior review;
these error identities do not parse English exception messages.

The rebase app-language wrapper accepts only API 33+ AVDs because it exercises
Android's per-app LocaleManager. Minimum-version qualification uses the separate
API 28 AVD and explicit compatible test selections. Runtime shared-storage
permission on API 28/29 is distinct from the API 30+ all-files app-op; do not
reuse an app-op grant as evidence that legacy storage access was tested.


`run_legacy_storage_checks.py` runs the API 28 storage permission lifecycle with
an exact owned-device guard. It verifies initial denial, grants only the declared
read/write runtime permissions, runs actual folder selection/storage/move tests,
then verifies revoked denial and restores the original grant/flag state in finally.
`--picker-only` narrows the granted phase to the real native folder-picker case.
Only default permission flags on a fixture AVD are accepted. The API 28 package
manager can omit zero-flag and default-denied rows, which the metadata reader
normalizes without exporting preferences or credentials.

```sh
python3 tools/testing/run_legacy_storage_checks.py --device emulator-5554 --avd gitsync-parity-api28-20261031 --picker-only
python3 tools/testing/run_legacy_storage_checks.py --device emulator-5554 --avd gitsync-parity-api28-20261031
```

Release the inspection helper for only the selected emulator before native
UiAutomator instrumentation; both require exclusive UiAutomation ownership.
The fixture picker handles the observed SELECT/direct-result and Use this
folder/Allow flows while requiring actual result delivery and the caller's exact
persisted-folder checks. It does not replace native permission dialogs with a
mock grant. Large-text LFS tests scroll the lazy list to an explicit target and
assert it is displayed before physical taps. Concurrent endpoint-review tests
wait for both owned workers to enter verification before approving or rejecting
their shared prompt; submission alone does not establish concurrent arrival.


### Storage language workflow

After installing matching debug/test APKs, run:

```sh
python3 tools/testing/run_storage_language_checks.py --device emulator-5556 --avd gitsync-paid-review-20261026
```

The guarded API 33+ wrapper checks the production storage screen in all 25
non-English app languages at actual 160% system text. It exercises app-storage
selection and retained success notices across activity recreation, checks title
and text overflow, left-to-right path paragraph direction and the 48dp Back target, and restores the initial font/app
language even after interruption. Private preferences remain inside the protected
runner. It does not grant shared-storage permission or select an external folder;
the separate native storage/picker cohort covers that behavior.


### File browser/editor languages

```sh
python3 tools/testing/run_file_language_checks.py --device emulator-5556 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs first. This guarded API 33+ wrapper checks the
production file components in 25 app languages at actual 160% system text. The
owned local Git fixtures exercise creation/cancellation, folder labels, retained
mixed-script drafts, activity recreation, rename, canceled reload/delete,
explicit save, canceled browser rename and hidden-file toggles. HEAD/index and
untouched hidden bytes are checked throughout. Font/app-language restoration is
host-guarded in finally; preference baselines stay inside the protected runner.
No external folder grants, network repository or real user file is used.
The file action titles must fit without ellipsis. Unsaved/reload/delete dialogs
keep the exact filename separately; rename retains the original name while the
editable name changes. The native long-path test also measures complete English
action titles across its portrait/landscape sequence at 160% system text.


### File recovery languages

```sh
python3 tools/testing/run_file_error_language_checks.py --device emulator-5556 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs first. This guarded API 33+ wrapper runs one
native method with 16 real recovery states in each of 25 app languages at actual
160% system text. It exercises binary, invalid UTF-8 and oversized content,
external-change save/delete guards, retained drafts and errors across activity
recreation, duplicate/invalid names, missing files/folders, Git metadata and
symlink protection, an overlong-name fallback, and denied read/write access.
Permission changes affect only an owned private fixture folder and are restored
in a native finally block before cleanup. Recovery text must match the current
locale and render without text overflow; scrollable paragraphs retain access to
both ends. The fixture checks relevant disk bytes, folder inventory and exact
HEAD/index/config preservation. Three synthetic recovery captures are copied.
Font/app language restoration is host-guarded; private preference baselines stay
inside the protected runner. This does not qualify other API/provider/device or
window configurations, screen readers, release performance or linguistic quality.


### Modern shared-storage permission lifecycle

```sh
python3 tools/testing/run_modern_storage_checks.py --device emulator-5558 --avd gitsync-parity-api34-20261031
```

Confirm the current serial and install matching debug/test APKs first. The runner
accepts only the exact owned API 30+ identities, and was verified on the dedicated
Android 14/API 34 phone AVD. It requires empty owned storage-fixture namespaces;
it aborts on unknown leftovers rather than deleting them. It runs initial denial,
then the 30 native location/move cases with shared-storage access enabled, then
revokes access and checks denial again. Native folder selection must return real
persisted read/write access; relocation checks include real private/shared files,
Git state, credentials and retained-copy recovery. Skipped, assumption-failed and
failed native case statuses are rejected explicitly.

A host finally block restores the original app-op mode and stops only debug/test
processes. Font, app locale (API 33+), notification flags, rotation, eight owned
namespace counts and the 54-counter audit must match their required final state.
Private preference baselines stay in the protected runner. The API 34 qualification
also ran the established Git/navigation and background/push cohorts separately;
this storage command alone does not establish those results, other APIs/providers,
physical/removable media, live FCM/webhook delivery or release acceptance.


### Live FCM after app process loss

```sh
python3 tools/testing/push_cold_live_delivery.py --live-firebase --process-exit uid-signal --device emulator-5556 --avd gitsync-paid-review-20261026
```

This opt-in test uses only the approved personal Firebase project
`gitsync-md-android-20261028` and `bontecouc@gmail.com`. Matching debug/test APKs,
a current personal gcloud credential and the emulator's SQLite CLI are required.
The sender rejects credential-file/access-token/impersonation overrides and never
falls back to a different identity. It reads only generated fixture delivery
addresses into memory; OAuth/FCM tokens and private preference/credential/recovery
values are not written to host logs or repository files.

The harness first captures an encrypted private baseline. When it reports
`Waiting for notification pre-grant`, use `settings-permissions` on the exact
owned serial with action `grant`, permission `notifications` and bundle ID
`md.gitsync.md.debug`. Its finally block restores the original grant and mutable
permission flags even if the live test fails. The permission wait is bounded;
it must not be confused with permission approval or an automatic consent toggle.
The native preparation requires Background/Push Sync off and no unfinished
WorkManager requests before installing the owned fixture.

Preparation anonymously clones public `octocat/Hello-World`, rewinds only that
owned checkout to a parent commit and records exact private hashes. A persistent
one-day maintenance barrier prevents production relay registration/status calls,
including after app startup or token callbacks. It obtains an owned FCM address
without enabling SDK auto-init, temporarily installs one private catalog entry,
and persists its ownership journal and expected event ID.

The harness launches the app normally, backgrounds it and terminates only its
owned app UID's PID. `cached-kill` is also available, but Android may keep a bound
process alive; that mode fails before sending if absence is not observed.
`uid-signal` sends SIGTERM through `run-as`, not to the emulator. Both modes
require actual app-process absence and a non-force-stopped package before the
personal FCM send. Force-stop is deliberately unsuitable because it suppresses
FCM until user launch.

While no test instrumentation runs, the host observes only its event's scalar
WorkManager state using read-only on-device SQLite. It requires success, a new
PID, an existing owned notification and a process-start record naming a recognized
Firebase receiver/service. Only then does verification instrumentation check the
persisted finished event, real HEAD fast-forward and changed index, clean working
tree, preserved config, ignored false SHA hint and never-run relay barrier.
Cleanup cancels owned work/notifications, deletes the SDK token and owned files,
then restores the private baseline and original device metadata. A missing owned
journal is a no-op; retry after baseline restoration refuses new Push consent.

This qualifies a controlled app-process-loss case on the tested emulator. It
does not exercise GitHub App linking/webhooks, Cloudflare relay delivery, reboot,
Doze/OEM policies, every low-memory cause, other APIs or notification-tap UI in
this cold run. The separate warm live test covers the notification tap. Never
export the private address/journal/baseline or use this against a real user vault.


### Real Android browser GitHub App linking

```sh
python3 tools/testing/push_link_live.py --live-relay --device emulator-5556 --avd gitsync-paid-review-20261026
```

This explicit production-relay test requires matching debug/test APKs, the owned
API 33+ emulator, automatic Background/Push Sync off, no pending clone recovery
and no unfinished WorkManager jobs. It uses the existing personal GitHub App
installation for `CodyBontecou` (installation `160612170`); it neither installs
the App nor changes its repository permissions. The Firebase SDK must point to
`gitsync-md-android-20261028` with auto-init initially off.

After encrypted private-baseline capture, pre-grant notifications only when the
harness prints its setup wait, using the permission-setup tool on this exact
serial/package. Preparation generates a fresh encrypted device identity, installs
an empty temporary repository inventory and a one-day maintenance barrier, then
uses the production backend to opt in, register, request link authorization and
open the real Android Custom Tab. This does enable SDK auto-init for the owned
fixture. No generated token, relay secret, authorization URL or callback state
is exported to host evidence. Complete GitHub login in the emulator browser as
`CodyBontecou`; keep passwords and verification codes out of chat/logs.

The host waits up to ten minutes (`--link-wait-seconds` can change this) for the
app's linked-account screen before starting inspection instrumentation. It never
reads browser fields or reconstructs an OAuth callback. Native verification then
requires the persisted pending state to have been consumed by the actual app,
checks the exact personal installation/status and unlinks only this device.
The maintenance barrier must remain enqueued with zero attempts. A timeout or
sign-in screen is not a passed linking test.

Finally, cleanup unregisters only the fresh relay device and verifies status 404,
deletes its SDK token, encrypted owned identity, work and private fixture files,
then restores the private baseline and exact notification grant/flags and device
metadata. The owned encrypted identity/journal remain available for retry if
remote cleanup fails, including after baseline restoration. Pending server OAuth
state expires normally; the absent device prevents a later callback from linking.
The browser's login session is not cleared. This test alone does not qualify a
GitHub webhook, relay-to-FCM delivery or repository synchronization.

### Push Sync at large system text in every app language

Build and install matching debug/app-test APKs with `-r` (without `-g`). Use the
current serial of an explicitly owned API 33+ GitSync AVD:

```sh
python3 tools/testing/run_push_language_checks.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
```

Both identity arguments are mandatory; the wrapper verifies the AVD name/API
before changing font scale. It sets actual system text to 160%, selects each of
25 app languages through Android's LocaleManager, and runs the production
Push Sync screen/ViewModel/backend with synthetic relay, messaging and browser
interfaces. Each language must complete the same 21 states: consent off,
selected installation, missing/restored registration, all repositories,
suspension, cancelled/owner callbacks, Stop, failed status, no matching checkout,
failed link opening, notification permission feedback, unavailable messaging,
empty connections, connected callback, cancelled unlink, activity recreation,
confirmed unlink, opt-out and Back. This does not authorize GitHub in a browser,
receive real FCM or test Android's permission dialog.

The checks require readable translated controls/title, a named switch, an LTR
GitHub handle within RTL layouts, complete unlink text or an operable scroll area,
and unchanged note bytes, HEAD, index and config after every state. Recreation
must retain the ViewModel, dismiss an unconfirmed unlink and send no unlink
request. Fixtures use UUID-scoped preferences and encrypted Push Sync state. Original
preference maps remain on-device in the protected runner’s encrypted baseline,
which it restores afterward. The host restores
its original font and app-language tags in `finally`, records recovery even on
failure, and copies only synthetic Arabic/German/Japanese screenshots after the
whole matrix passes. Do not run an Argent inspection session concurrently with
instrumentation screenshot capture: Android allows one UiAutomation connection.

### Pull All entry and summary ownership

`PullAllScreenTest` includes nine additional entry tests. Back cancels a pending
Home summary read, manual Start replaces that pending request, and an older empty
read cannot start another pull after newer history is known. Automatic entry
checks for a summary both before and after catalog loading; a newly running
background batch also blocks manual dispatch at those boundaries. Positive
controls require empty-history Home entry and explicit Pull All Again after
review to execute one real local pull.

The tests use delayed local reads, the real UUID-scoped background summary store
and production screen/ViewModel/runner. They count attempted pull calls, retain
new summaries, and check working bytes, HEAD, index, config and the fixture remote
at the catalog-publication boundary. All Git endpoints are on-device fixture
repositories. No production summary or external repository is used.

After installing matching debug/test APKs with `-r`, run the full screen cohort
on its explicitly identified owned emulator:

```sh
python3 tools/testing/run_native_regression.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026 \
  --classes com.bontecou.syncmd.PullAllScreenTest --raw-status --timeout 300
```

The protected runner retains/restores the original private preference maps
inside encrypted on-device storage. It rejects empty test selections; do not
clear app data or uninstall to recover an interrupted run.

Four further Pull All re-entry cases cover opening Pull All from Home while a
foreground pull is running, while Stop cleanup is pending, and while its catalog
is loading.
Review must preserve the current request/results and must not queue a later pull
or invalidate the original automatic request. Opening after completion and an
explicit retry after Stop must still execute. The tests permit an immediate
review response; they do not require an unnecessary background read while work
is active. All delayed work and Git endpoints belong to the same local fixture.

Pull All language and widget qualification:

```sh
python3 tools/testing/run_pull_all_language_checks.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
python3 tools/testing/run_native_regression.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026 \
  --classes com.bontecou.syncmd.PullAllWidgetLanguageTest --raw-status --timeout 300
```

Install matching debug/test APKs with `-r` first. The language wrapper checks the
owned AVD identity and restores original app locales/system font in `finally`;
private preferences stay in the protected runner's on-device encrypted baseline.
It requires all 25 app languages, actual 160% text and 25 workflow states each:
real fixture fast-forward/current/dirty results, synthetic LFS/failure, gated
Stop, saved running/completed/failed/interrupted summaries, clear cancellation,
recreation and unreadable-summary recovery. Git endpoints are owned local file
repositories; app actions must preserve the fixture remote and second checkout.
Arabic/German/Japanese captures contain only synthetic data. Widget checks
parcel RemoteViews into a different-language host, verify localized labels,
accessibility text, locale spans/direction and the private locale-change route.
They do not click an execution intent or place/resize a real launcher widget.


Widget size qualification additionally uses an unbound native `AppWidgetHostView`
to check automatic responsive layout selection and shrink-back on API 31+, and
conservative options-based updates on older Android. No widget ID is allocated
and no launcher layout/binding is changed. The compact minimum is 180×112dp;
expanded variants start at 240×280dp or 360×192dp. Every locale is measured at
font-configuration scales 1, 1.6 and 2 (225 renders per platform), checking actual
render configuration, visible line widths, vertical fit, no ellipsis and the
full accessible description. These configuration-context renders are separate
from the language wrapper's actual global 160% system-text workflow. Actual
launcher placement, transitions and sizes outside the advertised range remain
separate acceptance work.

Background Sync language workflow:

```sh
python3 tools/testing/run_background_language_checks.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs with `-r` first. The identity-scoped wrapper
requires all 25 actual app languages at actual 160% system text, with 39 states
per language. Production screen/ViewModel/backend/store cover pull/publishing
consent, reviewed enrollment and Wi-Fi/power options, all 12 saved health states,
dates, exclusion, missing registration, policy removal, unreadable-settings
reset and stale-confirmation rejection. Actual recreation must dismiss pending
consent/review/reset dialogs and retain the ViewModel. Long consent/reset text
is scrolled to its end and back; action labels, paths/URLs, toggle roles/names
and minimum 48dp header/toggle targets are checked. The fixture seeds an owned
file remote before the workflow, then requires unchanged note bytes, HEAD,
index, config and remote HEAD at every state. Scheduler callbacks and work-status
flows are synthetic: no actual worker or external Git/network call is launched.
The existing BackgroundSyncTest worker cases separately exercise local-file Git
transfers and persisted mode consent. Native private baseline recovery stays
on-device; host `finally` restores exact original font/app locale settings.

`BackgroundSyncTest#nativeResetConfirmationCannotAuthorizeAReplacementUnreadableSettingsVersion`
replaces unreadable settings while a reset dialog remains open, reloads the
production ViewModel and requires rejection of the old confirmation. A new
confirmation must still recover. This tests the UI-to-store version boundary;
it does not rely only on calling the store's reset API directly.

After a complete language qualification, an isolated copy/layout change can be
replayed with `--language de` (or another supported tag). The receipt records
one expected locale; the default still requires the complete 25-locale matrix.
The protected runner accepts `--background-language` only for the exact
Background Sync language workflow method and rejects unsupported tags before
baseline capture. The workflow's default selection remains all 25 languages.


### Repository action confirmation ownership

`RepositoryActionsScreenTest` exercises manual write and launcher-shortcut
confirmations through production screen/ViewModel/runner code with owned local
file Git remotes. Pending dialogs retain their original repository, action and
message. Replacing the selected repository or accepting an incoming shortcut
must invalidate the old confirmation, including when the shortcut yields an
identical reviewed plan. Reusing an already consumed confirmation must not
publish later edits or issue another launcher request. Each case also verifies
that a fresh explicit confirmation still works. The launcher interface is a
fixture; these tests do not pin real home-screen shortcuts.

With matching debug/test APKs installed using `-r`, run the complete 19-method
component cohort at actual 160% system text:

```sh
python3 tools/testing/run_repository_actions_large_text.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
```

The wrapper verifies the selected owned emulator, runs the protected private
baseline harness, captures only synthetic consent images, and restores the
exact original font setting in `finally`. The dialog check reads the rendered
text layout's actual font scale and checks the visible 48dp execution-mode
choice. Scrollable review details follow the action, repository and mode/scope.
This English component check does not qualify all locales, screen sizes,
physical devices or actual launcher placement.


`RepositoryActionsScreenTest#pendingLinkResetCannotEraseAReplacementUnreadableStore`
opens the unreadable-authorized-links reset dialog, replaces the store, refreshes
the production ViewModel while that dialog stays open, and confirms the old
review. The replacement and all repository/catalog bytes must remain intact;
a new explicit confirmation must still recover. `RepositoryCallbackGrantTest`
checks exact-version store recovery, healthy-store refusal, retained bearer-link
revocation semantics and isolation from launcher grants. Recovery fingerprints
include preference types and sorted set contents, with non-finite floats encoded
as strings; only the opaque digest crosses the UI boundary. No private link
payload is included in test receipts.


### Repository Actions languages

```sh
python3 tools/testing/run_repository_action_language_checks.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs with `-r` first. The identity-scoped wrapper
requires all 25 actual app locales at actual 160% system text. The production
screen, ViewModel and runner cover 17 states per locale: empty catalog, selection
and review, identifier display, local status, an up-to-date pull, entered message,
write review/cancellation, actual activity recreation with dismissed pending
consent and retained draft, no-change push/sync, stale approval rejection,
catalog failure, reload, Stop, recovery and Back. Native assertions check text
layout, rendered font scale, reviewed dialog contents, scrolling to the message
and back, LTR path direction, remote-before-branch character bounds in Arabic/Hebrew,
and named 48dp radio choices and Back control. Commit-message labels and all
four selected action labels are checked in the rendered workflow. Every state preserves note,
HEAD, index, config and the owned file-remote HEAD. Fetches stay on that local
file remote; checkout bytes and remote HEAD remain unchanged. Fetch metadata
may update. The scenarios make no external network calls. Stop uses a
controlled pending catalog read; existing action tests cover stopping Git work.

The default requires all 25 locales; `--language de` replays exactly one locale.
The protected runner accepts `--repository-action-language` only for this exact
workflow method and validates its tag before baseline capture. The host wrapper
retains a live child handle after an observation timeout, restores original
font/app locales in `finally`, and copies only synthetic Arabic/German/Japanese
consent images. Private baseline recovery stays on-device. This language path
qualifies the main Repository Actions controls, not every launcher/callback
section or every possible Git error/transport. Resource tests independently
validate all translated formatter strings. Wider device, window, accessibility,
motion and human linguistic review remain required.


### Launcher-shortcut consent and recovery languages

```sh
python3 tools/testing/run_shortcut_language_checks.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026
```

Install matching debug/test APKs with `-r`. The identity-scoped wrapper requires
25 actual app locales, actual 160% system text and 32 ordered states per locale.
`--language ar` (or another supported tag) replays exactly one language; the
protected runner gates `--shortcut-language` to this exact native method before
baseline capture. Private baseline recovery stays on-device. Host `finally`
restores original font/app locales and copies only synthetic Arabic/German/
Japanese consent images. A live child process is retained after an observation
timeout.

The production screen/ViewModel/service/store run with a simulated launcher and
an owned local file Git fixture. Checks cover foreground/background consent,
cancelled and recreated dialogs, unsupported/requested/pinned states, localized
ShortcutInfo labels, two incoming Status deliveries, retained manual Push draft
and dismissed pending pin consent across actual recreation, named 48dp background
choice, queued/running/completed/attention/interrupted/local-commit receipt
presentation, unreadable-results recovery, busy rejection, revoke/cancel/forget
and accessible Back. Every checkpoint preserves note, index, config, HEAD and
file-remote HEAD. Saved result statuses are seeded; the foreground Status runtime
is real. The test posts no notifications and creates no real launcher bindings.
Notification/channel copy is covered independently by native resource checks.
Existing background execution and real-launcher acceptance remain separate gates.

A shortcut delivery now has an opaque presentation identity. The screen saves
which delivery it applied, so activity recreation retains the user's manual
selection/message, while a new delivery of the same shortcut still applies.
This presentation gate does not consume or change reusable shortcut authorization.

### Existing pinned-shortcut language updates

After installing matching debug/test APKs on the owned API 35 qualification AVD:

```sh
python3 tools/testing/run_native_regression.py \
  --device emulator-5560 --avd gitsync-paid-review-20261026 \
  --classes com.bontecou.syncmd.RepositoryShortcutLabelsTest,com.bontecou.syncmd.RepositoryShortcutLauncherTest#existingPinnedLabelTracksAppLanguagesWithoutRunningOrReauthorizing
```

The six isolated label checks cover all actions and execution modes, unchanged
labels, unrelated intents/IDs, rate limiting, locked-user responses, unreadable
authorization, revocation and Unicode clipping. The launcher method approves one
synthetic Status pin, changes the actual app locale through German, Japanese,
Arabic and English, and requires both platform labels and the visible home-screen
label to change. After revocation it repeats the four language changes, requires
the saved disabled message to use the app language, and taps the disabled icon.
It verifies the disabled reason, intent stack and authorization remain unchanged,
then injects a stale message with current labels and requires its repair.
Pixel Launcher on the qualification images may substitute its own English
“Shortcut isn't available” toast. The fixture records this host fallback separately
from the strictly checked translated Android shortcut metadata; it does not claim
localized visible toast acceptance for that host.
Intent stacks, private authorization and local Git contents must stay unchanged;
no repository action is executed by this method. Cleanup removes only its synthetic pin,
restores original app locales and deletes its fixture. The protected runner keeps
the private baseline encrypted inside the app. Other API levels, a system-wide
locale switch and an upgrade with an existing pin need separate qualification.
Static list sections have distinct keys so approval/results refreshes replace
their controls without retaining duplicate headers.


### Authorized Links management languages

```sh
python3 tools/testing/run_callback_grant_language_checks.py \
  --device emulator-5554 --avd gitsync-parity-api36-20261031
```

Install matching debug/test APKs first with `-r`. The wrapper requires all 25 app
languages at actual system font scale 1.6 and validates 21 ordered workflow states
per language. It restores the original font/app locales even after interruption;
the protected runner keeps its encrypted preference baseline inside the app.

`CallbackGrantLanguageTest` exercises the production Repository Actions screen,
ViewModel, callback grant service and private stores against owned local Git
repositories. It covers active permissions, saved queued/running/completed/
attention/busy/interrupted/local-commit feedback, unreadable result recovery,
result clearing without changing permissions, revoke/cancel/forget, unreadable
authorization reset/cancel/recreation and rejection of a stale reset. Each state
preserves note/index/config/HEAD and the owned file-remote HEAD. Receipts are
controlled fixtures; no callback is dispatched, no launcher binding is created,
and no external repository is contacted.

Revocation/reset explanations must scroll to their end and back, retain full
native text layout and leave 48dp confirmation/cancel controls visible. Refresh,
revoke, forget, reset and result-clear targets also require 48dp bounds. Selected
Arabic/German/Japanese screenshots wait for the actual revocation dialog before
capture. These checks qualify management/recovery copy, not the separate callback
request, link-creation consent, external return-app flow or linguistic quality.

The separately authored
`RepositoryCallbackScreenTest#everyTranslatedForegroundConsentRemainsReadableAtLargeComponentTextWithoutRunningGit`
checks the foreground review title, three consent explanations and 48dp controls
in all 25 locales at injected 160% component text. It preserves the owned
draft/index/config/local and bare-remote HEAD/catalog and never approves the Git
action or opens an external return. This is component coverage, not actual
system-font/language activity recreation. Execution is still pending; authoring
or compiling the method is not a runtime pass.


### Callback link consent component languages

`RepositoryCallbackScreenTest#everyTranslatedLinkConsentCanBeReadAndCancelledWithoutIssuingAuthority`
opens the production authorization dialog in all 25 resource locales at injected
160% component text density. It checks the title and grant/background consent
layouts, 48dp confirm/cancel/choice controls, both ends of the scroll range, disabled
return while background mode is chosen, and restored foreground return after
switching back. Cancelling must leave no grant or copied link, no Git dispatch or
return, and unchanged draft/index/config/local and file-remote HEAD/catalog.
This method has been authored for native execution; compilation is not a runtime
pass. System-language recreation, the physical IME and actual notification returns
remain separate checks. Do not resume emulator work: the user's selected device
is the physical Pixel through Argent.


### Remote-merge consent component languages

`RemoteMergeScreenTest#everyLocaleReviewsAndCancelsBothMergeConsentsWithoutChangingGitAtLargeComponentText`
uses a divergent owned file remote, a real fetched review plan and all 25 resource
locales at injected 160% component density/direction. It checks the growing title,
48dp controls, rendered source/target identifiers, separate local/publishing consent,
reviewed publication address, both dialog scroll ends and cancellation. Both consent
cancellations must preserve the plan, draft files, index/config/local and file-remote
HEAD, with no merge result. Fetch metadata may change during the initial review.
The method is authored for native execution. Compilation is not a runtime pass;
system-language recreation, actual remote publication, physical accessibility and
wider transport qualification remain separate. The selected device remains the
physical Pixel via Argent; do not resume emulator work.


### Physical Pixel saved link and Done-key form

The real existing-installation browser link is verified separately by direct Pixel
UI and refresh/restart evidence in `FEATURE_PARITY.md`. Its OAuth diagnostic trace
is archived as incomplete; it is not an automated authorization test.

The native-only replay is:

```sh
argent e2e run pixel-linked-account-form-704-20261102 --platform android --device 2C061FDH200CJN
```

The saved test is `.argent/e2e/pixel-linked-account-form-704-20261102.yaml`.
It assumes an onboarded English production app with an existing registered Push
Sync connection and the Pixel's Gboard. It restarts the app, opens Settings/Push
Sync, checks registration and Remove connection, types the public owner draft,
proves Gboard visible before Enter and hidden afterward with retained field text,
then collapses the form and rechecks the connection. It never submits authorization.
Recorded helper resets deliberately contain the session's device scope; the explicit
`--device` rebinds them. There are no raw coordinates or role-only action taps.

Final native replay: 35 non-echo steps pass, 0 fail/error/skip, 35.7s. Five idle
steps warn about small continuing visual changes; the exact moving regions remain
unqualified. Named controls and value/keyboard conditions support the functional
claim; this is not a screenshot-stability/performance or all-language pass. The
initial replay failed at collapse before post-IME readiness was added, and is retained
as failed evidence. Receipts: `/tmp/syncmd-pixel-form-704-replay-{1,2}.json` and
`/tmp/syncmd-pixel-704-update-proof.json`. Desired production link/data/permissions
remain intact; no fixture registration is unregistered as cleanup.


Diagnostics localization fixture (authored, physical execution pending):
`DiagnosticsScreenTest#everyLocaleRetainsFeedbackAndReviewsAllFilteredRecordsBeforeClearingAtLargeText`
uses the independently generated expectations, 25 component locale/resource
contexts, RTL direction and 1.6 component density with one retained view model.
It reviews an error-only filter while two other records are hidden, cancels and
checks the owned store remains intact, then confirms all-record clearing and
checks a newly loaded owned store. Copied/cleared feedback must resolve in the
current locale. This does not establish system-language recreation, actual
clipboard/share-provider behavior in every locale, or physical accessibility.
Existing English diagnostic filter/copy/share/corruption tests remain separate.


SSH trust localization fixtures (authored; physical execution pending):
`SshTrustTest#everyLocaleReviewsChangedKeyAndCancelsForgetAndResetWithoutChangingPinsAtLargeText`
uses 25 component locales, RTL and 1.6 density with independently generated text
expectations. It cancels changed-key approval, one-server forgetting and reset,
then reloads the scoped pin store to check the old key still verifies and the new
one does not. The separate
`SshTrustTest#changedPinDuringApprovalIsReportedByIdentityAndNeverApprovesTheWaitingKey`
removes an owned pin while a changed-key request waits, then attempts its stale
approval and checks typed rejection without persisting the new key. Synthetic
host keys and scoped preferences are used; no network connection or real private
key is involved. These do not qualify actual SSH authentication, system locale
recreation, disk-failure rollback or physical accessibility.

The unchanged English/Gboard linked-account form replay also passes on the
847-key physical Pixel release (APK SHA-256
`b2e635b7aa10bdaaaff236a0e43a8eb16bd3700f3b07d81bab6919b1f3358586`):
35 non-echo steps, 0 failures/errors/skips, 33.7s. Report:
`/tmp/syncmd-pixel-form-847-replay.json`; guarded update receipt:
`/tmp/syncmd-pixel-847-update-proof.json`. Five idle warnings remain unqualified.
This is a Push Sync account/form regression on the updated app, not execution of
the new Diagnostics or SSH locale fixtures and not live webhook verification.

Physical Diagnostics/SSH language draft (not a passing test):
`.argent/recordings/pixel-diagnostics-ssh-language-847-20261102.incomplete.yaml`.
The 137 steps review production English and Spanish Diagnostics filters,
clear-all consent/Cancel and retained entries, empty SSH trust/reset consent/Cancel,
actual Android app-language switching, restoration of System default and the
existing Push Sync connection. It assumes an onboarded 847-key release, English
system UI/default app language, Spanish US suggested, no trusted SSH servers,
saved informational diagnostics with no errors and an existing Push Sync link.
It adds ordinary startup log entries but never confirms clearing logs/resetting
trust. No private keys, SSH connections or new GitHub authorization are used.

A full replay reached the translated states but had an offscreen Settings-row
identity check; that was replaced using a newly recorded visible heading. The
23-step restoration tail passed, restoring the original language and connection
controls. The subsequent full replay was interrupted by physical USB/ADB loss
before changing language. Do not count either partial replay or the tail as a
full pass. Reports and exact limits are in
`/tmp/syncmd-pixel-language-847-runtime-proof.json`. Resume with Argent
`e2e_execute`, absolute `flow_path`, `project_root` and explicit Pixel serial.
All actions in the draft are semantic; the 30 stored serial references scope
recorded helper teardown only. Live ambiguous language-row captures were replaced
by text selectors verified in scratch runner fragments. No visual stability,
160% physical font, full linguistic or all-25-locale execution claim is made.


LFS endpoint localization fixtures (authored, device execution pending):
`LfsEndpointStoreTest#everyLocaleReviewsAndCancelsBothEndpointDirectionsWithoutSavingAuthorityAtLargeText`
reviews download/upload consent in 25 component locales at 1.6 density, checks
literal endpoint display, the separate-credentials choice, password semantics,
disabled approval with empty credentials, then cancellation without any saved
authority. A fresh noninteractive store must still require review.
`LfsEndpointStoreTest#everyLocaleCancelsDirectionalRevocationAndResetWithoutChangingEncryptedApprovalsAtLargeText`
starts with owned, encrypted synthetic credentials in both directions. It reviews
and cancels each direction’s revoke dialog and the reset dialog, checks the exact
stored ciphertext map remains unchanged, and reloads both credential policies.
No external transfer, actual system locale recreation or physical accessibility
is established by these authored fixtures. Existing English persistence/transfer
coverage remains separate; neither new method has run on the Pixel.


LFS approval failure fixtures (authored, physical execution pending):
`LfsEndpointStoreTest#wrongAuthorizationAndInvalidNewCredentialsDoNotChangeExistingDirectionOrApproveTheWaiter`
checks typed method/credential rejection, unchanged scoped ciphertext and retained
download credentials while the upload waiter fails.
`LfsEndpointStoreTest#failedRollbackReportsRecoveryAndDoesNotApproveTheWaitingTransfer`
simulates two reported commit failures and requires a recovery identity with no
successful waiter. This is an injected persistence boundary, not power-loss proof.
`LfsEndpointStoreTest#everyLocaleRendersAndDismissesDamagedApprovalRecoveryWithoutRemovingTheStoredRecordAtLargeText`
uses an owned corrupted encrypted entry, all 25 component locales and 1.6 density.
The recovery message must be readable/localized; dismissing it must leave the
stored record intact and unavailable for authorization. Existing stale review and
approval/revoke/reset failure cases now also assert their failure identities.
No method above has executed on the Pixel. Actual system locale recreation,
platform disk-failure behavior and full accessibility remain unqualified.


### Physical Pixel live GitHub webhook proof (2026-11-02)

The approved generated public repository check now passes GitHub webhook → deployed relay → FCM → background fast-forward on the physical API 37 Pixel, with periodic sync off and the app backgrounded. Notification tap and Open repository were verified afterward; the generated public repo was archived and its phone catalog entry/files removed. The desired registration and personal installation link remain, and relay inventory is empty again.

Source repair, 106 relay tests, five actual workerd checks, exact deployed-source archive, sanitized receipts and screenshots are in [relay-fcm-runtime](relay-fcm-runtime/README.md). The two prepared commit IDs, GitHub retry metadata, final release APK hash, cleanup and proof limitations are in its evidence directory and FEATURE_PARITY.md. The preserved 93-step `.argent/recordings/pixel-live-webhook-887-20261102.observational.yaml` is a first-walkthrough record with documented fallbacks and external cloud actions; it has no uninterrupted portable replay pass. Do not replay it against the archived fixture or count it as a qualified automated regression. No emulator or native JUnit execution occurred in this phase.

### Reviewed discard regression fixtures

`DiscardReviewTest` uses real temporary Git repositories. It covers distinct full-path scope, both index/worktree layers, removal of new tracked/untracked files, protected ignored collisions, selected-file independence from unrelated later edits, changed checkout attributes, unborn-branch staging-only behavior, same-size/same-mtime edits, stale branch/index/config/operation/folder identity, foreign index-lock preservation, safe symlink handling, file/directory obstructions, cached LFS restoration, merge cleanup, and successful private-index/lock cleanup.

Native fixtures are authored but **not executed on the Pixel**:

- `DiffViewModelTest#cancelKeepsEveryByteAndDuplicateDiscardConfirmationExecutesOnlyOnce`
- `DiffViewModelTest#staleFileAndForeignIndexLockKeepChangesAndClearTheDiscardBusyState` (also checks the refreshed single-file preview)
- `DiffViewModelTest#queuedDiscardRejectsRepositorySwitchAndSameRepositoryReentryBeforeItsLeaseStarts`
- `DiffViewModelTest#observationRefreshDoesNotReplaceTheOwnerOfAnApprovedQueuedDiscard` (also checks that a read-only refresh preserves the frozen review)
- `DiscardReviewDialogTest#everyLocaleCanReviewAllFullPathsAndCancelAtLargeTextWithoutChangingFilesOrIndex`
- `DiscardReviewDialogTest#unbornConfirmationSaysClearStagingAndPreservesEveryWorkingFile`

The dialog fixture uses 25 component-local locales, 1.6 font density, representative dark/RTL layouts, 35 long full paths, reachable 48dp actions, and exact index/file preservation on Cancel. It does not change system locale, font settings, user repositories, credentials or remote state. It does not prove system-locale recreation, TalkBack, motion or physical media behavior. The unborn fixture confirms the production core operation after the UI action and checks that only staging changes.

Physical acceptance remains a separate Argent run on the connected Pixel using only generated local files. Verify Cancel, selected-file restoration, Revert All including untracked files, ignored-file retention, stale approval refusal, retained Push Sync, and cleanup. The archived public webhook fixture may be read/cloned without a new publication; no new public repository, commit, push, unarchive or connection unlink is needed for this check.

`check_discard_readback.py` checks a pulled copy of the generated November 2 fixture against its prepared host baseline. It performs no ADB commands, network requests or writes. Use `--baseline /absolute/path/to/prepared-fixture --readback /absolute/path/to/pulled-fixture --phase cancel|single|all`. Cancel requires exact index bytes and all 11 working files; single means only `note.md` was restored in both layers; all requires the two HEAD files, the retained ignored file and no generated untracked files. Every phase pins HEAD, branch, remote, config, exclude rules and staged blob identities, and rejects leftover discard index/lock files. The checker was smoke-tested on temporary host copies, including rejection of corrupted working bytes and a missing ignored file. Host results do not qualify a physical UI run or its cleanup.

Physical continuation completed direct Cancel/single/all checks and cleanup. The generated phone catalog entry and checkout are removed; Push Sync remains linked to @CodyBontecou with its notification device registered. See [discard evidence](discard-review-evidence/README.md) for exact release/build/readback evidence and the failed full replay. The old `/tmp/syncmd-discard-pixel-fixture-proof.json` is updated to `cleanup_pending: false`; its prepared baseline remains available for future isolated fixture creation. Do not replay the archived drafts against a deleted or unrelated checkout. Native JUnit, stale-review/lock physical checks and broader accessibility/localization acceptance remain pending.

### LFS request ownership without a device

The app JVM suite now includes `LfsRequestOwnershipTest`: five real-Git queued tracking/extension/cached-hydration cases and three mocked remote-lock boundary cases. They exercise the production ViewModel under a held repository lease, including selection changes, same-folder reload, disposal and valid duplicate suppression. [Evidence and rerun command](lfs-request-ownership-evidence/README.md) include the original failing regression, eight passing cases, frozen build inputs and explicit runtime limits. These do not replace physical Android or live lock-provider checks.

### LFS object status and sizes

`LfsFileSummaryLanguageTest` is authored/compiled for 150 component locale/cache/availability/layout states but remains unexecuted. The production summary has five extracted messages in all 25 locales and context-based native file sizes. [Evidence and boundaries](lfs-file-state-evidence/README.md) include the eight passing JVM regressions, catalog/tooling validation, release installation, physical English checks, exact readback/cache findings and completed generated-checkout cleanup. The setup/language record is observational; the separate 15-step Push Sync retention replay passes. User preference is currently code/JVM work while the Pixel is in use.

### Coherent LFS read snapshots

The app JVM `LfsRequestOwnershipTest` suite now has ten passing cases. The two added regressions reproduce mixed object/plan snapshots during concurrent staging and an old queued load entering the inspector after selection changed. [Read-snapshot evidence](lfs-load-snapshot-evidence/README.md) includes the original failures, passing results, build hashes and scope limits. Extension-review reads now use the same owner guard. This phase follows the user’s code/JVM preference; its new release is not installed and it adds no physical or native-instrumentation qualification.

### LFS tracking review identity

[Tracking identity evidence](lfs-tracking-identity-evidence/README.md) records five reproduced stale-review failures and six passing real-Git regressions covering exact and extension choices after checkout/metadata replacement or configuration changes. Both confirmation paths now pin checkout identity, and fresh reviews still authorize the replacement repository. Full JVM/build results and frozen inputs are retained there. This phase follows the user's code/JVM preference and adds no device or native-instrumentation qualification.

### Localized exact-file LFS confirmation

[Tracking confirmation language evidence](lfs-tracking-language-evidence/README.md) records four newly extracted messages in 25 locales, a proper file-count plural, complete frozen paths in a scrollable dialog and 48dp actions. Catalog checks, 22 tooling tests, 10 app JVM tests and all build/lint tasks pass. The 150-state component fixture compiles but has not executed; the release remains uninstalled per the code/JVM preference.

### LFS lock review working-folder identity

[Lock worktree evidence](lfs-lock-worktree-evidence/README.md) records three reproduced stale-confirmation failures and passing real local HTTPS checks for create, owned release and force release. Old reviews now reject a replaced working folder even when its original Git metadata is retained, with zero further lock requests; fresh reviews remain usable. All 682 core tests, 10 app JVM tests and build/lint tasks pass. This phase adds no physical/provider acceptance and leaves the new release uninstalled per the code/JVM preference.

### Main LFS screen translations

[Main LFS language evidence](lfs-main-language-evidence/README.md) records 13 additional mapped keys in all 25 locales, including the exact-confirmation Apply action. All 20 static resource references across the main screen/dialog/entry are mapped; lock components and model text remain separate unfinished work. Catalog checks, 22 tooling tests, APK builds and release lint pass. App JVM results were reused unchanged, core hashes match the prior 682-test run, and the updated native fixture is compiled but unrun. The release remains uninstalled per the code/JVM preference.

### LFS confirmed-action refresh recovery

[Follow-up recovery evidence](lfs-followup-recovery-evidence/README.md) records three reproduced false-failure reports after real tracking/hydration completed. Confirmed notices now survive failed follow-up reads; stale controls and further actions wait for explicit reload. All 13 app JVM cases, 22 tooling tests and build/lint tasks pass. The new explanation has 25-locale mappings; existing model notices remain unfinished localization. No native/physical acceptance or installation is claimed.

### Typed LFS completion feedback

[Operation notice evidence](lfs-operation-notice-evidence/README.md) records language-independent tracking/extension/hydration results and all-25-locale messages/quantities. Thirteen JVM regressions, 22 tooling tests and build/lint checks pass. The 250-state retained-notice native fixture compiles but has not executed; language recreation, human linguistic review and legacy lock/endpoint/error copy remain open. The new release remains uninstalled per the code/JVM preference.

### LFS lock consent translations

[Lock consent evidence](lfs-lock-consent-language-evidence/README.md) records 11 mapped keys in 25 locales, literal LTR identifiers, unchanged acknowledgement/validity/busy gates and 125 authored native scenarios. Thirteen JVM tests, 22 tooling tests and build/lint checks pass. Native scenarios and linguistic/physical qualification remain pending; the release is uninstalled per the code/JVM preference.

### LFS lock list languages and routine status

[Lock list evidence](lfs-lock-screen-language-evidence/README.md) records all-25-locale static controls, typed routine refresh results and correction of an unsupported-server ownership label. Fourteen JVM tests, 22 tooling tests and build/lint checks pass. The 150-scenario list fixture and expanded 300-state retained-notice fixture compile but remain unrun. Legacy mutation/endpoint/errors, native/physical/linguistic qualification and other parity requirements remain open. The release stays uninstalled per the code/JVM preference.

### Repository catalog languages and counts

[Catalog language evidence](repository-catalog-language-evidence/README.md) records ten additional mapped labels per locale, proper repository-count quantities, a wrapping account/count header and scrollable keep-files consent with 48dp targets. Fourteen existing JVM tests, 22 tooling tests and build/lint checks pass. The 200-state native header fixture is compiled but unrun; full-screen/removal-language and physical/linguistic qualification remain open. No repository removal or installation occurred.

### Discovery and repository-history translations

[Discovery/history evidence](repository-discovery-language-evidence/README.md) records 19 mapped resources per locale and current-language status rendering from retained metadata. Fourteen existing JVM checks, 22 tooling tests and APK/lint builds pass. The 200-state native summary fixture compiles but remains unrun; no scan, import, reconnect or removal was performed. Other consent/model/warning copy, actual language workflows and physical/linguistic qualification remain open. The release stays uninstalled per the code/JVM preference.

### Frozen tracking-removal approval

[Tracking consent evidence](tracking-removal-consent-evidence/README.md) records frozen plan/backup approval, lease-time revocation and six real-Git rejection/success cases. Twenty JVM tests, 22 tooling checks and all APK/lint builds pass. Final consent has all-25-locale resources; its 50 native scenarios compile but remain unrun. Android Settings persistence, other consent/model copy and physical/linguistic qualification stay open. Only generated JVM fixtures were detached; the release is uninstalled per the code/JVM preference.

### Tracking-removal review presentation

[First-review evidence](tracking-removal-review-evidence/README.md) records all-25-locale backup warnings, native metadata size, 48dp controls and controlled checkbox semantics before frozen final consent. Twenty JVM cases, 22 tooling tests and build/lint checks pass. The 50 native review scenarios and corrected merged-checkbox assertions compile but remain unrun. Actual navigation/persistence, other consent/model copy and physical/linguistic qualification remain open. The new release is uninstalled per the code/JVM preference.

### Reviewed managed-clone deletion

[Managed deletion evidence](managed-deletion-review-evidence/README.md) records two reproduced path-replacement failures and eight passing real-Git review/context cases. Deletion now requires a prepared checkout plan plus a frozen catalog review and lease-time validation. All 690 core tests, 20 app JVM checks and APK/lint builds pass. Whole-folder working bytes are not frozen; external-writer atomicity and Android persistence/navigation/physical qualification remain open. Only owned JVM fixtures were deleted, and the release remains uninstalled.

Managed deletion request authority: [evidence](managed-deletion-request-evidence/README.md), eight real-Git request cases and 28 passing app JVM cases; native/persistence qualification remains pending.

Repository-removal language resources: [evidence](repository-removal-language-evidence/README.md), 200 authored values, 22 tooling tests and qualified APK builds; native/language-quality acceptance remains pending.

Credential-removal consent: [evidence](connection-credential-consent-evidence/README.md), six new ViewModel cases, 34 app JVM passes and fixed-snapshot APK/lint qualification. Current UI drift, mocked store boundary and 193 unscanned localization strings/eight plurals are explicitly pending.

Complete localization resource discovery: [evidence](localization-resource-discovery-evidence/README.md), three new regression reproductions, 25 tooling tests, complete base inventory and final fixed-snapshot APK/JVM/lint checks. Native and remaining language acceptance stay pending.

Home/Git/Vault plural languages: [evidence](tide-plural-language-evidence/README.md), 528 authored forms, all nineteen plural resources mapped, 25 tooling tests and current-input APK/JVM/lint checks. Native and linguistic acceptance remain pending.

Connection save/migration ownership: [evidence](connection-mutation-ownership-evidence/README.md), five reproduced failures, thirteen new JVM cases and 47 app JVM passes; native effect delivery and encrypted persistence remain unverified.

Connection-screen languages: [evidence](connection-language-evidence/README.md), 350 authored values and resource wiring for all five auth labels; 43 static resource references mapped, with native/linguistic acceptance pending.

Translated-label casing: [evidence](ui-label-casing-evidence/README.md), app-locale caps and preserved resource labels; five isolated JDK probes and fixed-snapshot build checks. Production Compose/Android casing and concurrent UI edits remain unverified.

Home/status languages: [evidence](home-language-evidence/README.md), 550 authored values, packaged RTL handle bounds, six unique JDK Bidi probes and current-input APK/JVM/lint checks. Native rendering and linguistic acceptance remain pending.

- [Vault language evidence](vault-language-evidence/README.md): 700 translations, reviewed status copy, packaged RTL arrow/count checks, 47 app JVM tests, APK builds and release lint; native execution pending.

- [Connection completion evidence](connection-completion-evidence/README.md): saved-review/route ownership through asynchronous metadata refresh, cancellation and duplicate delivery; 56 app JVM tests, APK builds and release lint; native lifecycle/persistence pending.

- [Author/upstream review evidence](settings-consent-evidence/README.md): exact displayed Save consent, reload request IDs and synchronous draft initialization; sixteen new real-Git cases, 72 app JVM tests, APK builds and release lint; native behavior pending.

- [Git tools language evidence](git-tools-language-evidence/README.md): 575 translations, exact debug/release resource-table readback, 72 app JVM tests, APK builds and release lint; native and linguistic review pending.

- [Onboarding language evidence](onboarding-language-evidence/README.md): 450 translations, split-headline notes, integer signatures and exact debug/release table readback; 72 app JVM tests, APK builds and release lint; native and linguistic review pending.

- [Reviewed sign-out evidence](signout-consent-evidence/README.md): frozen dialog consent, auth/store session checks and fourteen new cases; 86 app JVM tests, APK builds and release lint; encrypted persistence and native dialog qualification pending.

- [Persisted sign-out refresh evidence](signout-refresh-evidence/README.md): account/profile reconciliation before fresh consent, owned-cache invalidation and seven new cases; 93 app JVM tests, APK builds and release lint; native persistence/rendering pending.

- [Settings language evidence](settings-language-evidence/README.md): 225 translations, literal-handle/fallback/version direction boundaries, compiled table readback and twelve JDK probes; 93 app JVM tests, APK builds and release lint; native/linguistic review pending.

- [Diff/conflict language evidence](diff-language-evidence/README.md): 675 mapped values, exact-source reuse, signed-total LTR formatting and eighteen positive JDK probes with a negative control; 93 app JVM tests, APK builds and release lint; native/linguistic review pending.

- [Merge/rebase status language evidence](merge-status-language-evidence/README.md): 525 translations, two reviewed English clarifications, exact APK resource readback and twelve upstream-display JDK probes; 93 app JVM tests, APK builds and release lint; native/linguistic review pending.

- [Git workflow language evidence](git-workflow-language-evidence/README.md): 675 values with current production references, localized strategy presentation and ninety argument probes; 93 app JVM tests, APK builds and release lint. Includes the separately unresolved byte-counter finding.

- [Conflict byte-count evidence](conflict-byte-evidence/README.md): indexed blob sizes and accurate UTF-8 fallback, empty/deleted/unknown distinctions and ten new real-Git cases; 700 core + 93 app JVM tests, APK builds and release lint. Native counters and character/grapheme semantics pending.

- [Branch-control language evidence](branch-language-evidence/README.md): 300 translations, literal ref display and explicit LTR branch input; exact APK readback and thirty JDK probes; 93 app JVM tests, APK builds and release lint; native/linguistic review pending.

- [Repository setup language evidence](repository-language-evidence/README.md): 200 translations for eight active setup/empty-state keys, literal example preservation and exact APK readback; 93 app JVM tests, APK builds and release lint; native/linguistic review pending.

- [Move confirmation ownership evidence](move-consent-evidence/README.md): explicit displayed-review callbacks, lease-time guard and seven new real-Git cases; 100 app JVM tests, APK builds and release lint. Android UI/lifecycle and request-token publication still require native qualification.

- [Move review lifecycle evidence](move-lifecycle-evidence/README.md): atomic request/screen ownership, accepted-move retention and owner-bound completion; ten new state/real-Git cases, 110 app JVM tests, APK builds and release lint. Compose/Android failure paths, resumed navigation and stale-error delivery remain open.

- [Move completion delivery evidence](move-delivery-evidence/README.md): resumed-only route-bound receipt delivery, replay prevention and request-owned preparation errors; twelve new AndroidX lifecycle/state cases, 122 app JVM tests, APK builds and release lint. Real Activity/navigation/process-death and Android store wiring remain unqualified.

### Vault resolution and complete resource languages

[Vault resolution evidence](vault-resolution-evidence/README.md) covers:
- **Blocked pull → Resolve:** commit locally, then the reviewed remote merge.
- **Fetch:** a new entry on the Git screen.
- **Vault overflow menu:** copy URL with HTTP userinfo stripped, Connection, Author and Move.
- **Conflict editor counter:** counts user-perceived characters (grapheme clusters).
- **Localization:** the move, restore and removal flows, and resource coverage of 1,321/1,321 strings in 25 locales.

Results:
- 130 app JVM tests pass on a frozen snapshot.
- **31/31 native tests pass on the API 36 parity emulator.** These include `VaultResolveDeviceTest`, all-locale `LocalizationTest` and `RepositoryMoveTest`.
- The emulator-only storage appop is restored after the run.

Physical-device and accessibility qualification remain open.

### Remaining literals, UiText and the literal guard

[Localization literals evidence](localization-literals-evidence/README.md) covers:
- **Copy:** 276 strings and 8 plurals with translations in all 25 locales. Coverage is now 1,597/1,597 strings and 30/30 plurals.
- **`UiText`:** typed, display-time messages for ViewModels that have no Context.
- **`literal_guard.py`:** blocks new user-facing literals.
- **Importer:** now enforces Android ICU plural categories.

Results:
- 130/130 JVM tests and 28/28 tooling tests pass.
- First full instrumentation suite on API 36: 565 pass, 83 fail, 20 skipped.
- An A/B rerun against the previous phase's APKs shows 81 of those failures already existed there. The other 2 are timing flakes that pass 3/3 in isolation.
