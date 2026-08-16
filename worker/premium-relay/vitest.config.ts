import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: "./wrangler.jsonc" },
    miniflare: {
      bindings: {
        GITHUB_APP_PRIVATE_KEY: "unused-in-relay-tests",
        GITHUB_WEBHOOK_SECRET: "test-webhook-secret",
        APNS_PRIVATE_KEY: "unused-in-relay-tests",
        GITHUB_APP_ID: "123456",
        GITHUB_APP_SLUG: "gitsync-test-app",
        GITHUB_CALLBACK_URL: "https://relay.test/v1/github/callback",
        APNS_TEAM_ID: "TESTTEAM01",
        APNS_KEY_ID: "TESTKEY001",
      },
      serviceBindings: {
        STOREKIT_VERIFIER: async (request) => {
          const path = new URL(request.url).pathname;
          const body = await request.json() as { signedPayload?: string; signedTransaction?: string };
          if ((path === "/v1/notifications/verify" && body.signedPayload?.startsWith("{")) ||
              (path === "/v1/transactions/verify" && body.signedTransaction?.startsWith("{"))) {
            try {
              const parsed = JSON.parse(body.signedPayload ?? body.signedTransaction ?? "") as Record<string, unknown>;
              if (path === "/v1/notifications/verify") {
                if (!("subtype" in parsed)) parsed.subtype = null;
                if (!("gracePeriodExpiresAt" in parsed)) parsed.gracePeriodExpiresAt = null;
              }
              return Response.json(parsed);
            }
            catch { return Response.json({ error: "invalid test fixture" }, { status: 400 }); }
          }
          return new Response(JSON.stringify({ error: "unconfigured test verifier" }), { status: 503 });
        },
      },
    },
  })],
  test: { pool: "cloudflare" },
});
