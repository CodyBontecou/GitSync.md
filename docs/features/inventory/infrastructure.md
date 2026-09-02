# GitSync.md — Supporting Infrastructure Feature Inventory

Domain: OAuth server, marketing site, site-router, localization, App Store distribution, fastlane, build/CI scripts. App: iOS "GitSync.md", bundle `bontecou.Sync-md`, Apple team `67KC823C9A`, App Store ID `6758960270`.

---

## 1. GitHub OAuth Server (`oauth-server/`, Vercel)

**Files:** `oauth-server/api/auth/login.ts`, `oauth-server/api/auth/callback.ts`, `oauth-server/api/health.ts`, `oauth-server/vercel.json`

### GET /api/auth/login (`login.ts`, 25 lines)
- Requires env `GITHUB_CLIENT_ID` (500 if missing).
- `state` = incoming `?state` query param OR generated fallback `Math.random().toString(36).slice(2) + Date.now().toString(36)`.
- Derives base URL from `x-forwarded-proto` / `x-forwarded-host` headers.
- 302 redirect to `https://github.com/login/oauth/authorize` with params: `client_id`, `redirect_uri=<base>/api/auth/callback`, `scope="repo user:email"`, `state`.
- **No PKCE.** State is generated but never validated on callback (see Gaps).

### GET /api/auth/callback (`callback.ts`, 76 lines)
- Reads `?code` (400 if missing) and optional `?state`.
- Server-side POST to `https://github.com/login/oauth/access_token` (JSON, `Accept: application/json`) with `client_id`, `client_secret` (env `GITHUB_CLIENT_SECRET`), `code`.
- On success: 302 redirect to iOS custom scheme `syncmd://auth?token=<github access token>&state=<state>` — intercepted by `ASWebAuthenticationSession` on device. The GitHub token transits a URL but not the GitHub-side browser; it is a full-privilege `repo user:email` token.
- Error paths: 400 with GitHub error/description; 500 "No access token in response" / "Token exchange failed".
- **No state verification** — the returned `state` is echoed to the app but never checked against the login-issued value.

### GET /api/health (`health.ts`)
- JSON `{status:"ok", hasClientId, hasClientSecret}` — credential presence probe.

### Config
- `vercel.json`: single rewrite `/api/(.*) → /api/$1`. Secrets: `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`.

**User-visible behavior:** In-app "Sign in with GitHub" opens a web session; after authorize, the app is relaunched via `syncmd://auth` already authenticated. No user interaction beyond GitHub's consent screen.

---

## 2. Marketing Site (`site/`, static, deployed to Cloudflare Pages as `syncmd.pages.dev` behind the router)

**Files:** `site/index.html`, `site/privacy.html`, `site/terms.html`, `site/sitemap.xml`, `site/robots.txt`, `site/blog/{index,obsidian-git-iphone-ipad,obsidian-git-ios-setup}.html`, `site/screenshots/*`, favicons.

### index.html — feature claims (verbatim-ish bullets)
Title: "GitSync.md — Real Git for Obsidian and code on iPhone/iPad". Tagline: "A Git workflow you can actually finish on iOS." / "The paid-app checklist: Git, editor, diffs, automation."

Nine numbered feature cards:
1. **Real Git** — "Powered by libgit2. Actual .git directories, real commit history, real branches. Your working copy stays standard Git on disk."
2. **Private Repo Ready** — "Sign in with GitHub OAuth or paste a Personal Access Token. Credentials are stored in Keychain and used from your device to the remote."
3. **Code Editor** — syntax-highlighted editor; Swift, Python, JavaScript, Markdown, JSON, YAML and 15+ more; VSCode-style Dark+ and Light+ themes.
4. **File Manager** — hierarchical browser with git-status badges; create/rename/delete files in-app.
5. **Diff Viewer** — line-by-line unified diffs, color-coded add/delete, hunk headers, counts, one-tap revert per file.
6. **Branch Controls** — create/switch/merge/delete branches; stash with messages; annotated tags; single Git control sheet.
7. **Push & Pull** — upstream workflow (pull, stage files, commit message, push); conflict center with ours/theirs/manual strategies.
8. **Multi-Repo** — per-repo branch, author identity, custom storage location, sync state.
9. **x-callback-url** — Obsidian/Shortcuts call `syncmd://x-callback-url/sync`; scheme stays `syncmd://` for compatibility.

