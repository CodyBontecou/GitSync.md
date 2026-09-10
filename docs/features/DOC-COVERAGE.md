# GitSync.md — Documentation Coverage Matrix

Maps each `FEATURESET.md` category to documentation surfaces, with gaps/ inaccuracies to fix. Surfaces audited: `README.md`, `site/index.html` (+privacy/terms/blog), App Store metadata (`app-store-input/`, `localization/app-store/`), in-app surfaces (release notes, onboarding copy, PremiumSettingsView disclosures), `docs/` (premium runbooks), `worker/*/README.md`, `oauth-server/`, `push-worker/README.md`.

Legend: ✅ documented · ⚠️ partial/stale/inaccurate · ❌ undocumented.

| Category | README | Site | App Store/in-app | Notes / action |
|---|---|---|---|---|
| 1 Repos & storage | ✅ overview (incl. ghost-repo re-add + storage-scan rediscovery) | ✅ (clone once, multi-vault) | ✅ | Ghost-repo re-add (1.10) now in README; site still lacks it |
| 2 Auth & accounts | ✅ (Any remote + Multiple GitHub accounts bullets cover SSH/HTTPS creds, TOFU host-key trust, account switching) | ⚠️ OAuth/PAT only | ⚠️ 2.5.1 notes cover SSH + multi-account | README done 2026-08-22 pass; site still lacks: per-repo credential methods, host-key trust UX, account switching |
| 3 Git operations | ✅ ("Full Git toolkit" bullet + Git Implementation section: branches/stash/tags/merge/rebase/revert/staging/discard) | ✅ checklist covers most (Branch Controls, Push & Pull, Diff) | ⚠️ partial via release notes | README done 2026-08-22 pass (matrix synced 2026-09-04) |
| 4 Conflict resolution | ✅ (key-features bullet + Git Implementation conflict sessions) | ⚠️ "Conflict center" one-liner | ✅ 2.4.7 notes | README fixed 2026-08-22 (matrix synced 2026-09-04) |
| 5 Git LFS | ✅ (dedicated section + key-features bullet) | ❌ | ❌ (not in release notes read) | README done (item 5); whole category still undocumented on site — hydration, auto-track, locking, SSH LFS auth |
| 6 Editor & files | ✅ (editor/syntax/file-browser/diff bullets) | ✅ editor/file manager/diff | ⚠️ | Site "15+ more" claim fixed (item 4); actual: 11 languages |
| 7 Automation (x-callback, Shortcuts) | ✅ x-callback table (pull/push/sync/status + params) | ✅ dedicated section | ✅ 2.4.5 notes | README table completed (item 6) — `message` param + response params now documented |
| 8 Background Sync | ✅ current on-device architecture, independent pull/push, two scheduler identifiers, and validation runbook | ✅ included/on-device model and best-effort limits reflected on landing/legal/Obsidian guide | ⚠️ in-app management is current; re-audit App Store metadata against the included, no-entitlement model | Current local gates: `background-sync-validation.md`; signed-device discretionary scheduling and safety remain external evidence |
| 9 Onboarding & UX | ⚠️ (demo mode now in README; tour/release-notes implicit) | ⚠️ demo | ⚠️ | Demo mode README bullet added 2026-09-04; app tour replay + release-notes sheet still implicit |
| 10 Diagnostics & feedback | ✅ (Diagnostics bullet) | ❌ | ❌ | README done 2026-08-22 pass; debug log viewer/feedback/privacy-request still undocumented on site |
| 11 Analytics | ⚠️ (not mentioned; privacy posture only) | ⚠️ privacy.html | ⚠️ | Event taxonomy + opt-out expectations documented only in code + privacy docs |
| 12 Localization | ✅ (Localization bullet, item 8) | ❌ | ✅ (metadata pipeline) | README done (item 8); site still undersold — worth a site bullet |
| 13 Platform & compliance | ⚠️ build reqs yes | ✅ privacy/terms | ⚠️ | Privacy manifest data types not surfaced anywhere user-facing |
| 14 Dev infrastructure | ✅ build/tests plus no-secret Background Sync validation tooling | ✅ blog (Obsidian setup) | — | `scripts/background-sync/` adds static, simulator/LLDB, fixture, persisted-log, and Release artifact receipts |
| 15 Push Sync & quick triggers (widget / Control Center / push-worker) | ✅ one-time GitHub App, alert-plus-background APNs path, and explicit pull-only controls | ✅ landing page plus privacy/terms disclosures | ✅ Settings disclosure and 2.6.0 release-notes entry | App Store metadata and TestFlight/production-APNs validation remain required; the production GitHub App path is active |

