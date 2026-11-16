# Final provider flow coverage

Entry point: `.argent/e2e/qa-pr47-provider-recovery-final.yaml`; physical iPhone 13 Pro Max; `e2e-execute`. Two unchanged complete passes: `provider-pair2-pass1.json`, `provider-pair2-pass2.json`, 181/181 each. No optional acceptance branches or skipped steps.

Setup: outer launch seeds only the isolated private inventory and owned native working copy. Persistent owned external parent must be empty; QA return-anchor must exist. Baseline child flow proves these conditions and enters QA Documents. Ending move returns the source and empties external parent; no Trash accumulation or other folders touched.

## Navigation identity/readiness map

Every listed destination gate is followed by `await idle`; data/control gates remain separate.

| Transition (including repeated occurrences) | Destination identity / positive readiness evidence |
| --- | --- |
| Outer QA seed launch | Attention 1 |
| Baseline Files launch | Browse View |
| Both baseline Browse-tab selections | DOC.sidebar.item.On My iPhone |
| Both baseline local-location selections | Owned external parent / QA container IDs respectively |
| Enter external parent | Exact owned Actions Menu title; Folder is Empty |
| Enter isolated QA Documents | Exact QA Actions Menu title; owned source and return-anchor IDs |
| More menu | checkmark.circle (Select) |
| Select mode | Select Items; after selection checkmark.circle.platter |
| Both Move chooser presentations | Browse View (Picker) |
| Initial chooser parent hop | Exact owned external-parent folder ID |
| Select initial move destination | Exact owned parent browsing-root ID; filename equals git-tools-fixture |
| Complete outward move | Return anchor remains; same previously visible source folder hidden |
| All four QA resume launches | Attention 1; actual state subsequently checked, not inferred from count |
| Open unavailable vault (before renewal and after real child rename) | vault.reauthorizeFolder |
| Open Settings | Vault settings |
| Reveal Settings recovery | scroll-to repository.reauthorizeFolder; exact button gate |
| Both reauthorization choosers | Browse View (Picker) |
| Both chooser Browse selections | DOC.sidebar.item.On My iPhone |
| Both chooser local-root selections | Exact owned external-parent folder ID |
| Both chooser parent entries | Exact owned parent browsing-root ID |
| Intentionally enter wrong child | Exact child browsing-root ID |
| Reject wrong-root selection | Reauthorization Failed alert |
| Dismiss rejection | repository.reauthorizeFolder |
| Accept correct parent | Exact rendered parent › child path; configured main value |
| Cancel renewed Settings | vault.title; modified README; same previously visible recovery action hidden |
| Open recovered vault (twice, including after restoration) | vault.title; modified README |
| Files launch before rename | Exact owned external-parent Actions Menu |
| All three context-menu long presses | pencil for rename, Move for return |
| Both Rename actions | DOC.inlineRenameField; native auto-selected name gets raw keyboard, then exact resulting folder ID |
| Cold-load renamed-child loss | Attention 1 plus Folder Access Unavailable, then vault.reauthorizeFolder |
| Files launch before restoring child | Exact owned moved-folder ID |
| Restore child then cold-load | Original folder ID, then vault.title and modified README |
| Actual native Pull | Already up to date; modified README still visible |
| Files launch for return | Original owned folder ID |
| Return chooser first parent hop | QA container ID |
| Return chooser Browse hop | DOC.sidebar.item.On My iPhone |
| Return chooser local root | QA container ID |
| Enter return destination | Return-anchor ID; same source folder absent before move |
| Finish return | Folder is Empty in external parent |

## Distinct proof surfaces

- UI checks establish reachable recovery, native picker behavior, unavailable-vs-clone state, cold access and real Pull outcome.
- Whole saved JSON comparisons establish exact configuration/clone metadata retention around real renewal/rename in the first live take.
- Original live round-trip manifest establishes all worktree/index/ref bytes unchanged except legitimate FETCH_HEAD.
- Post-final-pair independent inspection establishes exact note bytes, expected HEAD/ref, nonempty fetch result and no staged changes; it does not pretend index stat-cache bytes are stable across reseeding.
- Unit/OS-boundary regressions cover denied/stale/direct/discovery semantics, not a claim that every provider failure occurred physically.
- Three small-motion readiness warnings are retained. Follow-up Home and native-sidebar inspection found no persistent loading indicator and paired captures were pixel-identical. Their original pixel-level cause remains unestablished; all following positive controls/data gates executed. Idle is not used as acceptance evidence.