"Clone to push in five steps": Authenticate → Clone → Browse → Edit → Sync.
Audience taglines: "Keep your vault in Git, not another subscription silo." / "Make the one-line fix without opening a laptop." / "Let iOS apps edit the files; let GitSync.md handle Git."
**Quick-start URL / x-callback API documented on the site:** actions `/pull` (fetch + fast-forward), `/push` (stage all, commit, push), `/sync` (pull then push, recommended), `/status` (branch/SHA/change count); optional params `message`, `x-success`, `x-error`.
Positioning: "Under the hood, without a hidden sync layer." / "Buy once. Clone unlimited. Push from iOS." (paid-app positioning).

### privacy.html, terms.html — standard privacy/terms pages (part of sitemap).

### Blog posts
- "How to Use Obsidian Git on iPhone and iPad" (priority 0.9, with repo-list/vault-workflow/sync-actions images)
- "How to Set Up GitSync.md with Obsidian Git on iOS" (priority 0.8)

`sitemap.xml` lists `/`, `/blog/`, both posts, `/privacy.html` under `https://gitsyncmd.app/`.

---

## 3. Site Router Worker (`site-router/`) — campaign attribution + reverse proxy

**Files:** `site-router/src/index.ts` (301 lines), `wrangler.toml`, `campaign-links.mjs` (shared slug config), `ATTRIBUTION.md`, `migrations/0001_campaign_clicks.sql`, `scripts/list-campaign-links.mjs`

Worker `gitsyncmd-site-router`, routes (custom domains): `gitsyncmd.app`, `www.gitsyncmd.app`, `gitsyncmd.isolated.tech`, `syncmd.isolated.tech`.

### Routing logic (src/index.ts)
- Non-canonical hostname → 301 redirect to `gitsyncmd.app`.
- `GET /health` → `{ok:true, service:"gitsyncmd-site-router"}`.
- `GET|HEAD /v/<slug>` → **campaign redirect**:
  - Slug grammar: `<platform>-<angle>-<sequence(3 digits)>` (regex `^([a-z]{1,4})-([a-z0-9]+)-([0-9]{3})$`) plus explicit alias `web-home`.
  - Platforms: `tt` TikTok, `ig` Instagram Reels, `yt` YouTube Shorts, `x` X/Twitter, `th` Threads, `web` Website.
  - Angles → Custom Product Pages (CPP): `obsidian`→`CPP_OBSIDIAN_PPID` (6d42eefc…), `gitclient`→`CPP_GITCLIENT_PPID` (76f9ef64…), `selfhosted`→`CPP_SELFHOSTED_PPID` (874af699…), `commits` (alias of gitclient), `privacy` (alias of selfhosted), `homepage`→`CPP_HOMEPAGE_PPID` (unset).
  - Redirect 302 to `https://apps.apple.com/us/app/gitsync-md/id6758960270` with `ppid` (if configured), `pt` (Apple provider token secret), `ct=<platform>_<angle>_<seq>`, `mt=8`.
  - Click telemetry: `console.log` structured JSON event (`gitsyncmd_campaign_click` with slug, tokens, referrer host, UA-derived client signal, `cf.country`, hasProviderToken/hasCustomProductPage) and, for GET, async D1 insert into `campaign_clicks` (D1 `gitsyncmd-campaigns`, id `0b2b8ac9-…`) via `ctx.waitUntil`.
  - If `APPLE_PROVIDER_TOKEN` missing, response header `x-gitsyncmd-attribution-warning` is set.
  - Unknown slug → 404 text "Unknown GitSync.md campaign link."