## Priority fixes (documentation backlog)

1. ✅ **README "Key features" rewrite** — done 2026-08-22 (14 verified bullets across all categories, links to FEATURESET.md).
2. ✅ **README Contributing list** — done: replaced shipped items with real gaps (force-push, branch rename, remote checkout, cherry-pick, submodules, editor search/line numbers, iPad split-view).
3. ✅ **Site Background Sync copy post-removal pass** — landing/legal/Obsidian-guide copy now reflects the included, on-device scheduled model and separates optional Push Sync relay metadata.
4. ✅ **Site editor claim** — done: feature card + spec table corrected to the actual 11 languages (was "15+"/"20+" in two places).
5. **LFS documentation** — ✅ README section added (hydration, pointer staging w/ confirmation, push guards, locking, endpoints, limitations). Dedicated site page still pending (can be modeled on the README section).
6. ✅ **README x-callback table** — done: `message` param, response params per action, error contract, rename-tolerant staging note.
7. **Document ghost-repo re-add, demo mode, debug log viewer, feedback/privacy-request flows** — ✅ README coverage complete (2026-09-04: demo-mode bullet added, closing the last README gap in this item); dedicated site/help sections still pending.
8. **Localization** — ✅ README bullet + site "App Languages: 26 localized languages" spec row added.

Also done this pass: README architecture tree updated (all feature-bearing files/workers), "Git Implementation" section corrected (staged-only commits, verified pushes, full op list), inaccurate "Optimized layouts for iPad" claim removed.

**2026-09-04 README coverage sync**: this matrix's README column had gone stale after the 2026-08-22/08-30 fixes — rows 2, 3, 4, 5, 6, 10, 12 updated to ✅ and notes adjusted (site/App Store gaps unchanged). Demo mode (FEATURESET 1.14) added to README key features as a verified bullet. **Post-baseline flag — closed 2026-09-04 (cycle-2 baseline refresh)**: the `b911f13` scope ("widget + Control Center pull buttons and push-initiated sync" — `SyncWidget/`, `SharedSources/PullAllControlIntent.swift`, `PushSyncManager`/`SyncRuntimeLocator`/`SyncAppDelegate`, `push-worker/`) is now baselined: FEATURESET category 15 with full file:line evidence in `inventory/widget-push-sync.md`; README gained two key-features bullets, the missing architecture-tree entries (`SyncWidget/`, `SharedSources/`, `PushSyncManager`/`SyncRuntimeLocator` in Services, `SyncAppDelegate.swift` at the app root, `push-worker/`), and the "There is no relay server, no push notification registration" sentence now keeps its on-device truth for Background Sync while pointing to the optional, separately deployed Push Sync relay. **Remaining gaps (honest)**: App Store metadata still needs this feature; Settings, the 2.6.0 in-app release notes, README, and the landing/legal site surfaces now disclose it. **Category-8 follow-up:** the current baseline now describes the removed relay/subscription rows as historical non-features and points release validation to `docs/background-sync-validation.md`; the retained `premium-v1-*` relay material is explicitly superseded provenance.

**2026-08-22 site audit**: a full page-by-page audit of the site vs. the featureset now lives in [`SITE-AUDIT.md`](SITE-AUDIT.md) — it found and fixed 4 stale-claim clusters on the landing page (GitLab/Bitbucket "do not buy" hedge → self-hosted+SSH reality; conflict-tooling understatement; storage-locations precision; unverifiable blog libgit2 claim), added Shortcuts + iOS-17+ coverage, and queues the remaining P1 (invisible features: LFS, SSH card, rebase, multi-account, local-repo-add, removal behavior, history browser) and P2 (cards/FAQ/blog expansion) work plus 3 product decisions (D-1 Background Sync positioning, D-2 card-grid growth, D-3 blog cadence).

## Verified stale/wrong claims found during audit

- ~~README: "Conflict resolution UI (currently only fast-forward merges)" under Contributing~~ — **fixed 2026-08-22** (conflicts shipped in 2.4.7; Contributing now lists real gaps).
- ~~README: x-callback table lists only 4 actions w/o `message` param or response payloads~~ — **fixed** (full contract now documented).
- ~~Site: optional-subscription/relay wording stale after the on-device/included architecture change~~ — **fixed** across landing, legal, and the Obsidian setup guide.
- ~~Site: "15+ more" syntax languages vs 11 in `SyntaxLanguage.detect`~~ — **fixed** (item 4).
