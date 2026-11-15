# Physical iPhone 17 Pro issue QA — 2026-11-13

## Environment and safety boundary

- Target: user's connected **physical iPhone 17 Pro**, iOS **27.0.1**, driven through **Argent 0.32.0** (`xcuitest-runner` discovery).
- Installed app: **GitSync.md 3.0, build 202611071310**, read from `devicectl` installed-app metadata. No app build/install/replacement performed. Source equivalence to the earlier simulator build is not claimed solely from the matching version number.
- Fixture: existing synthetic private remote `CodyBontecou/gitsync-tide-qa`, containing only `README.md` and `daily-note.md`; their synthetic contents were inspected before cloning.
- Created a new empty parent folder through the Files picker: **On My iPhone → Obsidian → GitSync-Issue-QA-20261113**. Cloned the fixture beneath it. A new empty parent avoided the known same-remote overwrite hazard.
- Existing vaults initially showed three and one unpushed changes respectively. No sync-all, push, commit, discard, re-clone, or file mutation was performed on them. A cold-launch home-row reorder caused one observational navigation into an existing vault; it was immediately exited without opening notes or running an operation.
- Used existing account authorization; no credentials were entered, exported or changed. No hosted repository writes. Automatic-sync settings were not changed.

## Confirmed on hardware: #50 survives the redesign

Executed this sequence live with Argent:

1. Add the synthetic repository using its HTTPS URL and the new Files-picked parent folder.
2. Clone completes; the new vault displays **In sync**, branch `main`, last displayed commit `475886f`.
3. Tap **Check for changes**. Observe **Pulling…**, progress, steps and a Cancel control, then **Already up to date** with no error.
4. Pull-to-refresh on the same fixture. Observe refresh/progress completion and **Already up to date**, with no raw cancellation error.
5. Use the vault menu's **Open in Files**. Files shows `daily-note.md` (90 bytes) and `README.md` (74 bytes).
6. In Files, rename **only the newly cloned fixture working-copy folder**, from `gitsync-tide-qa` to `gitsync-tide-qa-renamed`. No parent/container migration, deletion or note edit is involved.
7. Return to GitSync.md. After foreground refresh the vault becomes **Not Cloned**, with the false explanation that it has never been cloned and a **Clone Repository** button. There is no inline reconnect action on this screen.
8. Return to Files. Both note entries remain present at the renamed path with unchanged displayed sizes.
9. Rename the fixture folder back to `gitsync-tide-qa` and restart GitSync.md. After initial status refresh, the fixture still displays **Not Cloned / Clone Repository**. Restoring the original path and restarting does not repair the saved state.

**Verdict:** #50's path-change → false-not-cloned failure is **physically reproduced in the redesigned installed app**. This is an explicit folder rename, not proof of the original Obsidian-update/container-migration mechanism. It demonstrates the same stale/missing-path metadata-loss outcome without touching a real vault.

**The Clone Repository button was not tapped.** Actual phone-side deletion/re-clone was deliberately not exercised. The earlier simulator AppState test independently proves the unsafe same-remote deletion path; this phone run proves the misleading entry state and offered action, not deletion on hardware.

Evidence:

- [Renamed folder still contains both notes](evidence/iphone17-issue-qa-20261113/renamed-files-intact.png)
- [False Not Cloned screen after rename](evidence/iphone17-issue-qa-20261113/false-not-cloned.png)
- [Original folder name restored, app restarted, still Not Cloned](evidence/iphone17-issue-qa-20261113/restored-name-still-not-cloned.png)
- [Accessibility assertion surface](evidence/iphone17-issue-qa-20261113/false-not-cloned.json)

Screenshots were visually inspected. No pixel-diff or file hash/index/HEAD verification on the physical filesystem is claimed. Files listings and displayed sizes are weaker than byte-for-byte verification.

## Other issue relevance from this run

| Issue | Physical-device result |
| --- | --- |
| #38 nested-folder pull cancellation | Fresh clone into a Files-selected subfolder under the local Obsidian folder succeeded. Both explicit button Pull and pull-to-refresh completed normally. The original failure did not reproduce in these two uninterrupted attempts. Cold-launch pull, provider-backed storage, task interruption and large vaults remain untested. |
| #39 typed cancellation | Normal pulls did not display raw cancellation errors. Deliberate pre-update/queued cancellation and manual retry were **not** exercised on the phone; do not substitute this for the passing simulator cancellation tests. |
| #40 bookmark/access recovery | Path-change metadata loss and absent inline reconnect are demonstrated by #50. Invalid/denied/stale security-scope restoration was **not** separately injected on the phone. |
| #41 Obsidian Git plugin/editor interoperability | **Not tested.** Native GitSync Pull is not Obsidian Git Pull. No plugin-version or editor-byte verification claimed. |
| #42 external HEAD/branch | **Not tested on hardware.** No independent Git client changed the fixture's HEAD/branch. Earlier deterministic simulator finding still stands. |
| #43 incomplete Markdown checkout classification | **Not triggered on hardware.** No concurrent checkout failure injected; earlier deterministic foreground presentation finding still stands. |
| #50 button feedback | Explicit Pull immediately exposed Pulling/progress/steps/Cancel and later an up-to-date result. This supports improved feedback for this small fixture, not all controls, large-vault performance or background execution. |
| #50 stale HEAD/ref locks | **Not tested.** No phone process-death or leftover-lock injection. |

## Recording and limitations

The live exploration was recorded before its first in-app action, but it is **not a passing reusable e2e regression**. The diagnostic is saved as [exploration.incomplete.yaml.txt](evidence/iphone17-issue-qa-20261113/exploration.incomplete.yaml.txt), deliberately outside runnable `.argent/e2e/*.yaml` discovery.

Reasons not to replay this take:

- Setup creates/renames folders and clones; replaying blindly against the changed fixture could hit existing files.
- Physical picker snapshots sometimes omitted controls or lagged keyboard geometry. One stale-geometry tap entered an extra URL character; it was immediately removed before submission. A screenshot verified the correction, then keyboard Return submitted the correct URL.
- New Folder and picker Open required screenshot-backed point actions when the physical tree omitted the controls. Menu selector capture also warned about duplicate elements. These are live exploratory exceptions, not portable selector evidence.
- A wait for a separate Clone vault button failed because the flow had already completed cloning and returned Home. That was a wrong test expectation, not a product clone failure.
- An early cold-launch Home snapshot reordered as status scans completed; its stale row coordinates caused the observational wrong-vault navigation described above.
- No manual correction is counted as a replay pass. No two-pass automated QA claim.

Initial runner access was blocked while the phone was unavailable for UI testing; after the user retried, launch and discovery worked. The exploratory artifact retains the tool warnings and unsuccessful gate rather than laundering them into a green test.

## Final state

The newly created QA parent folder and its clone are **retained on the phone for reproduction**. The working-copy folder's original name is restored; its saved GitSync entry remains **Not Cloned**. The phone is left on that QA entry. Do not press Clone Repository to repair it.

No attempt was made to repair the saved record by removing/re-adding it, because this task was QA and retaining the failed state provides useful evidence. Existing real vaults were not synchronized or modified by QA actions. No GitHub issue/PR state or production source was changed.

**Priority recommendation:** fix #50's non-destructive access/path recovery and same-remote re-clone safeguard first. The redesign demonstrably did not remove this data-safety issue.