- Everything else → **reverse proxy to `syncmd.pages.dev`** with `X-Forwarded-Host: gitsyncmd.app` (this is how the static site is served from the canonical domain).

`scripts/list-campaign-links.mjs` enumerates valid slugs. ATTRIBUTION.md documents the whole shortlink/CPP matrix.

**User-visible:** short links like `gitsyncmd.app/v/yt-obsidian-001` open the matching App Store custom product page with Apple campaign analytics attached; the marketing site loads at `gitsyncmd.app`.

---

## 4. Fastlane (`fastlane/`)

**Files:** `Fastfile` (120 lines), `Appfile`, `metadata/en-US/release_notes.txt`, `.env.example` (only `OPENAI_API_KEY=`), `Preview.html`, `report.xml`. **No Matchfile exists** — signing is automatic (see `release` lane export options).

- `Appfile`: app_identifier `bontecou.Sync-md`, apple_id `bontecouc@gmail.com`, team_id `67KC823C9A`.
- Private lane `asc_api_key`: App Store Connect API key from `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_FILEPATH` (duration 1190 min).
- Hardcoded in Fastfile: `app_version = "2.4.4"`, en-US release notes (Git author setup fix), submission info `add_id_info_uses_idfa: false`, `export_compliance_uses_encryption: false`.

Lanes (all `platform :ios`):
1. **upload_metadata** — ASC API key; `upload_to_app_store` skip binary+screenshots, metadata only, no submit, no auto-release.
2. **upload_screenshots** — screenshots only (`sync_screenshots: true`, no overwrite).
3. **upload_all** — metadata + screenshots, overwrite screenshots, no submit.
4. **submit** — metadata only, `submit_for_review: true`.
5. **release** — `build_app` (project `Sync.md.xcodeproj`, scheme `Sync.md`, app-store export, teamID 67KC823C9A, automatic signing, uploadSymbols) then upload metadata+screenshots with `submit_for_review: true` and screenshot overwrite. Full end-to-end release lane.

---

## 5. App Store metadata input (`app-store-input/`, localization pipeline output `localization/app-store/`)

- `brand.json`: appName GitSync.md; colors primary `#050505`, secondary `#F7F4EE` (warm paper), accent `#007AFF`; "system + mono" fonts; tone "premium, technical, minimal, Obsidian, markdown, real Git, privacy-minded". `brand.example.json` is the template.
- `screens.json`: 3 custom-product-page screenshot specs (slugs `01-sync-obsidian-with-git` "Sync Obsidian with Git", `02-real-git-for-your-vault` "Real Git for your vault" — "A real .git directory on-device — no proprietary sync layer or lock-in", `03-review-before-push` "Review before you push"), each pairing a real screenshot path with an AI-image-generation prompt for abstract backgrounds (paper-and-ink palette, no text/logos).
- `localization/app-store/metadata/{current,proposed}` — ASC metadata snapshots (app-info + version). `localization/app-store/release-notes/ssh-forgejo/` — release notes in **26 locales** (en-US + 25 translations) announcing SSH support: bundled SSH transport for iPhone/iPad, Ed25519/ECDSA/RSA keys, SSH host-key fingerprint trust prompt, changed-host-key blocking, remote URL / SSH credential editing in repo settings.
- `localization/reports/` — 16 JSON audit artifacts (catalog audit, source-string audit, release-notes audit, screenshot audit/validation, ASC read-only preflight/postflight, dark-campaign dry-run/upload, approved apply/upload) — an evidence trail for guarded ASC mutations.

---

## 6. Scripts (`scripts/`)

### `set_price_paid.py`
Sets app price via App Store Connect REST API (default `PRICE=9.99` USD, base territory USA). Mints ES256 JWT from a local `.p8` (hardcoded key ID `T7KGDK4Y4V`, issuer `6c3b3640-…`, key path `/Users/codybontecou/.private_keys/AuthKey_T7KGDK4Y4V.p8`). Paid-app pricing control.

