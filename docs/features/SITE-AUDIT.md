# Site & Landing Page Audit — vs. Featureset Baseline

**Date**: 2026-08-22 · **Baseline**: `FEATURESET.md` (117 verified features) · **Scope**: `site/index.html`, `site/blog/` (2 posts + index), `site/privacy.html`, `site/terms.html`, `site/sitemap.xml`.

**Status of fixes**: P0-1, P0-2, P0-3, P0-6 ✅ applied 2026-08-22 · P1-5 (Shortcuts card + spec), P1-10 (iOS 17+) ✅ applied · D-2 executed: grid 9→12 cards (Self-Hosted & SSH, Git LFS, Conflict Resolution) + fold-ins (multi-account → card 02, history → card 05, rebase/verified-push → card 07) + hero strip additions. P1-8/P1-9 FAQ items added. D-3 executed: three blog posts — `self-hosted-git-ssh-ios.html`, `git-lfs-ios.html`, `shortcuts-automation-ios.html` — blog index + sitemap (9 URLs) updated. **Third pass**: P1-3 LFS FAQ entry ✅ · P2-8 landing JSON-LD featureList expanded to 11 entries + OS requirement ✅ · P2-3 LFS + Shortcuts blog posts ✅. **Background Sync launch pass (2026-08-30)**: the prior subscription-silent D-1 decision was superseded; P0-4/P0-5 and P2-2/P2-6 are ✅ fixed with optional-subscription copy that preserves the one-time manual Git purchase, describes independently controlled automatic pull/push behavior plus separately consented default-off publishing, and qualifies iOS background timing. **Remaining**: ~~P2-7 screenshot refresh~~ **✅ DONE 2026-08-23** — ran `scripts/capture-marketing.sh` (iPhone 17 Pro Max + iPad Pro 13" simulators, iOS 26.5, demo-seeded Brutal UI, en-US): 10 iPhone + 4 iPad current-generation captures. Wired in: hero + band slot 1 = repo list; band = repo list / vault / Git control sheet; workflow phone-frame = diff view (new `diff-view.png`); obsidian blog inline assets replaced; byte-duplicate `04-home-both-cloned.png` deleted; 4 current-UI iPad shots staged at `site/screenshots/ipad-*.png` (unreferenced — available for a future iPad section; pre-Brutal `screenshots/ipad/` shots now obsolete). Img width/height attrs updated to 1320×2868.

**Bonus fix found via capture**: the app UI itself promised "Delete, rename, and **move** files" (VaultView.swift) but no move-file feature exists — fixed to "Create, rename, and delete files" (Swift + Localizable.xcstrings key swapped with hand-written translations for all 25 locales, verified in re-captured shots). Full-locale re-capture for App Store use: run the script without `MARKETING_LOCALE_PAIRS` to regenerate all 26 locales.

**Method**: extracted every text claim from each page; mapped coverage per FEATURESET category; verified each suspicious claim against source/inventories (evidence cited). Previously-fixed claims (language counts, auth spec row, data-collection row, 26-language row) are excluded — see `DOC-COVERAGE.md`.

---

## Summary

| Severity | Count | Meaning |
|---|---|---|
| **P0 — factually wrong/stale, actively costing value** | 5 | Shipped features described as unsupported/uncertain; copy contradicting the app's own Terms |
| **P1 — shipped features invisible** | 10 | Whole capabilities absent from the landing page |
| **P2 — expansion opportunities** | 8 | New sections, cards, FAQs, blog posts |
| **Decisions needed** | 3 | Product/positioning calls before copy can be finalized |

---

## P0 — Factual fixes (wrong or stale today)

### P0-1. FAQ "Does it support GitLab or Bitbucket?" actively discourages purchase of a shipped feature
**Current text**: *"GitHub is the first-class supported flow advertised on this page. Other HTTPS Git remotes may depend on manual remote/PAT support in the current App Store build, so **do not buy solely for GitLab or Bitbucket unless you have confirmed that workflow**."*
**Reality**: v2.5.1 shipped full self-hosted support — manual remotes (HTTPS/git://), **SSH remotes incl. custom ports** (`git@host:path`, `ssh://git@host:2222/…`), Ed25519/ECDSA/RSA keys, TOFU host-key trust with fingerprint prompts, per-repo credential methods. The app's own June-2026 blog guide documents `git@git.example.com` + SSH private key setup for Gitea/Forgejo/GitLab/Bitbucket. The 2.5.1 release notes were localized into 26 languages for exactly this feature.
**Evidence**: `inventory/git-engine.md` §28 (auth callbacks), `git-lfs-ssh-citadel.md` §9–11 (SSH creds/trust), `automation-analytics.md` §5 (2.5.1 chronology), `ui-views.md` §5.3 (auth method picker).
**Fix**: rewrite the answer to state GitLab/Bitbucket/Gitea/Forgejo support via manual URL + HTTPS token or SSH key; keep GitHub-browse as the "first-class" convenience differentiator.

### P0-2. Integrations section hedges the same way
**Current text**: *"GitHub OAuth and Personal Access Token flows are the supported path advertised here. **Other HTTPS Git remotes depend on the current app build's manual remote sup[port]**…"* (§005 "Git remotes" card).
**Fix**: state plainly: GitHub (browse + OAuth/PAT), self-hosted HTTPS, git://, SSH (Ed25519/ECDSA/RSA, host-key verification), public/no-auth, and existing local repositories.

### P0-3. Conflict FAQ understates the shipped conflict tooling
**Current text**: *"includes conflict-resolution flows for common pull/merge cases. If a conflict is too complex, you still have a real Git working copy…"*
**Reality** (2.4.7): full **Conflict Center** — per-file ours/theirs/manual, **3-way side-by-side editor** (ancestor/ours/theirs, binary detection), rename/rename path picker, delete/modify classification, merge complete/abort, **rebase continue/abort**, plus explicit **pull-with-rebase**.
**Evidence**: `inventory/ui-views.md` §9.3, §13; `git-engine.md` §7, §15–17.
**Fix**: describe the conflict center accurately; keep the desktop-repair sentence only as an edge-case note (e.g., exotic octopus conflicts).

### P0-4. ✅ Fixed — subscription answer now distinguishes base app and optional Background Sync
**Current text**: *"$9.99 one-time: no subscription and unlimited repositories"* (plus 7 more "No subscription" instances: OG/twitter meta, hero strip ×2, price card, closing CTA).
**Reality**: `site/terms.html` (§"Background Sync Safety and Subscription Terms") and `privacy.html` already document the **optional Background Sync auto-renewable subscription**. The absolute "no subscription" copy contradicts the same domain's legal pages. (Note: per `docs/premium-v1-completion-audit.md`, Background Sync products are **not yet live in ASC** — so today's buyer literally cannot buy a subscription, but the app build contains the Background Sync UI.)
**Applied 2026-08-30 and superseded by the bidirectional copy pass**: landing copy says the full manual Git client is a one-time purchase and the optional Background Sync subscription adds best-effort independently controlled automatic pull/push attempts, with publication available only under separate default-off consent. No subscription is required for manual Git, Shortcuts, or callbacks.

### P0-5. ✅ Fixed — cloud-dependency copy now includes Background Sync relay nuance
**Reality**: true for all manual Git; **false as an absolute** for Background Sync subscribers (opt-in relay handles wake hints only — no repo content/credentials, per privacy policy).
**Applied 2026-08-30**: "Manual Git: device→remote, no server. Optional Background Sync: opt-in relay handles wake hints and minimal operational metadata only — never repository content or credentials."

### P0-6 (minor). Spec "Storage Locations: App Documents · iCloud Drive · OneDrive"
Naming OneDrive specifically is arbitrary (any Files-provider location works, incl. USB/external on iPad). **Fix**: "App Documents · any Files-app location". Also consider adding the default path (`On My iPhone › GitSync.md`) since the FAQ and blog lean on it.

---

## P1 — Shipped features invisible on the landing page

| # | Feature (evidence) | Where it could live |
|---|---|---|
| P1-1 | **SSH remotes + host-key TOFU trust + Ed25519/ECDSA/RSA** (2.5.1) | New capability card "Self-hosted & SSH"; FAQ; spec row "SSH: Ed25519/ECDSA/RSA · host-key verification" |
| P1-2 | **Self-hosted Git** (Forgejo/Gitea/GitLab/Bitbucket manual remotes) (2.5.1) | Same card; fixes P0-1/2 |
| P1-3 | **Git LFS** — hydration, auto-track prompts, locking, push guards, self-hosted endpoints | Capability card + FAQ; blog guide (P2-3) |
| P1-4 | **Pull with rebase** (2.4.7) | Card 07 expansion ("Pull — fast-forward or rebase") ; FAQ P0-3 rewrite |
| P1-5 | **Apple Shortcuts / App Intents** — "Pull All Repositories", "Pull Repository", Siri phrases, Personal Automations (2.4.5) | §006 Automation: add "Apple Shortcuts" beside x-callback; spec row "Automation: x-callback-url + App Shortcuts" |
| P1-6 | **Multi-account GitHub** — switcher, per-account repo visibility (2.5.1) | Card 08 or FAQ ("Can I use multiple GitHub accounts? Yes…") |
| P1-7 | **Commit history browser + commit detail** (author/committer, parents, changed files) | Card 05/06 expansion or "Review before you push" workflow step |
| P1-8 | **Add existing local repository** (folder picker, `.git` validation) | FAQ ("I already have a repo in Files — yes, add it in place") |
| P1-9 | **Safer repo removal** — remove-keeps-files vs explicit delete (2.5.1); plus **previously-cloned one-tap re-add** | FAQ ("Removing a repo never deletes your files by default") |
| P1-10 | **Minimum iOS version** — not stated anywhere on the site (app requires iOS 17+) | Spec row "Requires iOS 17+"; footer near Buy button |

---

## P2 — Expansion opportunities

1. **New capability cards** to round the grid to 12: "Self-hosted & SSH", "Git LFS", "Shortcuts & Automations", "Conflict Resolution" (promote from card-07 fragment). Grid is `002 — Capabilities` (currently 9).
2. ✅ **Background Sync marketing**: capability card, specification row, FAQ, pricing notes, metadata descriptions, and CTAs now mirror the app's honest framing (best-effort wake hints → clean fast-forward pulls only; never stages/commits/merges/rebases/pushes; iOS controls timing).
3. **Blog pipeline** (index already says "More soon"): (a) *Self-hosted Git (Forgejo/Gitea) over SSH on iOS* — the 26-locale 2.5.1 release is the hook; (b) *Git LFS vaults/media on iOS*; (c) *Automate pulls with Apple Shortcuts*; (d) release writeups.
4. **FAQ additions**: SSH host-key trust ("first connect shows a SHA-256 fingerprint you approve; changes are blocked"), LFS ("downloaded automatically; you're prompted before big binaries are tracked"), multi-account, Background Sync, local-repo-add, removal behavior.
5. **Spec table rows**: "Requires iOS 17+", "Pull modes: fast-forward · rebase", "SSH keys: Ed25519 · ECDSA · RSA (host-key verification)", "Git LFS: hydration · locking · auto-track", "Automation: x-callback-url · App Shortcuts".
6. ✅ **OG/twitter meta descriptions** now distinguish the $9.99 one-time manual Git client from the optional Background Sync subscription.
7. **Screenshot freshness check**: `site/screenshots/` alt texts describe current screens, but confirm visuals match the shipped "Brutal" design system and latest UI (conflict editor, branches/tags sheet are strong candidates to add).
8. **Trust/SEO nits**: blog Feb-2026 post's line "the same C library that powers GitHub Desktop" is unverifiable (GitHub Desktop ships git CLI via dugite; source check inconclusive) — replace with a safe formulation ("libgit2, the open-source C implementation of Git, embedded in countless clients"). Consider `lastmod` refresh + JSON-LD `SoftwareApplication` with `featureList` when cards land.

---

## Decisions needed (blocking final copy)

- **D-1 Background Sync positioning — SUPERSEDED 2026-08-30: market it as optional.** The site now clearly separates the $9.99 one-time manual Git client from the optional auto-renewable Background Sync subscription. Copy describes clean fast-forward attempts, separately consented default-off stage/commit/push attempts, best-effort GitHub wake hints, foreground reconciliation, and iOS-controlled timing; it never presents background execution as guaranteed or real time.
- **D-2 Card grid — DECIDED 2026-08-22: grow 9 → 12 cards** (Self-hosted & SSH, Git LFS, Conflict Resolution) + fold smaller features into existing cards and FAQ.
- **D-3 Blog — DECIDED 2026-08-22: proceed.** Flagship post (self-hosted Git over SSH) written first; LFS and Shortcuts guides queued behind it.

---

## Coverage matrix (FEATURESET category → landing/blog/FAQ)

| Category | Landing cards | Spec table | FAQ | Blog | Verdict |
|---|---|---|---|---|---|
| 1 Repos & storage | ✅ (multi-repo, custom location) | ⚠️ storage row imprecise | ✅ iCloud | ✅ | Local-repo-add + removal + ghost re-add missing |
| 2 Auth & accounts | ⚠️ OAuth/PAT only; hedged | ✅ (fixed earlier) | ❌ stale hedge | ✅ SSH documented | **SSH/multi-account invisible on landing** |
| 3 Git operations | ✅ most | ✅ | ⚠️ conflicts understated | ✅ | Rebase + history browser missing |
| 4 Conflict resolution | ⚠️ fragment of card 07 | — | ❌ understated | — | **Invisible as a capability** |
| 5 Git LFS | ❌ | ❌ | ❌ | ❌ | **Entire category absent** |
| 6 Editor & files | ✅ | ✅ (fixed) | — | ✅ | Good |
| 7 Automation | ⚠️ x-callback only | ⚠️ x-callback only | — | ✅ | **Shortcuts absent** |
| 8 Background Sync | ✅ optional capability | ✅ qualified pricing/relay rows | ✅ behavior + subscription answer | ✅ Obsidian guide | Independent pull/push controls, separately consented publishing, best-effort delivery, and iOS timing are explicit |
| 9 Onboarding/UX | — | — | — | — | Demo-mode/ghost re-add optional mentions |
| 10 Diagnostics | ❌ | — | — | — | Optional support angle |
| 11 Analytics/privacy | — | ✅ (fixed) | ✅ | — | ✅ (privacy policy current) |
| 12 Localization | — | ✅ (added) | — | — | Could be a marketing point |
| 13 Platform | ✅ iPhone/iPad | ⚠️ no iOS version | ✅ | ✅ | Add iOS 17+ |
| 14 Infra/credibility | ✅ source callout | ✅ | ✅ content-path boundary | — | Background Sync relay nuance added |

**Verified accurate elsewhere**: hero claims, price strip mechanics, Keychain claims, no-middleman architecture (for manual Git), 5-step workflow, Obsidian use-case block, x-callback quick-start + params (now complete), both blog posts' technical content (except the libgit2/GitHub Desktop line), sitemap URLs, terms.html (already Background Sync-aware), privacy.html (already covers analytics + Background Sync + web analytics gate).
