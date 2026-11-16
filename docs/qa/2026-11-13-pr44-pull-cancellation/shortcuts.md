# PR44 — physical Shortcuts queued Stop/retry verified; wording limits retained

## Correction of the earlier timeout interpretation

**The earlier claim that candidate/main Shortcuts actions stalled is retracted.** Visual inspection of the saved, device-wide screenshots shows:

- Candidate Pull returned **“git-tools-fixture: Already up to date” with Done**.
- Verified-main status control returned **“git-tools-fixture · main: 1 changed files” with Done**.

Those hosted result dialogs were absent from the Shortcuts accessibility tree. Stop stayed visible behind a dialog waiting for acknowledgement; requiring Stop to disappear without pressing Done was an invalid completion oracle. Its red receipts do not establish a hung AppIntent, runtime deadlock, packaging failure or PR44 regression. This was an agent verification error: screenshots should have been inspected before classifying the tree timeout. The old report is retained explicitly as `retracted-stop-oracle-interpretation.md.txt`; timeout/raw recovery evidence is preserved, not counted as acceptance or silently erased.

## Direct parameter-free control

With the isolated QA default inventory independently verified as `[]`, created one owned temporary shortcut **PR44 QA No Entity Return**:

1. Selected the **background** Pull All Repositories action from **GitSync PR QA**. Its native More Info text matches `PullAllRepositoriesIntent` (the Personal Automation description), not the separate foreground control action. Native editor ID explicitly contains `bontecou.Sync-md.prqa.PullAllRepositoriesIntent.ShowWhenRun`.
2. Added a separate Show Alert action containing **PR44 QA intent returned**. That alert is only a return-chain sentinel, not a fabricated Git result. Its message also retains the default “Do you want to continue?” text/newline from live editing; no result substitution occurs.
3. Invoked only that uniquely named owned shortcut using `shortcuts://run-shortcut?name=PR44%20QA%20No%20Entity%20Return`, explicitly targeting `com.apple.shortcuts`.
4. Actual device screenshot showed **“No cloned repositories to pull yet. Open GitSync.md and clone a repository first.” / Done**, matching the real empty-inventory path.
5. Visually located and acknowledged hosted Done. The next device screenshot showed the **PR44 QA intent returned** sentinel, proving that execution returned past the real AppIntent. Acknowledged OK; native library then exposed **Play, PR44 QA No Entity Return** again.

Both hosted dialogs were missing from the AX tree. This is direct visual/OS-chain evidence, **not** a claimed unattended AX-only test or Git-pull success. Correct handling must inspect the actual hosted UI before any tap; do not tap underlying library cards when a dialog is missing. Cropped result/sentinel images avoid exporting unrelated shortcut-library names.

## Preserved setup mistakes

During sentinel text editing, a keyboard overlay intercepted attempted bottom Play taps and opened the system **Turn on Dictation** introduction. The introduction was dismissed, never enabled; no consent/Turn on button was pressed. Those attempts were not shortcut executions. The synthetic sentinel remained present. Returned to the library and used the explicit owned-name URL to avoid the keyboard overlap. Raw recording retains the brittle/role-only captures and is **not a mature passing e2e flow**.

## Isolation and prior preservation

Physical iPhone13 Pro Max/iOS27.2. Isolated app `bontecou.Sync-md.prqa`, display **GitSync PR QA**. Candidate dylib SHA256 `68fe868e9ae63baf169757ac979acb4227c520a1034468eebe65cd05af733b6a`; baseline verified-main dylib `061ef840d8c235ec5b305651621024dd7f23291a712d3d173e8e68499cbe6d88`. No source, entitlement, shipping-app or production-guard changes. Candidate restored after the earlier comparison.

For the prior entity tests, CoreDevice proved the isolated default support directory readable and empty before copying only the owned credential-free fixture record. All28 working/Git files remained byte-identical through candidate/control runs and OS Stop. Only the two then-created Pull/Status shortcuts were deleted after validating exactly those names selected and Delete2 confirmation; all12 originally visible cards remained. Only the newly created QA default inventory file was reset to `[]` and read back; physical absence is not claimed. Existing real shortcuts, shipping inventory/vaults, Android work and retained iPhone17 fixture untouched.

