# GitSync.md campaign attribution shortlinks

This Worker handles the public `gitsync.md` domain and reserves first-party shortlinks at:

```text
https://gitsync.md/v/<platform>-<angle>-<sequence>
```

After the domain cutover below, existing `gitsyncmd.app` links permanently redirect to the same path and query string on `gitsync.md`, so published campaign links continue to work.

## Domain cutover

The cutover is live as of October 8, 2026 at 04:42 UTC. `gitsync.md` is the primary website; `www.gitsync.md`, `gitsyncmd.app`, `www.gitsyncmd.app`, and both legacy `isolated.tech` aliases permanently redirect to it, preserving paths and queries. The Cloudflare Free zone `2e833240b006dc6e4858ee1ddc8f4533` is Active, with `bill.ns.cloudflare.com` and `naya.ns.cloudflare.com` delegated through nic.md.

Deployment evidence:

- Final router version: `442e2bdb-3b08-4e82-b8f0-4e4c33b6f4a1`, with `SERVE_DOMAIN_ALIASES=false`.
- Router rollback target: staging version `7643f20e-57ab-44bc-82ed-3ff8415278b5`, which serves every domain directly.
- Final Pages deployment: `058b7b2a-2912-4fa7-999a-fb0d94763ae5` (`https://058b7b2a.syncmd.pages.dev`).
- Pre-cutover Pages deployment: `93561fd0-3bc3-4020-ad97-2375a08dfe78`; pre-migration Worker: `d9762818-688f-442f-ba16-76af08b99cb9` (not a safe rollback target after permanent redirects).
- Verified 20 legacy-host/path combinations; all 9 HTML canonical and analytics entries; robots and sitemap; CSS, JavaScript, favicon and hero image; health; and App Store campaign parameters. HTTPS certificate validation passed for apex and www. Cloudflare and Google public DNS returned the new records; the deployment computer retained a negative DNS cache entry, so HTTPS checks also used those confirmed records with normal certificate validation.

The cutover used this sequence, which can also guide a future domain change:

1. Add `gitsync.md` as a zone in Cloudflare account `e4265f322e6380ee832b83ad45e3e8c0`. Set the domain's nameservers at its registrar to the exact pair Cloudflare assigns to this new zone. Wait for the zone to become **Active**.
2. Record the current Worker version and Pages deployment, then deploy the router in staging mode. This serves the site on every attached hostname without domain redirects while Cloudflare attaches the new Custom Domains and provisions DNS and certificates. Keep every existing domain attached; do not point the new hostnames at the Pages project directly.

   ```bash
   wrangler versions list --cwd site-router
   wrangler pages deployment list --project-name syncmd
   wrangler deploy --cwd site-router --keep-vars --var SERVE_DOMAIN_ALIASES:true
   wrangler versions list --cwd site-router
   ```

   Record this staging version as the **router rollback target**. It serves both domains, including clients that later cache an old-to-new redirect.
3. Verify HTTPS for both new hostnames. `https://gitsync.md/` and `https://www.gitsync.md/` should serve the site, and both `/health` endpoints should return the router's healthy JSON response. This confirms the new DNS, TLS, and Worker bindings are ready. Domain redirects must remain disabled during this step.
4. From the repository root, deploy the updated site, then enable the router's permanent redirects. Explicitly set `SERVE_DOMAIN_ALIASES` to `"false"` while preserving existing dashboard variables and secrets:

   ```bash
   wrangler pages deploy site --project-name syncmd --branch main --commit-dirty=true
   wrangler deploy --cwd site-router --keep-vars --var SERVE_DOMAIN_ALIASES:false
   ```

5. Verify `https://gitsync.md/` serves the site; `www.gitsync.md`, `gitsyncmd.app`, and `www.gitsyncmd.app` return `301` redirects to it, preserving paths and queries. Check the new sitemap, robots sitemap URL, canonical metadata, privacy and terms pages, images, and `/v/yt-obsidian-001` App Store destination.

