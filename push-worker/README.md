# syncmd-push

A thin relay that turns GitHub webhooks into visible APNs notifications.

Flow: someone pushes to GitHub → GitHub calls `/v1/github-webhook` → the
worker verifies the HMAC signature, finds devices subscribed to that repo,
and sends a **visible** push notification ("2 new commits — tap to sync").
Tapping opens GitSync.md, which pulls via its normal foreground
reconciliation. We deliberately avoid silent pushes: Apple throttles them
to a few per hour and drops them in Low Power Mode — visible alerts are the
dependable path.

Privacy: the worker stores only `{repo name, APNs token}` pairs in Workers
KV. No repository contents, credentials, or file paths ever reach it.

## Setup

1. **KV namespace**

   ```sh
   wrangler kv namespace create REGISTRY
   # put the printed id into wrangler.toml
   ```

2. **Secrets**

   ```sh
   wrangler secret put GITHUB_WEBHOOK_SECRET   # any random string
   wrangler secret put APNS_KEY_P8             # .p8 contents (PEM, with BEGIN/END lines)
   wrangler secret put APNS_KEY_ID             # 10-char Key ID
   wrangler secret put APNS_TEAM_ID            # 10-char Team ID
   ```

   Create the .p8 at developer.apple.com → Certificates, Identifiers &
   Profiles → Keys → Apple Push Notifications service (APNs). One key works
   for both development and production.

3. **Deploy**

   ```sh
   wrangler deploy
   ```

4. **GitHub webhook** — repo Settings → Webhooks → Add webhook:

   - Payload URL: `https://syncmd-push.<account>.workers.dev/v1/github-webhook`
   - Content type: `application/json`
   - Secret: the same `GITHUB_WEBHOOK_SECRET`
   - Events: just the `push` event

5. **Point the app at the worker** (Settings → Push Sync uses the built-in
   default; override `pushSyncWorkerURL` in UserDefaults for development).

## Local development

```sh
npm install
npm run dev      # wrangler local dev server
npm test         # vitest unit tests for signature + payload handling
```

Testing the webhook locally with ngrok:

```sh
ngrok http 8787
curl -X POST https://<ngrok-id>.ngrok.app/v1/github-webhook \
  -H 'content-type: application/json' \
  -H 'x-github-event: push' \
  -H 'x-hub-signature-256: sha256=<hmac>' \
  -d '{"repository":{"full_name":"you/repo"},"commits":[{},{}]}'
```

## Notes & limits

- **APNs over Workers `fetch`**: APNs requires HTTP/2; Cloudflare's edge
  negotiates h2 with Apple's endpoints. If you ever see connection errors,
  verify `compatibility_date` is current.
- **Scale**: device discovery scans KV keys with the `device:` prefix
  (1000 keys/page). Fine for personal/family scale; revisit if you ever
  exceed a few hundred devices.
- **Development vs production tokens**: debug builds register with
  `environment: "development"` and get sandbox APNs; TestFlight/App Store
  builds use production. Both are supported per device.
- **Collapse window**: repeated pushes to the same repo within
  `NOTIFY_COLLAPSE_SECONDS` (default 120s) produce at most one
  notification per device.