### `build-libgit2-ios-ssh.sh`
Rebuilds `libgit2.xcframework` for iOS with SSH transport: compiles **OpenSSL 3.3.2** (static libs) → **libssh2 1.11.1** → **libgit2** with `-DUSE_SSH=libssh2 -DUSE_HTTPS=SecureTransport`, verifying `HAVE_LIBSSH2_MEMORY_CREDENTIALS:INTERNAL=1` in the CMake cache (app supplies SSH credentials from memory — no agent/keychain at C level) and failing the build if the "without SSH support" stub string is present in `libgit2.a`. Produces iOS xcframework slices (device+sim).

### `capture-marketing.sh`
Deterministic localized App Store screenshot capture via simulator: 10 iPhone + 4 iPad images per locale across **26 locales** (`ar:ar-SA da:da de:de-DE en:en-US es:es-ES fi:fi fr:fr-FR he:he hu:hu id:id it:it ja:ja ko:ko nb:no nl:nl-NL pl:pl pt-BR:pt-BR ru:ru sv:sv th:th tr:tr uk:uk vi:vi zh-Hans:zh-Hans zh-Hant:zh-Hant`), builds the app for iOS 26.5 simruntime, creates temp devices, captures into gitignored `marketing/`. Locale/form-factor/timeouts overridable via env.

### `scripts/app-store-images/` (TypeScript)
AI-assisted App Store marketing image draft generator: inspects repo for brand colors/copy/screenshots, composites real UI screenshots into a phone frame over OpenAI-generated abstract backgrounds (`--generate`; dry-run default writes plan/manifest only). Needs `OPENAI_API_KEY`.

### `scripts/localization/` — guarded localization pipeline (Python; requirements.txt; ~20 scripts)
Pipeline order (per `localization/README.md`): `translate_catalog.py` (machine translation, providers google/alibaba/iciba/sogou via `translators`; resumable, never overwrites existing values; masks format specifiers, App Shortcut params, URLs, product terms) → `sync_catalog_from_stringsdata.py` (xcstrings → catalog) → `audit_catalog.py` / `audit_source_strings.py` (with `source-string-allowlist.json`, `intentional-equivalents.json`, `manual-overrides.json`) → `prepare_release_notes.py` + `audit_release_notes.py` → `prepare_next_version_metadata.py` → `audit_app_store_metadata.py` → `capture-marketing.sh` → `audit_screenshots.py` (+ `ocr_forbidden_tokens.swift` — OCR check that screenshots contain no forbidden/untranslated English tokens) → `validate_screenshots_with_asc.py` → `make_contact_sheets.py`. Dark-campaign track: `build_dark_campaign_queue.py`, `generate_campaign_copy.py`, `make_dark_campaign_contact_sheets.py`, `validate_dark_campaign.py`. ASC mutation scripts (`apply_approved_version_localizations.py`, `apply_approved_screenshots.py`) refuse to run without explicit confirmation flags; `asc_read_only_preflight.py` / `asc_read_only_after_apply.py` / `asc_read_only_postflight.py` verify ASC state before/after.

---

## 7. Localization

- **Languages:** English + 25 translations = 26 locales: ar, da, de, es, fi, fr, he, hi, hu, id, it, ja, ko, nb, nl, pl, pt-BR, ru, sv, th, tr, uk, vi, zh-Hans, zh-Hant.
- `Sync.md/Localizable.xcstrings`: **949 active source keys** across English plus 25 non-source locales. Compiler extraction coverage is complete, but 57 source entries (1,425 locale cells) still await human translation and review; `localization/reports/catalog-audit.json` intentionally records this failing release gate.
- `ADD_TRANSLATIONS_PROMPT.md`: parallel-agent workflow — one agent per locale writes `Sync.md/<locale>.lproj/Localizable.strings`; Xcode 16 `PBXFileSystemSynchronizedRootGroup` auto-discovers files (no pbxproj edits). Its 173-key note is historical; use the current catalog audit rather than that count.
- Runtime→ASC locale mapping: ar→ar-SA, de→de-DE, es→es-ES, fr→fr-FR, nl→nl-NL, nb→no, others identical.