Both isolated artifacts omit the production APNs/app-group entitlements for safety. That difference is not an established runtime defect. **Never grant QA the shipping app group as a workaround.**

## Actual queued Stop, release and explicit retry — two live physical cases

On the unchanged candidate, reused only the owned QA background Pull All shortcut. For each fresh synthetic fixture, positively held the real repository coordinator lease, validated the single credential-free private record, and copied only that record into the isolated default inventory. Before each replacement, read back and validated the prior empty/owned default contents. The fixture was deliberately reseeded between cases; no cross-reseed index-byte identity claim is made.

For each successful case:

1. Explicit owned-name invocation; current native tree showed the owned card running, not Play. A device-wide pre-tap screenshot shows its square Stop control and **no completed-result dialog**.
2. Tapped only that owned card's observed small control promptly, before the OS deadline. Play returned in **1.410s / 1.402s**; post-tap screenshots show no result/sentinel dialog and no running workflow.
3. Independently copied and hashed all **28** working/Git files: byte-identical before/after Stop.
4. Foregrounded QA without restart, verified the holder still held, explicitly released it, waited idle plus3s, then copied again **before invoking any retry**: all28 still byte-identical. No late Git write was observed in that bounded interval.
5. Explicitly invoked a new retry. Actual hosted UI returned **git-tools-fixture: Already up to date / Done** in both cases. After visual acknowledgement of Done, the separate return-chain sentinel appeared; acknowledged its OK and verified Play returned.
6. Independent native copies prove retry changed **only `.git/FETCH_HEAD`**, with note/index/HEAD/ref bytes retained. Native HEADs: `25e6bf6608596aced697ecc92c60cd76ec59d89b` and `003bc178cd362200d26961ce4335c0bcb97ffc60`.

**Preserved failed first attempt:** the initial queued run reached an actual system **timeout** dialog before the delayed manual Stop; its subsequent Play wait failed. All28 files remained identical, but this is NOT counted as a successful Stop. The raw30-step recording retains that failure and later successful segment. The fresh21-step second live recording has no unmet waits, but both retain physical coordinate/brittle-selector limitations, external default-inventory setup and manual hosted-dialog review. These are **two live physical cases, not two fully automated consecutive e2e passes**. Do not replay the raw files without their setup and visual safeguards.

OS Stop ends the workflow without showing the app's ProvidesDialog; these cases **do not prove actual displayed localized interruption wording on a canceled Shortcuts workflow**. Typed mapping tests and callback/UI evidence cover separate surfaces; they are not substituted for that missing observation. Earlier presses while completed Done dialogs waited remain excluded from in-flight cancellation evidence.

## Final cleanup and remaining limits

Verified exactly **1 Selected**, positively identified as PR44 QA No Entity Return; confirmed singular Delete Shortcut. The owned probe is removed. All **12 fully visible original cards plus2 partially visible original cards** from the pre-probe native tree remain. An initial cleanup count guard incorrectly counted those two partial cells as full cards; inspection established12full+2partial and the stronger check retained all14 captured names. No existing shortcut was run or changed. Raw unrelated-library snapshots remain private under `/tmp`.

Read back default inventory and confirmed it still exactly matched the owned case2 record before resetting only that newly created file to `[]`; independently read back empty. Normal QA restart uses no fixture args; owned private fixture/native copies retained. No workflow, holder, recorder or server remains running. Shipping app/real vaults untouched.

Additional artifacts are local sidecars under `docs/qa/2026-11-13-pr44-pull-cancellation/evidence/pr44-shortcuts-iphone13/`, not committed or CI-executed handset acceptance. Candidate CI remains green on unchanged0d15ed5, but that is not full host coverage. Actual canceled-host interruption wording, physical post-update LFS and human locale/RTL review remain open; PR44 stays draft/unmerged and overall goal incomplete.