Do not enable permanent redirects before step 3. A deployment can upload Worker code before it finishes configuring domains; staging mode keeps the old site available if a new domain attachment fails. If a cutover fails, roll back the router to the recorded **staging version** first, then restore the recorded Pages deployment in the dashboard. Do not restore the pre-migration router after enabling permanent redirects: its new-to-old redirects could loop with cached old-to-new redirects.

The iOS privacy and terms links in source target the new domain for the next app build; existing released builds continue working through the old-domain redirects. After cutover, update the App Store website/privacy URLs and submit the new sitemap and site move in search tools where applicable.

Example:

```text
https://gitsync.md/v/yt-obsidian-001
```

The Worker logs a privacy-light click event, stores real `GET` clicks in D1, and redirects to the App Store with Apple campaign parameters:

```text
ct=<platform>_<angle>_<sequence>
mt=8
ppid=<custom-product-page-id when configured>
pt=<APPLE_PROVIDER_TOKEN when configured>
```

## Supported platforms

- `yt` = YouTube Shorts
- `tt` = TikTok
- `ig` = Instagram Reels
- `x` = X/Twitter
- `th` = Threads
- `web` = Website

## Supported angles

| Angle | Custom Product Page | Env var |
|---|---|---|
| `obsidian` | `GitSync_US_Exact_Obsidian` | `CPP_OBSIDIAN_PPID` |
| `gitclient` | `GitSync_CPP_GitClient` | `CPP_GITCLIENT_PPID` |
| `selfhosted` | `GitSync_CPP_SelfHostedGit` | `CPP_SELFHOSTED_PPID` |
| `commits` | Git client page alias for Fastlane angle “Commits Without Laptop” | `CPP_GITCLIENT_PPID` |
| `privacy` | Self-hosted page alias for Fastlane angle “Private / Serverless Git” | `CPP_SELFHOSTED_PPID` |
| `homepage` | Optional homepage fallback | `CPP_HOMEPAGE_PPID` |

## Current custom product pages

```text
GitSync_US_Exact_Obsidian
ppid=6d42eefc-9fd5-4f7a-8a0d-b7332e0eea7f
url=https://apps.apple.com/us/app/gitsync-md/id6758960270?ppid=6d42eefc-9fd5-4f7a-8a0d-b7332e0eea7f

GitSync_CPP_GitClient
ppid=76f9ef64-2400-44f5-8e81-74b1728fc606
url=https://apps.apple.com/us/app/gitsync-md/id6758960270?ppid=76f9ef64-2400-44f5-8e81-74b1728fc606

GitSync_CPP_SelfHostedGit
ppid=874af699-91c3-4c2f-a76c-776229fe77e8
url=https://apps.apple.com/us/app/gitsync-md/id6758960270?ppid=874af699-91c3-4c2f-a76c-776229fe77e8
```

These IDs are public and are set in `wrangler.toml` vars. The pages still need App Store approval before relying on them for paid campaign traffic.

## Generate a starter link sheet

```bash
cd site-router
npm run campaigns:list
```

With the Apple provider token locally:

```bash
APPLE_PROVIDER_TOKEN="123456" npm run campaigns:list
```

The output is CSV with:

```text
shortlink,campaign_token,platform,angle,app_store_url
```

## D1 click storage

D1 database:

```text
name: gitsyncmd-campaigns
id: 0b2b8ac9-fba3-49a6-9c21-cb4a588d1a7b
binding: DB
```

Apply migrations:

```bash
cd site-router
npm run migrations:apply
```

Query click counts:

```bash
cd site-router
npx wrangler d1 execute gitsyncmd-campaigns --remote --command '
  SELECT campaign_token, COUNT(*) AS clicks
  FROM campaign_clicks
  GROUP BY campaign_token
  ORDER BY clicks DESC;
'
```

## Cloudflare environment variables

`APPLE_PROVIDER_TOKEN` is required for Apple App Store campaign attribution. Without it, the Worker still logs D1 clicks and redirects with `ct`, `mt`, and `ppid`, but App Store campaign attribution will be incomplete.

Set it with:

```bash
cd site-router
npx wrangler secret put APPLE_PROVIDER_TOKEN
```