---

## 8. Marketing assets (`marketing/`, gitignored)
- `app-store-localization-tests/` — per-locale screenshot experiments (e.g., es-ES iPad dark-v2 5-frame set: "Real Git", "control total repos", "flujo Git", "revisa cambios") with manifests.
- `app-store-dark-campaign-v1/`, `iphone/`, `iPad/` screenshot sets; `contact-sheets/` — per-locale × form-factor JPEG contact sheets (uk, tr, pt-BR, zh-Hant, vi, he, fr-FR, de-DE, …) for human review of 26-locale captures.
- Repo root also has `screenshots/` and sales report TSVs (`sales_report_2026-03-29_SALES.tsv[.gz]`).

---

## 9. CI Workflows (`.github/workflows/`)

1. **xctest.yml** — push to main + PRs. Runs `SyncMDTests` unit tests on `macos-26` runner, auto-selecting an iPhone simulator from the newest runtime (iOS 26.2 simruntime has a libswift_Concurrency crash workaround comment). Gate for app code.
2. **build-number-guard.yml** — push to main + PRs. Fails if `CURRENT_PROJECT_VERSION` in `project.pbxproj` is not a 12-digit `YYYYMMDDHHMM` timestamp. Rationale: a small-int build number would defeat any build-number-based legacy-unlock threshold (defense-in-depth for the freemium cutoff).
3. **premium-workers.yml** — push/PR touching `worker/onboarding-analytics/**`. Matrix over onboarding-analytics: typecheck/tests/migrations validation per worker. Gate for infra code. (The premium-relay and storekit-verifier workers were removed when Background Sync moved fully on-device.)
4. **announce.yml** — `repository_dispatch` type `asc-approved` (from a central `asc-webhook-worker`) + manual dispatch with dry-run default. After Apple approves: promotes the draft GitHub release for `v<version>` to published and triggers an `llm-wiki` launch checklist scaffold. Secrets: `INTERNAL_RELEASE_API_TOKEN`, `LLM_WIKI_DISPATCH_TOKEN`.
5. **review-state.yml** — `repository_dispatch` type `asc-review-state-changed`. Uses `asc` CLI (asccli.sh) with ASC API key secrets to record App Store review state transitions (env: APP_NAME GitSync.md, ASC_APP_ID 6758960270).
6. **claude.yml** — Claude Code GitHub Action on @claude mentions in issues/PRs (`anthropics/claude-code-action@v1`, OAuth token secret).

`.github/scripts/` exists (support scripts for the above).

---

## 10. Worker topology (`worker/`)

Four Cloudflare Workers:

### a) `syncmd-receipt-verifier` (`worker/src/index.ts`, `wrangler.toml`)
Legacy-purchase (paid-app) unlock verifier. Routes (implied by code; wrangler.toml has no explicit routes — deployed at workers.dev or bound elsewhere):
- `GET /health` → `{ok:true}`.
- `POST /verify-legacy` — body `{receipt: base64}`; calls Apple `verifyReceipt` (production, falls back to sandbox on status 21007, optional `APPLE_SHARED_SECRET`), checks `bundle_id == bontecou.Sync-md`, classifies `isLegacy = original_purchase_date_ms < FREEMIUM_CUTOFF_MS` (2026-04-01 UTC — generous cutoff so all v1.5/v1.6 paid buyers unlock).
- `POST /verify-legacy-jws` — body `{jws: StoreKit2 AppTransaction JWS}`; decodes payload (does NOT cryptographically verify signature here — see Gaps), checks bundleId, same cutoff. Works on TestFlight + App Store.
- Response: `{isLegacy, originalPurchaseDate (ISO), originalVersion}`. iOS caches a true result in Keychain — one call per device.
- Var `DEBUG_FORCE_LEGACY="false"` (kill/test switch; must never ship true).

