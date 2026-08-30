# GitSync.md — Featureset Baseline

**Purpose**: This directory is the canonical, source-verified inventory of every feature GitSync.md supports. It is the baseline against which all product documentation (README, marketing site, App Store metadata, help content) is planned, written, and audited.

**How to use it**:
- `FEATURESET.md` — the master structured featureset (categories → features → status/tiers), the single doc-management baseline.
- `DOC-COVERAGE.md` — matrix mapping every feature category to existing documentation surfaces, exposing gaps and stale claims.
- `inventory/` — deep-dive per-domain inventories with mechanical detail and source-file evidence. These are working papers; the two files above are the managed baseline.

**Status**: v1.0 complete — all 8 inventory domains verified against source; `FEATURESET.md` and `DOC-COVERAGE.md` finalized. Backlog items in DOC-COVERAGE drive the documentation work.

**Maintenance rule**: A feature ships or changes → update the relevant `inventory/*.md` entry and the `FEATURESET.md` row → check `DOC-COVERAGE.md` for surfaces needing updates. Features are only listed here when verified in source (file + symbol evidence cited in the inventory docs).

## Inventory domains

| Domain | File | Status |
|---|---|---|
| Git engine (libgit2 operations) | `inventory/git-engine.md` | ✅ complete |
| Git LFS + SSH (Citadel) + host-key trust | `inventory/git-lfs-ssh-citadel.md` | ✅ complete |
| UI screens & interactions | `inventory/ui-views.md` | ✅ complete |
| Core services (GitHub API, OAuth, Keychain, persistence, highlighting, feedback, logging, config, privacy) | `inventory/state-and-services.md` | ✅ complete |
| AppState orchestration layer | `inventory/state-appstate.md` | ✅ complete |
| Background Sync premium (client + relay + verifier) | `inventory/premium-assist.md` | ✅ complete |
| Automation, x-callback, analytics, release chronology, CI | `inventory/automation-analytics.md` | ✅ complete |
| Infrastructure (OAuth server, site, site-router, fastlane, localization, distribution, worker topology) | `inventory/infrastructure.md` | ✅ complete |

## Top-level taxonomy (draft — finalized in FEATURESET.md)

1. **Repositories & storage** — multi-repo management, clone (GitHub/self-hosted/SSH/public/local-existing), custom save locations, vault move, ghost "previously cloned" re-add, local-files retention on removal.
2. **Authentication & accounts** — GitHub OAuth (multi-account), PAT, per-repo HTTPS token, per-repo SSH key, anonymous; Keychain storage; TOFU SSH host-key trust.
3. **Git operations** — fetch, safe pull (FF-only), pull-with-rebase, commit (staged-only), push (verified), branch CRUD, merge + conflicts, revert, stash, tags, diff, history, status, staging/unstaging, discard.
4. **Conflict resolution** — sessions from merge/rebase/revert/cherry-pick, ours/theirs/manual, side-by-side editor, rename/rename + delete/modify handling.
5. **Git LFS** — hydration after clone/pull, pointer staging with auto-track policy + .gitattributes management, object upload before push, file locking + push guard, self-hosted endpoints, large-blob guard.
6. **Editor & files** — file browser (create/rename, status badges), code editor (syntax highlighting, binary fallback), diff viewer, Files-app interop (Open in Files), external editor compatibility (real .git).
7. **Automation & integrations** — x-callback-url API, pull/push/sync App Intents with fail-closed publishing, and best-effort remote-notification/BGProcessing Background Sync wakes.
8. **Background Sync (premium subscription)** — independently controlled fail-closed automatic pull and separately consented automatic commit/push, including safe push-only mode without checkout, per-repo enrollment, network/power policies, health/attention surfacing, best-effort iOS processing, relay + StoreKit verification, and privacy guarantees.
9. **Onboarding & account UX** — tour, sign-in flows, default save location, demo mode, release notes (Notelet), StoreKit review prompts.
10. **Diagnostics & feedback** — debug log viewer (filter/share/copy/clear), in-app feedback email, Discord community.
11. **Analytics (onboarding funnel)** — opt-out-able, coarse event capture, Cloudflare Worker transport.
12. **Localization & accessibility** — 26 locale catalogs and App Store metadata localization; current compiler extraction is complete, while new Background Sync/safety entries remain an explicit human-translation release gate.
13. **Platform & compliance** — privacy manifest, entitlements, Files-app document provider storage, iPad (single-column).
14. **Developer infrastructure** — libgit2 xcframework build w/ SSH, CI XCTest gate, fastlane, oauth-server, site + site-router, marketing capture (DEBUG), pricing scripts.

Feature chronology (version-by-version shipped features: 2.4.1, 2.4.5, 2.4.7, 2.5.1) is in `inventory/automation-analytics.md` §5.