The Worker will add an `x-gitsyncmd-attribution-warning` response header on redirects until the secret is configured.

## Test after deploy

```bash
curl -I https://gitsync.md/v/yt-obsidian-001
```

Expected: `302` redirect to `apps.apple.com` with:

```text
ppid=6d42eefc-9fd5-4f7a-8a0d-b7332e0eea7f
ct=yt_obsidian_001
mt=8
pt=... # once APPLE_PROVIDER_TOKEN is set
```

Apple campaign data may not appear until at least 24 hours have passed and the campaign has enough first-time downloads to satisfy Apple's privacy threshold.

## Active Fastlane YouTube Shorts links

The current `GitSync.md Obsidian + Git Client Launch` Fastlane automation uses 21 unique YouTube Shorts links:

- `yt-obsidian-001` through `yt-obsidian-007` → Obsidian CPP
- `yt-commits-001` through `yt-commits-007` → Git client CPP
- `yt-privacy-001` through `yt-privacy-007` → Self-hosted/privacy CPP

Slot-level mapping is stored at:

```text
/Users/codybontecou/dev/Sync.md/marketing/fastlane-ai/runs/2026-07-05-gitsync-obsidian-git-client/slot-tracking-links.md
```

## Apple Search Ads note

Apple Search Ads cannot use `gitsync.md/v/...` redirect URLs as ad destinations. ASA destinations must be the default App Store listing or Apple-approved custom product page creatives. GitSync.md ASA campaigns are therefore kept paused until:

1. App Store Connect CPP versions leave `WAITING_FOR_REVIEW`.
2. `asa --app gitsyncmd ads product-pages --adam-id 6758960270` returns the approved pages.
3. The matching CPP creatives are created and attached as paused ads.
4. Spend is explicitly approved.

The ASA attach plan is stored at:

```text
/Users/codybontecou/dev/Sync.md/marketing/apple-search-ads/creative-tracking-plan.md
```

## Website SEO article campaigns (October 26, 2026)

Download links in the static site use the existing `/v/web-homepage-<sequence>` route.
The `homepage` angle deliberately uses the default App Store listing while the
Obsidian and self-hosted custom product pages await separate approval verification.
The same page token covers its inline and bottom download links; review links stay direct.

| Page | Campaign token |
| --- | --- |
| `/` | `web_homepage_100` |
| `/blog/` | `web_homepage_101` |
| `/blog/obsidian-git-iphone-ipad` | `web_homepage_102` |
| `/blog/obsidian-git-ios-setup` | `web_homepage_103` |
| `/blog/gitsync-connect-obsidian-git` | `web_homepage_104` |
| `/blog/create-git-repository-iphone` | `web_homepage_105` |
| `/blog/shortcuts-automation-ios` | `web_homepage_106` |
| `/blog/self-hosted-git-ssh-ios` | `web_homepage_107` |
| `/blog/git-lfs-ios` | `web_homepage_108` |
| `/blog/obsidian-git-not-working-iphone` | `web_homepage_109` |
| `/blog/sync-obsidian-windows-iphone-github` | `web_homepage_110` |
| `/compare/gitsync-vs-working-copy` | `web_homepage_111` |
| `/compare/obsidian-sync-vs-git` | `web_homepage_112` |
| `/blog/obsidian-git-merge-conflicts-iphone` | `web_homepage_113` |
| `/blog/obsidian-git-background-sync-iphone` | `web_homepage_114` |
| `/blog/clone-github-repository-iphone` | `web_homepage_115` |
| `/blog/obsidian-gitignore` | `web_homepage_116` |
| `/compare/icloud-vs-git-obsidian` | `web_homepage_117` |

A HEAD request to `/v/web-homepage-108` on October 26 returned 302 with `pt`, `ct`,
and `mt=8`, without a custom product page. HEAD does not create a D1 click row.
This verifies the redirect configuration, not an attributed download or purchase.
Apple campaign reporting remains subject to its reporting delays and privacy thresholds.
Use Search Console landing-page clicks and aggregate page views alongside these
store-link clicks; campaign clicks alone do not establish organic acquisition.