### b) Removed: `gitsync-premium-relay` + `storekit-verifier`

The Background Sync relay (webhook→outbox→APNs wake fan-out, D1 `premium-relay`, Queue `premium-relay-outbox`) and its private `storekit-verifier` service-binding worker were removed when Background Sync became fully on-device. Entitlements are now verified locally with StoreKit 2; see `premium-assist.md`. No replacement server exists.

### d) `onboarding-analytics` (`worker/onboarding-analytics/`, D1 `sync-md-onboarding-analytics`)
Privacy-safe onboarding event ingestion: stores only install UUID, event name, app version/build/platform, coarse onboarding step, coarse auth method/outcome, default-vs-custom save location, coarse error category. Rejects unknown events; never persists IPs, UAs, repo URLs, paths, identities, tokens. 90-day retention via daily cron (`RETENTION_DAYS` 1–365). Secrets: `INGEST_TOKEN`, `DELETION_TOKEN` (operator installation-deletion endpoint).

Plus **site-router** (section 3) — five workers total across the repo.

---

## 11. Misc config
- `ExportOptions.plist`: app-store-connect method, teamID 67KC823C9A, automatic signing, uploadSymbols, destination `upload`.
- `Gemfile`: fastlane `~>2.232.1` only.
- `.env.example` (root): only `OPENAI_API_KEY=`.
- `.gitignore` notable: `*.p8` keys, `fastlane/AuthKey_*.p8`, `marketing/`, `Config/`, `.asc/`, `screenshots/`, `metadata/` (but `!localization/app-store/metadata/` whitelisted), `.pi/`, `.vercel/`, `buildServer.json`.
- `fastlane/Preview.html`, `fastlane/report.xml` are generated artifacts.

---

## Gaps / uncertainties
- **OAuth security gaps (potential findings):** no PKCE and, critically, the callback never validates `state` — CSRF on the OAuth flow is possible in principle; fallback state uses weak `Math.random()`. The access token is delivered via redirect URL (standard for ASWebAuthenticationSession but worth noting). `verify-legacy-jws` decodes the JWS payload without signature verification on this worker — it trusts Apple's transport only if the caller is the app; the crypto verification now happens on-device via StoreKit 2 (`PremiumStorefront.swift`); the separate server verifier used by the former premium relay was removed.
  - **Clarification (verified in `state-and-services.md` §2):** the **iOS client independently validates `state`** (`OAuthService.validateReturnedState` / `parseCallbackURL` — strict scheme/host/path/query-shape checks + exact-match against the client-generated 32-hex state; unit-tested). A forged `syncmd://auth` redirect is therefore rejected on-device even though the server skips the check; the server-side gap primarily matters for flows where the callback URL itself is the trust boundary. Docs should state: CSRF protection is enforced client-side.
- No `fastlane/Matchfile` or screenshots config directory exists; signing is automatic. `fastlane/README.md` and `.env.example` are thin.
- `oauth-server` deployment: vercel.json only rewrites `/api/`; custom domain/routes not visible in repo.
- `worker/src` (receipt-verifier) wrangler.toml lists no routes — production hostname/trigger inferred, not evidenced.
- App Store metadata in `localization/app-store/metadata/{current,proposed}` contents not fully enumerated here (app-info/version JSON per locale).
- Localizable.xcstrings: en has only 274 explicit entries (others inherit from `source`), so "complete coverage" is approximate; count from Python analysis of the JSON.
- `sales_report_*.tsv` at repo root suggest App Store Connect sales exports are dropped in-repo (untracked workflow).
- `.github/scripts/` contents not inspected in detail. **Resolved: directory is empty** (checked 2026-08-22); the workflows that reference scripts use inline run blocks.
