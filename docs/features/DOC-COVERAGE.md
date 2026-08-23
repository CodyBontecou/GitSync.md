# GitSync.md — Documentation Coverage Matrix

Maps each `FEATURESET.md` category to documentation surfaces, with gaps/ inaccuracies to fix. Surfaces audited: `README.md`, `site/index.html` (+privacy/terms/blog), App Store metadata (`app-store-input/`, `localization/app-store/`), in-app surfaces (release notes, onboarding copy, PremiumSettingsView disclosures), `docs/` (premium runbooks), `worker/*/README.md`, `oauth-server/`.

Legend: ✅ documented · ⚠️ partial/stale/inaccurate · ❌ undocumented.

| Category | README | Site | App Store/in-app | Notes / action |
|---|---|---|---|---|
| 1 Repos & storage | ✅ overview | ✅ (clone once, multi-vault) | ✅ | Ghost-repo re-add (1.10) undocumented anywhere → add |
| 2 Auth & accounts | ⚠️ OAuth/PAT only; multi-account, per-repo SSH/HTTPS creds, TOFU host-key trust not in README | ⚠️ OAuth/PAT only | ⚠️ 2.5.1 notes cover SSH + multi-account | Site+README lack: per-repo credential methods, host-key trust UX, account switching |
| 3 Git operations | ⚠️ only clone/pull/push/status listed; branches/stash/tags/merge/rebase/revert/staging/discard absent | ✅ checklist covers most (Branch Controls, Push & Pull, Diff) | ⚠️ partial via release notes | README "Key features" list is years stale — biggest gap |
| 4 Conflict resolution | ❌ (README even lists it under "Contributing: Conflict resolution UI" — **wrong**, it shipped in 2.4.7) | ⚠️ "Conflict center" one-liner | ✅ 2.4.7 notes | **README factually wrong** — fix Contributing section |
| 5 Git LFS | ❌ entirely undocumented | ❌ | ❌ (not in release notes read) | Whole category undocumented — hydration, auto-track, locking, SSH LFS auth |
| 6 Editor & files | ⚠️ "edit with any app" only | ✅ editor/file manager/diff | ⚠️ | Site claims "Swift, Python, JavaScript, Markdown, JSON, YAML and **15+ more**" — actual: **11 languages** (incl. bash/html/css/ts) → fix copy |
| 7 Automation (x-callback, Shortcuts) | ✅ x-callback table (pull/push/sync/status + params) | ✅ dedicated section | ✅ 2.4.5 notes | README table lacks `message` param + response params (sha/updated/changes…) → complete it |
| 8 GitSync Assist | ✅ good summary | ⚠️ site still describes **$9.99 one-time purchase**; premium sections stale (per completion audit) | ⚠️ products not yet live in ASC | Site pricing page = biggest stale surface; relay README ✅ excellent |
| 9 Onboarding & UX | ⚠️ | ⚠️ demo | ⚠️ | Demo mode, app tour replay, release-notes sheet mostly implicit |
| 10 Diagnostics & feedback | ❌ | ❌ | ❌ | Debug log viewer, feedback email, privacy-request flow undocumented |
| 11 Analytics | ⚠️ (not mentioned; privacy posture only) | ⚠️ privacy.html | ⚠️ | Event taxonomy + opt-out expectations documented only in code + privacy docs |
| 12 Localization | ❌ (26 languages!) | ❌ | ✅ (metadata pipeline) | Undersold achievement — worth a README/site bullet |
| 13 Platform & compliance | ⚠️ build reqs yes | ✅ privacy/terms | ⚠️ | Privacy manifest data types not surfaced anywhere user-facing |
| 14 Dev infrastructure | ✅ (build, tests, oauth-server, relay) | ✅ blog (Obsidian setup) | — | ✅ strongest-covered area; CI workflow list could be added |

## Priority fixes (documentation backlog)

1. ✅ **README "Key features" rewrite** — done 2026-08-22 (14 verified bullets across all categories, links to FEATURESET.md).
2. ✅ **README Contributing list** — done: replaced shipped items with real gaps (force-push, branch rename, remote checkout, cherry-pick, submodules, editor search/line numbers, iPad split-view).
3. **Site pricing/Assist copy**: replace one-time-purchase description with Assist subscription reality. *(site meta tags + 7 in-page occurrences still say "$9.99 one-time / No subscription"; positioning decision needed — the core app IS paid-up-front, Assist is optional. Also: site never mentions Assist at all; whether/how to market it is a product call.)*
4. ✅ **Site editor claim** — done: feature card + spec table corrected to the actual 11 languages (was "15+"/"20+" in two places).
5. **LFS documentation** — ✅ README section added (hydration, pointer staging w/ confirmation, push guards, locking, endpoints, limitations). Dedicated site page still pending (can be modeled on the README section).
6. ✅ **README x-callback table** — done: `message` param, response params per action, error contract, rename-tolerant staging note.
7. **Document ghost-repo re-add, demo mode, debug log viewer, feedback/privacy-request flows** (user-facing features with zero docs — partially covered by new README bullets; dedicated site/help sections still pending).
8. **Localization** — ✅ README bullet + site "App Languages: 26 localized languages" spec row added.

Also done this pass: README architecture tree updated (all feature-bearing files/workers), "Git Implementation" section corrected (staged-only commits, verified pushes, full op list), inaccurate "Optimized layouts for iPad" claim removed.

**2026-08-22 site audit**: a full page-by-page audit of the site vs. the featureset now lives in [`SITE-AUDIT.md`](SITE-AUDIT.md) — it found and fixed 4 stale-claim clusters on the landing page (GitLab/Bitbucket "do not buy" hedge → self-hosted+SSH reality; conflict-tooling understatement; storage-locations precision; unverifiable blog libgit2 claim), added Shortcuts + iOS-17+ coverage, and queues the remaining P1 (invisible features: LFS, SSH card, rebase, multi-account, local-repo-add, removal behavior, history browser) and P2 (cards/FAQ/blog expansion) work plus 3 product decisions (D-1 Assist positioning, D-2 card-grid growth, D-3 blog cadence).

## Verified stale/wrong claims found during audit

- ~~README: "Conflict resolution UI (currently only fast-forward merges)" under Contributing~~ — **fixed 2026-08-22** (conflicts shipped in 2.4.7; Contributing now lists real gaps).
- ~~README: x-callback table lists only 4 actions w/o `message` param or response payloads~~ — **fixed** (full contract now documented).
- Site: "$9.99 one-time purchase" era copy vs current Assist subscription + paid-up-front app — **still open** (item 3).
- Site: "15+ more" syntax languages vs 11 in `SyntaxLanguage.detect` — **still open** (item 4).
