# GitSync.md campaign attribution shortlinks

This Worker handles the public `gitsyncmd.app` domain and reserves first-party shortlinks at:

```text
https://gitsyncmd.app/v/<platform>-<angle>-<sequence>
```

Example:

```text
https://gitsyncmd.app/v/yt-obsidian-001
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
curl -I https://gitsyncmd.app/v/yt-obsidian-001
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

Apple Search Ads cannot use `gitsyncmd.app/v/...` redirect URLs as ad destinations. ASA destinations must be the default App Store listing or Apple-approved custom product page creatives. GitSync.md ASA campaigns are therefore kept paused until:

1. App Store Connect CPP versions leave `WAITING_FOR_REVIEW`.
2. `asa --app gitsyncmd ads product-pages --adam-id 6758960270` returns the approved pages.
3. The matching CPP creatives are created and attached as paused ads.
4. Spend is explicitly approved.

The ASA attach plan is stored at:

```text
/Users/codybontecou/dev/Sync.md/marketing/apple-search-ads/creative-tracking-plan.md
```
