import { env, SELF } from "cloudflare:test";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { classifyApns, silentPayload } from "../src/apns";
import { cleanupRetention, consumeOutbox, dispatchPendingOutbox, setBeforeDeviceDeletionBatchForTesting, setBeforeDeviceRegistrationBatchForTesting, setBeforeEnrollmentBatchForTesting, setBeforeEntitlementAuthorizationBatchForTesting, setBeforeGitHubLinkCompletionBatchForTesting, setBeforeInvalidTokenTombstoneForTesting, setProveRepositoryForTesting, setRevalidateInstallationAdministratorForTesting } from "../src/index";
import { sha256 } from "../src/core";
import type { Env, OutboxMessage } from "../src/types";

const INSTALLATION_A = "11111111-1111-4111-8111-111111111111";
const INSTALLATION_B = "22222222-2222-4222-8222-222222222222";
const CHANNEL_A = "channel_aaaaaaaaaaaaaaaa";
const CHANNEL_B = "channel_bbbbbbbbbbbbbbbb";
const TOKEN_A = "aa".repeat(32);
const TOKEN_B = "bb".repeat(32);
const TOKEN_C = "cc".repeat(32);
const NOW = Date.now();
const TEST_GITHUB_PRIVATE_KEY =
  "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBANRtX160eASVFQHakxHZw7pZg4rD3SzehApn+75FjV4ZRcD7UksTMNLdngYyxXyLWNGli09GUgztNjFu2Co7O8o79zlHDIWfeJj//uBvdmND+k29uVQdBUSY7yaZCB6nBnbg35hqhNS9Gab1Bf9rQwv9Zwdw9b4Ce40/In6iL4pzAgMBAAECgYA70ZzUl29govaqcfmOQktas5BWSDeFRhfaslNyzjUz9VvuLxeKapoKFzxDtJJmMvtM9hgXt86tMzNakkMvCUUZd5NUsGVxvvDoEOj9Lh2e5++JOmFFd+RohE9yF0wGPw70nJqVaH573EfuzKyo+DwD4u70XbzBvOHUCpZQudwsSQJBAO2G0OeStF6TvpKzWeOuwxP+9SgFBpedpt6vgTOKjFLY4s6rcfU2h5JBwlsgWPC8D4jxbG1/vrtYH+RfgkqI55UCQQDk8tMay7ojWjFc17JWTr+c2o0lNnZCeeEbDxN8IJjDmwsqK8yjf+3UzQCuxHXm3VI5PmuTtbPnnF/hJQMGq4fnAkBjYd40llhrngvF288Hic7Lpgizdu7cLzVrxSkdBKJT47V6XZevzuIImwUUFcPA7h7d4I3KfwGx51xotGGSiBfFAkA5Ygs7ShirR63boUxXiYFJJRX/X7kgTD/5cjvl/p2LWU7hEP1HdYb8sS0coK0UYiB7rIN2EDK5OF5npckuYMu9AkEAvOGYY6/o7WdMl67k1Q79VUF9zEpUJS9CKmHKwB/qk71pOtpyJCbxqefxxOXeuxXQPkrQEyzKaluXQnPj4Rj4pw==";
const TEST_APNS_PRIVATE_KEY = [
  "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgrFCWAIBJIabEYJO7",
  "LtNUA9ZtBs97NdadEcBHqXUSQTihRANCAARPdsjwYJjV28bI9pBLDiPh+h3hZ83/",
  "smXepS9DIkR+7Jx+jzXXOrUSsvw44siD0hLLVZbmZc4FV/r/Sjw+cUxW",
].join("");

function testEnv(): Env { return env as unknown as Env; }
async function exec(sql: string, ...bindings: unknown[]) { return testEnv().DB.prepare(sql).bind(...bindings).run(); }
async function row<T>(sql: string, ...bindings: unknown[]) { return testEnv().DB.prepare(sql).bind(...bindings).first<T>(); }

async function resetDatabase() {
  await testEnv().DB.exec("PRAGMA foreign_keys=OFF");
  for (const table of ["outbox_claims", "apns_attempts", "outbox", "webhook_deliveries", "device_channels", "devices", "repo_enrollments", "github_installations", "github_installation_tombstones", "github_link_states", "installation_deletions", "installation_deletion_keys", "sessions", "entitlements", "installations", "app_store_notifications"]) {
    await testEnv().DB.exec(`DELETE FROM ${table}`);
  }
  await testEnv().DB.exec("PRAGMA foreign_keys=ON");
}

async function seedInstallation(id: string, bearer: string, original = id, deletionToken = `delete-${id}`) {
  await exec("INSERT INTO installations(id,bundle_id,app_version,created_at,updated_at,deleted_at,deletion_generation) VALUES(?,?,?,?,?,NULL,0)", id, "bontecou.Sync-md", "1", NOW, NOW);
  await exec("INSERT INTO entitlements(installation_id,original_transaction_id,product_id,environment,expires_at,revoked_at,event_time,verified_at,updated_at) VALUES(?,?,?,?,?,NULL,?,?,?)", id, original, "com.bontecou.gitsync.assist.monthly", "Sandbox", NOW + 86_400_000, NOW, NOW, NOW);
  await exec("INSERT INTO sessions VALUES(?,?,?,?,NULL)", await sha256(bearer), id, NOW + 86_400_000, NOW);
  await exec("INSERT INTO installation_deletion_keys VALUES(?,?,?)", id, await sha256(deletionToken), NOW);
}

async function seedEnrollment(installationID: string, channel: string, repositoryID = 42, branch = "main") {
  const githubID = installationID === INSTALLATION_A ? 101 : 202;
  await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", githubID, installationID, NOW, 9001);
  await exec("INSERT INTO repo_enrollments(channel,installation_id,github_installation_id,repository_id,branch,created_at,deleted_at) VALUES(?,?,?,?,?,?,NULL)", channel, installationID, githubID, repositoryID, branch, NOW);
}

async function seedDevice(id: string, installationID: string, token: string, environment: string) {
  await exec("INSERT INTO devices(id,installation_id,apns_token,apns_environment,created_at,updated_at,deleted_at,registration_generation) VALUES(?,?,?,?,?,?,NULL,0)", id, installationID, token, environment, NOW, NOW);
}

function jsonRequest(path: string, method: string, body: unknown, bearer?: string): Request {
  return new Request(`https://relay.test${path}`, {
    method,
    headers: { "content-type": "application/json", ...(bearer ? { authorization: `Bearer ${bearer}` } : {}) },
    body: JSON.stringify(body),
  });
}

async function signature(raw: string, secret = "test-webhook-secret"): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const output = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(raw)));
  return `sha256=${[...output].map(byte => byte.toString(16).padStart(2, "0")).join("")}`;
}

async function webhook(delivery: string, payload: unknown, secret = "test-webhook-secret", event = "push") {
  const raw = JSON.stringify(payload);
  return SELF.fetch("https://relay.test/v1/webhooks/github", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-github-event": event,
      "x-github-delivery": delivery,
      "x-hub-signature-256": await signature(raw, secret),
    },
    body: raw,
  });
}

function pushPayload(repositoryID = 42, branch = "main") {
  return { ref: `refs/heads/${branch}`, repository: { id: repositoryID }, before: "0", after: "1", commits: [], head_commit: null, pusher: {}, sender: {} };
}

function verifiedNotification(overrides: Partial<Record<string, unknown>> = {}) {
  return JSON.stringify({
    notificationUUID: "notification-default",
    notificationType: "DID_RENEW",
    subtype: null,
    originalTransactionId: "subscription-a",
    expiresAt: NOW + 86_400_000,
    revokedAt: null,
    gracePeriodExpiresAt: null,
    eventTime: NOW,
    ...overrides,
  });
}

function message(body: OutboxMessage) {
  const state = { acked: false, retries: [] as unknown[] };
  return {
    body,
    ack: () => { state.acked = true; },
    retry: (options?: unknown) => { state.retries.push(options); },
    state,
  };
}

beforeAll(async () => {
  const statements = `PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS installations (id TEXT PRIMARY KEY,bundle_id TEXT NOT NULL,app_version TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER,deletion_generation INTEGER NOT NULL DEFAULT 0) STRICT;
CREATE TABLE IF NOT EXISTS entitlements (id INTEGER PRIMARY KEY AUTOINCREMENT,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,original_transaction_id TEXT NOT NULL,product_id TEXT NOT NULL,environment TEXT NOT NULL,expires_at INTEGER NOT NULL,revoked_at INTEGER,event_time INTEGER NOT NULL,verified_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(installation_id,original_transaction_id)) STRICT;
CREATE UNIQUE INDEX IF NOT EXISTS entitlements_original_owner ON entitlements(original_transaction_id);
CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL,revoked_at INTEGER) STRICT;
CREATE TABLE IF NOT EXISTS installation_deletions (installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,token_hash TEXT PRIMARY KEY,requested_at INTEGER NOT NULL) STRICT;
CREATE INDEX IF NOT EXISTS installation_deletions_installation ON installation_deletions(installation_id,requested_at);
CREATE TABLE IF NOT EXISTS installation_deletion_keys (installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,key_hash TEXT PRIMARY KEY,created_at INTEGER NOT NULL) STRICT;
CREATE INDEX IF NOT EXISTS installation_deletion_keys_installation ON installation_deletion_keys(installation_id,created_at);
CREATE TABLE IF NOT EXISTS github_link_states (state_hash TEXT PRIMARY KEY,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,deletion_generation INTEGER NOT NULL,expires_at INTEGER NOT NULL,consumed_at INTEGER,consumed_nonce TEXT,created_at INTEGER NOT NULL,github_installation_id INTEGER,authorization_state_hash TEXT,authorization_expires_at INTEGER,authorized_at INTEGER,authorized_nonce TEXT) STRICT;
CREATE UNIQUE INDEX IF NOT EXISTS github_link_states_authorization_state ON github_link_states(authorization_state_hash) WHERE authorization_state_hash IS NOT NULL;
CREATE TABLE IF NOT EXISTS github_installations (github_installation_id INTEGER NOT NULL,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,linked_at INTEGER NOT NULL,deleted_at INTEGER,authorizing_user_id INTEGER,PRIMARY KEY(github_installation_id,installation_id)) STRICT, WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS github_installation_tombstones (github_installation_id INTEGER PRIMARY KEY,deleted_at INTEGER NOT NULL) STRICT;
CREATE TABLE IF NOT EXISTS repo_enrollments (channel TEXT PRIMARY KEY,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,github_installation_id INTEGER NOT NULL,repository_id INTEGER NOT NULL,branch TEXT NOT NULL,created_at INTEGER NOT NULL,deleted_at INTEGER,UNIQUE(installation_id,repository_id,branch),FOREIGN KEY(github_installation_id,installation_id) REFERENCES github_installations(github_installation_id,installation_id) ON DELETE CASCADE) STRICT;
CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY,installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,apns_token TEXT NOT NULL,apns_environment TEXT NOT NULL CHECK(apns_environment IN ('sandbox','production')),created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER,registration_generation INTEGER NOT NULL DEFAULT 0,UNIQUE(installation_id,apns_token,apns_environment)) STRICT;
CREATE TABLE IF NOT EXISTS device_channels (device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,channel TEXT NOT NULL REFERENCES repo_enrollments(channel) ON DELETE CASCADE,created_at INTEGER NOT NULL,PRIMARY KEY(device_id,channel)) STRICT, WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS webhook_deliveries (delivery_id TEXT PRIMARY KEY,event TEXT NOT NULL,repository_id INTEGER NOT NULL,received_at INTEGER NOT NULL,completed_at INTEGER,retention_until INTEGER NOT NULL) STRICT;
CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY,delivery_id TEXT NOT NULL REFERENCES webhook_deliveries(delivery_id) ON DELETE CASCADE,channel TEXT NOT NULL REFERENCES repo_enrollments(channel) ON DELETE CASCADE,hint TEXT NOT NULL,created_at INTEGER NOT NULL,enqueued_at INTEGER,completed_at INTEGER,retention_until INTEGER NOT NULL,UNIQUE(delivery_id,channel)) STRICT;
CREATE TABLE IF NOT EXISTS apns_attempts (outbox_id TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,status TEXT NOT NULL CHECK(status IN ('success','invalidToken','permanent','transient')),http_status INTEGER,reason TEXT,attempted_at INTEGER NOT NULL,completed_at INTEGER,PRIMARY KEY(outbox_id,device_id)) STRICT, WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS outbox_claims (outbox_id TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,lease_token TEXT NOT NULL,lease_until INTEGER NOT NULL,PRIMARY KEY(outbox_id,device_id)) STRICT, WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS app_store_notifications (notification_uuid TEXT PRIMARY KEY,notification_type TEXT NOT NULL,original_transaction_id TEXT,received_at INTEGER NOT NULL,event_time INTEGER NOT NULL,processed_at INTEGER,retention_until INTEGER NOT NULL) STRICT;`;
  for (const statement of statements.split(";").map(value => value.trim()).filter(Boolean)) await testEnv().DB.exec(statement);
});

beforeEach(async () => {
  vi.restoreAllMocks();
  setBeforeInvalidTokenTombstoneForTesting();
  setBeforeEntitlementAuthorizationBatchForTesting();
  setBeforeDeviceRegistrationBatchForTesting();
  setBeforeDeviceDeletionBatchForTesting();
  setBeforeGitHubLinkCompletionBatchForTesting();
  setBeforeEnrollmentBatchForTesting();
  setProveRepositoryForTesting(async () => {});
  setRevalidateInstallationAdministratorForTesting(async () => {});
  await resetDatabase();
});

describe("entitlement and installation-scoped authorization", () => {
  it("fails closed before routing when required relay configuration is malformed", async () => {
    const priorSlug = testEnv().GITHUB_APP_SLUG;
    const priorSecret = testEnv().GITHUB_WEBHOOK_SECRET;
    try {
      (testEnv() as unknown as Record<string, string>).GITHUB_APP_SLUG = "replace-with-github-app-slug";
      expect((await SELF.fetch(jsonRequest("/v1/github/link/start", "POST", {}))).status).toBe(503);
      (testEnv() as unknown as Record<string, string>).GITHUB_APP_SLUG = priorSlug;
      (testEnv() as unknown as Record<string, string>).GITHUB_WEBHOOK_SECRET = "";
      expect((await SELF.fetch(new Request("https://relay.test/unknown"))).status).toBe(503);
    } finally {
      (testEnv() as unknown as Record<string, string>).GITHUB_APP_SLUG = priorSlug;
      (testEnv() as unknown as Record<string, string>).GITHUB_WEBHOOK_SECRET = priorSecret;
    }
  });

  it("fails only link admission routes when GitHub user OAuth configuration is absent", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorSecret = testEnv().GITHUB_CLIENT_SECRET;
    try {
      (testEnv() as unknown as Record<string, string>).GITHUB_CLIENT_SECRET = "";
      expect((await SELF.fetch(jsonRequest("/v1/github/link/start", "POST", {}, "bearer-a"))).status).toBe(503);
      expect((await SELF.fetch("https://relay.test/v1/github/callback?state=x&installation_id=303", { redirect: "manual" })).status).toBe(503);
      expect((await SELF.fetch("https://relay.test/v1/github/authorize/callback?code=x&state=y")).status).toBe(503);
      expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
        method: "DELETE", headers: { "x-installation-deletion-token": `delete-${INSTALLATION_A}` },
      }))).status).toBe(204);
    } finally {
      (testEnv() as unknown as Record<string, string>).GITHUB_CLIENT_SECRET = priorSecret;
    }
  });

  function entitlementRequest(installationID: string, appAccountToken: string | null) {
    const verified = JSON.stringify({
      transactionId: "1", originalTransactionId: "1",
      productId: "com.bontecou.gitsync.assist.monthly", bundleId: "bontecou.Sync-md",
      environment: "Sandbox", expiresAt: NOW + 86_400_000, revokedAt: null,
      appAccountToken, eventTime: NOW,
    });
    return jsonRequest("/v1/entitlements", "PUT", {
      installation: { installationID, bundleID: "bontecou.Sync-md", appVersion: "1" },
      proof: { productID: "com.bontecou.gitsync.assist.monthly", transactionID: 1, originalTransactionID: 1,
        expirationDate: new Date(NOW + 86_400_000).toISOString(), environment: "sandbox", signedTransaction: verified },
    });
  }

  it("authorizes only a transaction cryptographically bound to one installation owner", async () => {
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).status).toBe(200);
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_B, INSTALLATION_A))).status).toBe(403);
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_B, null))).status).toBe(403);
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_B, INSTALLATION_B))).status).toBe(409);
    expect(await row("SELECT count(*) AS count FROM installations")).toEqual({ count: 1 });
  });

  it("cannot create a deletion capability after a concurrent installation purge", async () => {
    setBeforeEntitlementAuthorizationBatchForTesting(async () => {
      await exec(
        "INSERT INTO installations(id,bundle_id,app_version,created_at,updated_at,deleted_at,deletion_generation) VALUES(?,?,?,?,?,?,1)",
        INSTALLATION_A, "bontecou.Sync-md", "1", NOW, NOW + 1, NOW + 1,
      );
      await exec("INSERT INTO installation_deletions VALUES(?,?,?)", INSTALLATION_A, await sha256("used-delete-capability"), NOW + 1);
    });

    expect((await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).status).toBe(409);
    expect(await row("SELECT count(*) AS count FROM installation_deletion_keys WHERE installation_id=?", INSTALLATION_A)).toEqual({ count: 0 });
    expect(await row("SELECT count(*) AS count FROM sessions WHERE installation_id=? AND revoked_at IS NULL", INSTALLATION_A)).toEqual({ count: 0 });
    expect(await row("SELECT deleted_at,deletion_generation FROM installations WHERE id=?", INSTALLATION_A))
      .toEqual({ deleted_at: NOW + 1, deletion_generation: 1 });
  });

  it("authorizes a current entitlement through a newer verified billing-grace expiry", async () => {
    await seedInstallation(INSTALLATION_A, "old-bearer", "1");
    await exec(
      "UPDATE entitlements SET expires_at=?,event_time=? WHERE installation_id=? AND original_transaction_id='1'",
      NOW + 100_000, NOW + 10, INSTALLATION_A,
    );
    const request = entitlementRequest(INSTALLATION_A, INSTALLATION_A);
    const body = await request.json() as { proof: { expirationDate: string; signedTransaction: string } };
    body.proof.expirationDate = new Date(NOW - 1).toISOString();
    const expiredTransaction = JSON.parse(body.proof.signedTransaction) as Record<string, unknown>;
    expiredTransaction.expiresAt = NOW - 1;
    expiredTransaction.eventTime = NOW;
    body.proof.signedTransaction = JSON.stringify(expiredTransaction);

    const response = await SELF.fetch(jsonRequest("/v1/entitlements", "PUT", body));
    expect(response.status).toBe(200);
    const credential = await response.json() as { expiresAt: string };
    expect(Date.parse(credential.expiresAt)).toBe(NOW + 100_000);
    expect(await row("SELECT expires_at,event_time FROM entitlements WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ expires_at: NOW + 100_000, event_time: NOW + 10 });
  });

  it("keeps every issued deletion capability valid until one deletes the installation", async () => {
    const first = await (await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).json() as { token: string; deletionToken: string };
    const second = await (await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).json() as { token: string; deletionToken: string };
    expect(first.deletionToken).not.toBe(second.deletionToken);
    expect(await row("SELECT count(*) AS count FROM installation_deletion_keys WHERE installation_id=?", INSTALLATION_A)).toEqual({ count: 2 });

    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": first.deletionToken },
    }))).status).toBe(204);
    expect(await row("SELECT count(*) AS count FROM installation_deletion_keys WHERE installation_id=?", INSTALLATION_A)).toEqual({ count: 0 });
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": first.deletionToken },
    }))).status).toBe(204);
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": second.deletionToken },
    }))).status).toBe(204);
    expect(await row("SELECT count(*) AS count FROM installation_deletions WHERE installation_id=?", INSTALLATION_A)).toEqual({ count: 2 });
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": first.deletionToken },
    }))).status).toBe(204);
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": second.deletionToken },
    }))).status).toBe(204);
  });

  it("allows signed renewal after server-driven expiration but never after explicit deletion", async () => {
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).status).toBe(200);
    await exec("UPDATE installations SET deleted_at=?,deletion_generation=deletion_generation+1 WHERE id=?", NOW, INSTALLATION_A);
    await exec("UPDATE entitlements SET expires_at=?,event_time=? WHERE installation_id=?", NOW - 1, NOW - 1, INSTALLATION_A);
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).status).toBe(200);
    expect(await row("SELECT deleted_at FROM installations WHERE id=?", INSTALLATION_A)).toEqual({ deleted_at: null });
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 101, INSTALLATION_A, NOW + 1, 9001);
    await exec("INSERT INTO repo_enrollments(channel,installation_id,github_installation_id,repository_id,branch,created_at,deleted_at) VALUES(?,?,?,?,?,?,?)",
      CHANNEL_A, INSTALLATION_A, 101, 42, "main", NOW, NOW);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-retained", "push", 42, NOW, NOW + 86_400_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_ffffffffffffffffffffffffffffffff", "delivery-retained", CHANNEL_A, "event_retained", NOW, NOW, NOW + 86_400_000);
    const renewedCredential = await (await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).json() as { token: string; deletionToken: string };
    const reenrollment = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
      githubInstallationID: 101, repositoryID: 42, branch: "main",
    }, renewedCredential.token));
    expect(reenrollment.status).toBe(201);
    expect(await reenrollment.json()).toEqual({
      channel: CHANNEL_A, githubInstallationID: 101, repositoryID: 42, branch: "main",
    });
    expect(await row("SELECT channel,deleted_at FROM repo_enrollments WHERE installation_id=? AND repository_id=42", INSTALLATION_A))
      .toEqual({ channel: CHANNEL_A, deleted_at: null });
    expect(await row("SELECT channel FROM outbox WHERE id='out_ffffffffffffffffffffffffffffffff'")).toEqual({ channel: CHANNEL_A });

    const bearer = renewedCredential.token;
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": renewedCredential.deletionToken },
    }))).status).toBe(204);
    expect((await SELF.fetch(entitlementRequest(INSTALLATION_A, INSTALLATION_A))).status).toBe(403);
  });

  it("fails closed when verifier is unavailable and stores nothing", async () => {
    const request = jsonRequest("/v1/entitlements", "PUT", {
      installation: { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" },
      proof: { productID: "com.bontecou.gitsync.assist.monthly", transactionID: 1, originalTransactionID: 1, expirationDate: new Date(NOW + 1000).toISOString(), environment: "sandbox", signedTransaction: "signed-jws" },
    });
    const response = await SELF.fetch(request);
    expect(response.status).toBe(503);
    expect(await row("SELECT count(*) AS count FROM installations")).toEqual({ count: 0 });
  });

  it("rejects missing bearer and cross-installation device registration", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedInstallation(INSTALLATION_B, "bearer-b");
    const body = { installation: { installationID: INSTALLATION_B, bundleID: "bontecou.Sync-md", appVersion: "1" }, token: TOKEN_A, environment: "sandbox", registrationGeneration: 1 };
    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", body))).status).toBe(401);
    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", body, "bearer-a"))).status).toBe(403);
    expect(await row("SELECT count(*) AS count FROM devices")).toEqual({ count: 0 });
  });

  it("accepts channel-free and large legacy registrations while validating legacy syntax and exact keys", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const installation = { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" };
    const legacyChannels = Array.from({ length: 101 }, (_, index) => `legacy_channel_${String(index).padStart(4, "0")}`);

    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_A, environment: "sandbox", channels: legacyChannels, registrationGeneration: 1,
    }, "bearer-a"))).status).toBe(200);
    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_B, environment: "sandbox", registrationGeneration: 2,
    }, "bearer-a"))).status).toBe(200);
    expect(await row("SELECT count(*) AS count FROM device_channels")).toEqual({ count: 0 });
    expect(await row("SELECT apns_token,registration_generation FROM devices WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ apns_token: TOKEN_B, registration_generation: 2 });

    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_B, environment: "sandbox", channels: [...legacyChannels, "bad"], registrationGeneration: 3,
    }, "bearer-a"))).status).toBe(400);
    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_B, environment: "sandbox", registrationGeneration: 3, unexpected: true,
    }, "bearer-a"))).status).toBe(400);
  });

  it("registers, replaces, rejects stale generations, and deletes only the authenticated installation token", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const installation = { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" };
    let response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", { installation, token: TOKEN_A, environment: "sandbox", registrationGeneration: 1 }, "bearer-a"));
    expect(response.status).toBe(200);
    response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", { installation, token: TOKEN_B, environment: "sandbox", registrationGeneration: 2 }, "bearer-a"));
    expect(response.status).toBe(200);
    response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", { installation, token: TOKEN_A, environment: "sandbox", registrationGeneration: 1 }, "bearer-a"));
    expect(response.status).toBe(200);
    expect(await row<{ apns_token: string; registration_generation: number }>("SELECT apns_token,registration_generation FROM devices WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ apns_token: TOKEN_B, registration_generation: 2 });
    response = await SELF.fetch(jsonRequest("/v1/devices", "DELETE", { installationID: INSTALLATION_A, token: TOKEN_B, environment: "sandbox" }, "bearer-a"));
    expect(response.status).toBe(204);
    const deleted = await row<{ deleted_at: number; apns_token: string }>("SELECT deleted_at,apns_token FROM devices WHERE installation_id=?", INSTALLATION_A);
    expect(deleted?.deleted_at).toBeTypeOf("number");
    expect(deleted?.apns_token.startsWith("deleted-")).toBe(true);

    // Reproduce a token-delete request that selected generation 3, followed by
    // a generation-4 registration before its guarded mutation.
    response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", { installation, token: TOKEN_A, environment: "sandbox", registrationGeneration: 3 }, "bearer-a"));
    expect(response.status).toBe(200);
    const selected = await row<{ id: string; registration_generation: number }>(
      "SELECT id,registration_generation FROM devices WHERE installation_id=? AND apns_token=?", INSTALLATION_A, TOKEN_A,
    );
    response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", { installation, token: TOKEN_B, environment: "sandbox", registrationGeneration: 4 }, "bearer-a"));
    expect(response.status).toBe(200);
    await exec(
      "UPDATE devices SET deleted_at=?,apns_token='deleted-'||id,updated_at=? WHERE id=? AND registration_generation=? AND deleted_at IS NULL AND apns_token=?",
      NOW, NOW, selected!.id, selected!.registration_generation, TOKEN_A,
    );
    expect(await row("SELECT apns_token,registration_generation,deleted_at FROM devices WHERE id=?", selected!.id))
      .toEqual({ apns_token: TOKEN_B, registration_generation: 4, deleted_at: null });

    // Swift's synthesized Encodable omits an optional nil token; accept that
    // wire representation as "delete all for this installation/environment".
    response = await SELF.fetch(jsonRequest("/v1/devices", "DELETE", { installationID: INSTALLATION_A, environment: "sandbox" }, "bearer-a"));
    expect(response.status).toBe(204);
  });

  it("bounds delayed device cleanup by its captured generation for same-token and null-token snapshots", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const installation = { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" };
    const register = (token: string, registrationGeneration: number) => SELF.fetch(jsonRequest(
      "/v1/devices", "PUT", { installation, token, environment: "sandbox", registrationGeneration }, "bearer-a",
    ));

    expect((await register(TOKEN_A, 1)).status).toBe(200);
    setBeforeDeviceDeletionBatchForTesting(async () => {
      expect((await register(TOKEN_A, 2)).status).toBe(200);
    });
    expect((await SELF.fetch(jsonRequest("/v1/devices", "DELETE", {
      installationID: INSTALLATION_A, token: TOKEN_A, environment: "sandbox", maximumRegistrationGeneration: 1,
    }, "bearer-a"))).status).toBe(204);
    expect(await row("SELECT apns_token,registration_generation,deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ apns_token: TOKEN_A, registration_generation: 2, deleted_at: null });

    expect((await register(TOKEN_B, 3)).status).toBe(200);
    setBeforeDeviceDeletionBatchForTesting(async () => {
      expect((await register(TOKEN_B, 4)).status).toBe(200);
    });
    expect((await SELF.fetch(jsonRequest("/v1/devices", "DELETE", {
      installationID: INSTALLATION_A, environment: "sandbox", maximumRegistrationGeneration: 3,
    }, "bearer-a"))).status).toBe(204);
    expect(await row("SELECT apns_token,registration_generation,deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ apns_token: TOKEN_B, registration_generation: 4, deleted_at: null });

    setBeforeDeviceDeletionBatchForTesting();
    expect((await SELF.fetch(jsonRequest("/v1/devices", "DELETE", {
      installationID: INSTALLATION_A, environment: "sandbox",
    }, "bearer-a"))).status).toBe(204);
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))?.deleted_at)
      .toBeTypeOf("number");
  });

  function installGitHubLinkFetchMock(options: {
    accountType?: "User" | "Organization";
    accountID?: number;
    membershipRole?: "admin" | "member";
    membershipOrganizationID?: number;
  } = {}) {
    const requests: Array<{ url: string; method: string; body: string }> = [];
    const accountType = options.accountType ?? "User";
    const accountID = options.accountID ?? (accountType === "User" ? 9001 : 7001);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url);
      const body = new TextDecoder().decode(await request.clone().arrayBuffer());
      requests.push({ url: request.url, method: request.method, body });
      if (url.pathname === "/app/installations/303") {
        return Response.json({
          id: 303,
          app_id: Number(testEnv().GITHUB_APP_ID),
          account: { id: accountID, login: accountType === "User" ? "owner-user" : "owner-org", type: accountType },
        });
      }
      if (url.hostname === "github.com" && url.pathname === "/login/oauth/access_token") {
        return Response.json({ access_token: `transient-user-token-${requests.length}`, token_type: "bearer" });
      }
      if (url.pathname === "/user") {
        return Response.json({ id: 9001, login: "authenticated-user", type: "User" });
      }
      if (url.pathname === "/user/memberships/orgs/owner-org") {
        return Response.json({
          state: "active",
          role: options.membershipRole ?? "admin",
          organization: { id: options.membershipOrganizationID ?? accountID, login: "owner-org", type: "Organization" },
          user: { id: 9001, login: "authenticated-user", type: "User" },
        });
      }
      // An ordinary collaborator may have repository access, but this endpoint
      // is deliberately never used as installation-wide authority.
      if (url.pathname === "/user/installations/303/repositories") {
        return Response.json({ total_count: 1, repositories: [{ id: 42 }] });
      }
      if (url.pathname === `/applications/${testEnv().GITHUB_CLIENT_ID}/token` && request.method === "DELETE") {
        return new Response(null, { status: 204 });
      }
      return Response.json({ message: "unexpected test request" }, { status: 500 });
    }));
    return requests;
  }

  async function runSetupCallback(state: string): Promise<{ response: Response; authorizationState: string }> {
    const response = await SELF.fetch(`https://relay.test/v1/github/callback?state=${encodeURIComponent(state)}&installation_id=303&setup_action=install`, {
      redirect: "manual",
    });
    const location = new URL(response.headers.get("location")!);
    return { response, authorizationState: location.searchParams.get("state")! };
  }

  it("links only after the successful two-leg GitHub user-ownership flow and returns a secret-free handoff", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    const requests = installGitHubLinkFetchMock();
    try {
      const start = await SELF.fetch(jsonRequest("/v1/github/link/start", "POST", {}, "bearer-a"));
      expect(start.status).toBe(200);
      const started = await start.json() as { url: string; expiresAt: string };
      const initialState = new URL(started.url).searchParams.get("state")!;
      expect(initialState).toBeTruthy();
      expect(await row("SELECT count(*) AS count FROM github_link_states WHERE state_hash=?", initialState)).toEqual({ count: 0 });

      const setup = await runSetupCallback(initialState);
      expect(setup.response.status).toBe(302);
      expect(setup.response.headers.get("cache-control")).toBe("no-store");
      expect(setup.response.headers.get("referrer-policy")).toBe("no-referrer");
      const authorizationURL = new URL(setup.response.headers.get("location")!);
      expect(authorizationURL.origin + authorizationURL.pathname).toBe("https://github.com/login/oauth/authorize");
      expect(authorizationURL.searchParams.get("client_id")).toBe(testEnv().GITHUB_CLIENT_ID);
      expect(authorizationURL.searchParams.get("redirect_uri")).toBe(testEnv().GITHUB_AUTHORIZATION_CALLBACK_URL);
      expect(setup.authorizationState).toBeTruthy();
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });
      expect(await row("SELECT count(*) AS count FROM github_link_states WHERE authorization_state_hash=?", setup.authorizationState))
        .toEqual({ count: 0 });

      const response = await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=private-oauth-code&state=${encodeURIComponent(setup.authorizationState)}`,
      );
      const body = await response.text();
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toBe("text/html; charset=utf-8");
      expect(response.headers.get("cache-control")).toBe("no-store");
      expect(response.headers.get("x-content-type-options")).toBe("nosniff");
      expect(response.headers.get("content-security-policy")).toBe("default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
      expect(response.headers.get("referrer-policy")).toBe("no-referrer");
      expect(response.headers.get("x-frame-options")).toBe("DENY");
      expect(body).toContain('href="syncmd://assist-linked"');
      for (const secret of [initialState, setup.authorizationState, "private-oauth-code", "transient-user-token", "303"]) {
        expect(body).not.toContain(secret);
      }
      expect(await row("SELECT count(*) AS count FROM github_installations WHERE github_installation_id=303 AND installation_id=?", INSTALLATION_A))
        .toEqual({ count: 1 });
      expect(await row("SELECT authorizing_user_id AS authorizingUserID FROM github_installations WHERE github_installation_id=303 AND installation_id=?", INSTALLATION_A))
        .toEqual({ authorizingUserID: 9001 });
      expect(requests.some(request => request.url.endsWith("/user"))).toBe(true);
      expect(requests.some(request => request.url.includes("/user/installations/303/repositories"))).toBe(false);
      expect(requests.some(request => request.method === "DELETE" && request.url.includes("/applications/"))).toBe(true);
      expect(requests.every(request => !request.url.includes("private-oauth-code") && !request.url.includes("transient-user-token"))).toBe(true);
      const exchange = requests.find(request => request.url === "https://github.com/login/oauth/access_token")!;
      expect(exchange.body).toContain("code=private-oauth-code");
      expect(exchange.body).toContain(`redirect_uri=${encodeURIComponent(testEnv().GITHUB_AUTHORIZATION_CALLBACK_URL)}`);
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("links an organization installation only for an active organization owner", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    const requests = installGitHubLinkFetchMock({ accountType: "Organization" });
    try {
      const initialState = "organization-owner-state";
      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256(initialState), INSTALLATION_A, 0, NOW + 100_000, NOW);
      const setup = await runSetupCallback(initialState);
      const response = await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=organization-owner-code&state=${encodeURIComponent(setup.authorizationState)}`,
      );
      expect(response.status).toBe(200);
      expect(await row("SELECT authorizing_user_id AS authorizingUserID,deleted_at FROM github_installations WHERE github_installation_id=303"))
        .toEqual({ authorizingUserID: 9001, deleted_at: null });
      expect(requests.some(request => request.url.endsWith("/user/memberships/orgs/owner-org"))).toBe(true);
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("denies an ordinary organization member even when that user can access a repository", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    const requests = installGitHubLinkFetchMock({ accountType: "Organization", membershipRole: "member" });
    try {
      const initialState = "organization-member-state";
      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256(initialState), INSTALLATION_A, 0, NOW + 100_000, NOW);
      const setup = await runSetupCallback(initialState);
      const response = await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=repository-collaborator-code&state=${encodeURIComponent(setup.authorizationState)}`,
      );
      expect(response.status).toBe(403);
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });
      expect(requests.some(request => request.url.includes("/user/installations/303/repositories"))).toBe(false);
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("denies an organization membership whose numeric organization identity does not match", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    installGitHubLinkFetchMock({ accountType: "Organization", membershipOrganizationID: 7999 });
    try {
      const initialState = "organization-mismatch-state";
      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256(initialState), INSTALLATION_A, 0, NOW + 100_000, NOW);
      const setup = await runSetupCallback(initialState);
      expect((await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=mismatched-organization-code&state=${encodeURIComponent(setup.authorizationState)}`,
      )).status).toBe(403);
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("never links from setup alone and denies substitution to another user's installation", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    installGitHubLinkFetchMock({ accountID: 9002 });
    try {
      const initialState = "setup-only-state";
      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256(initialState), INSTALLATION_A, 0, NOW + 100_000, NOW);
      const setup = await runSetupCallback(initialState);
      expect(setup.response.status).toBe(302);
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });

      const denied = await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=code-for-user-without-target-access&state=${encodeURIComponent(setup.authorizationState)}`,
      );
      expect(denied.status).toBe(403);
      expect(await denied.json()).toEqual({ error: "GitHub installation administrator required" });
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });
      expect(await row("SELECT authorized_at FROM github_link_states WHERE state_hash=?", await sha256(initialState)))
        .toEqual({ authorized_at: null });
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("enforces one-time and expiry semantics on both hashed link states", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    installGitHubLinkFetchMock();
    try {
      const initialState = "one-time-setup-state";
      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256(initialState), INSTALLATION_A, 0, NOW + 100_000, NOW);
      const setup = await runSetupCallback(initialState);
      expect((await SELF.fetch(`https://relay.test/v1/github/callback?state=${initialState}&installation_id=303`, { redirect: "manual" })).status).toBe(400);
      const first = await SELF.fetch(`https://relay.test/v1/github/authorize/callback?code=first-code&state=${setup.authorizationState}`);
      expect(first.status).toBe(200);
      expect((await SELF.fetch(`https://relay.test/v1/github/authorize/callback?code=reuse-code&state=${setup.authorizationState}`)).status).toBe(400);

      await exec("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
        await sha256("expired-setup"), INSTALLATION_A, 0, NOW - 1, NOW);
      expect((await SELF.fetch("https://relay.test/v1/github/callback?state=expired-setup&installation_id=303", { redirect: "manual" })).status).toBe(400);
      await exec(
        "INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,consumed_at,consumed_nonce,created_at,github_installation_id,authorization_state_hash,authorization_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        await sha256("rotated-original"), INSTALLATION_A, 0, NOW + 100_000, NOW, "setup-nonce", NOW, 303,
        await sha256("expired-authorization"), NOW - 1,
      );
      expect((await SELF.fetch("https://relay.test/v1/github/authorize/callback?code=expired-code&state=expired-authorization")).status).toBe(400);
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("prevents final link writes after deletion-generation change and under concurrent authorization claims", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    installGitHubLinkFetchMock();
    try {
      const seedRotated = async (authorizationState: string) => exec(
        "INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,consumed_at,consumed_nonce,created_at,github_installation_id,authorization_state_hash,authorization_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        await sha256(`original-${authorizationState}`), INSTALLATION_A, 0, NOW + 100_000, NOW, "setup-nonce", NOW, 303,
        await sha256(authorizationState), NOW + 100_000,
      );
      await seedRotated("deletion-guard-state");
      setBeforeGitHubLinkCompletionBatchForTesting(async () => {
        await exec("UPDATE installations SET deleted_at=?,deletion_generation=deletion_generation+1 WHERE id=?", NOW + 1, INSTALLATION_A);
      });
      expect((await SELF.fetch("https://relay.test/v1/github/authorize/callback?code=deletion-code&state=deletion-guard-state")).status).toBe(409);
      expect(await row("SELECT count(*) AS count FROM github_installations")).toEqual({ count: 0 });
      expect(await row("SELECT authorized_at FROM github_link_states WHERE authorization_state_hash=?", await sha256("deletion-guard-state")))
        .toEqual({ authorized_at: null });

      await exec("UPDATE installations SET deleted_at=NULL,deletion_generation=0 WHERE id=?", INSTALLATION_A);
      await seedRotated("concurrent-final-state");
      let arrivals = 0;
      let release!: () => void;
      const barrier = new Promise<void>(resolve => { release = resolve; });
      setBeforeGitHubLinkCompletionBatchForTesting(async () => {
        arrivals += 1;
        if (arrivals === 2) release();
        await barrier;
      });
      const responses = await Promise.all([
        SELF.fetch("https://relay.test/v1/github/authorize/callback?code=concurrent-a&state=concurrent-final-state"),
        SELF.fetch("https://relay.test/v1/github/authorize/callback?code=concurrent-b&state=concurrent-final-state"),
      ]);
      expect(responses.map(response => response.status).sort()).toEqual([200, 409]);
      expect(await row("SELECT count(*) AS count FROM github_installations WHERE github_installation_id=303 AND installation_id=?", INSTALLATION_A))
        .toEqual({ count: 1 });
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("blocks a delayed final OAuth link when signed uninstall wins the interleaving", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    installGitHubLinkFetchMock();
    try {
      const authorizationState = "delayed-final-link-state";
      await exec(
        "INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,consumed_at,consumed_nonce,created_at,github_installation_id,authorization_state_hash,authorization_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        await sha256("delayed-final-link-original"), INSTALLATION_A, 0, NOW + 100_000, NOW, "setup-nonce", NOW, 303,
        await sha256(authorizationState), NOW + 100_000,
      );
      setBeforeGitHubLinkCompletionBatchForTesting(async () => {
        expect((await webhook("delivery-uninstall-before-link", {
          action: "deleted", installation: { id: 303 }, repositories: [], sender: {},
        }, "test-webhook-secret", "installation")).status).toBe(202);
      });
      expect((await SELF.fetch(
        `https://relay.test/v1/github/authorize/callback?code=delayed-link-code&state=${authorizationState}`,
      )).status).toBe(409);
      expect(await row("SELECT count(*) AS count FROM github_installations WHERE github_installation_id=303"))
        .toEqual({ count: 0 });
      expect(await row("SELECT count(*) AS count FROM github_installation_tombstones WHERE github_installation_id=303"))
        .toEqual({ count: 1 });
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("blocks a delayed enrollment when signed uninstall wins the interleaving", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 101, INSTALLATION_A, NOW, 9001);
    setBeforeEnrollmentBatchForTesting(async () => {
      expect((await webhook("delivery-uninstall-before-enrollment", {
        action: "deleted", installation: { id: 101 }, repositories: [], sender: {},
      }, "test-webhook-secret", "installation")).status).toBe(202);
    });
    const response = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
      githubInstallationID: 101, repositoryID: 42, branch: "main",
    }, "bearer-a"));
    expect(response.status).toBe(409);
    expect(await row("SELECT count(*) AS count FROM repo_enrollments WHERE deleted_at IS NULL"))
      .toEqual({ count: 0 });
    expect(await row("SELECT count(*) AS count FROM github_installation_tombstones WHERE github_installation_id=101"))
      .toEqual({ count: 1 });
  });

  it("keeps device registration independent when an enrollment is concurrently tombstoned", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    const installation = { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" };
    setBeforeDeviceRegistrationBatchForTesting(async () => {
      await exec("UPDATE repo_enrollments SET deleted_at=? WHERE channel=?", NOW + 1, CHANNEL_A);
    });

    const response = await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_A, environment: "sandbox", registrationGeneration: 1,
    }, "bearer-a"));
    expect(response.status).toBe(200);
    expect(await row("SELECT apns_token,deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))
      .toEqual({ apns_token: TOKEN_A, deleted_at: null });
    expect(await row("SELECT deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A))
      .toEqual({ deleted_at: NOW + 1 });
  });

  it("purges the authenticated installation and invalidates stale link state generation", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-delete", INSTALLATION_A, TOKEN_A, "sandbox");
    await exec(
      "INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)",
      "stale-state", INSTALLATION_A, 0, NOW + 100_000, NOW,
    );

    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": `delete-${INSTALLATION_A}` },
    }))).status).toBe(204);
    expect(await row("SELECT deleted_at,deletion_generation FROM installations WHERE id=?", INSTALLATION_A)).toMatchObject({ deletion_generation: 1 });
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM installations WHERE id=?", INSTALLATION_A))?.deleted_at).toBeTypeOf("number");
    expect(await row("SELECT count(*) AS count FROM github_link_states s JOIN installations i ON i.id=s.installation_id WHERE s.state_hash='stale-state' AND i.deleted_at IS NULL AND i.deletion_generation=s.deletion_generation")).toEqual({ count: 0 });
    expect(await row("SELECT count(*) AS count FROM device_channels")).toEqual({ count: 0 });
    // The same bearer is now revoked, but retrying after a lost response must
    // still receive an idempotent success from the deletion receipt.
    expect((await SELF.fetch(new Request("https://relay.test/v1/installation", {
      method: "DELETE", headers: { "x-installation-deletion-token": `delete-${INSTALLATION_A}` },
    }))).status).toBe(204);
    expect(await row("SELECT count(*) AS count FROM installation_deletions WHERE installation_id=?", INSTALLATION_A)).toEqual({ count: 1 });
  });

  it("guards device and enrollment writes with the current deletion generation", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await exec("UPDATE installations SET deleted_at=?,deletion_generation=deletion_generation+1 WHERE id=?", NOW, INSTALLATION_A);
    const guardedDevice = await testEnv().DB.prepare(
      "INSERT INTO devices(id,installation_id,apns_token,apns_environment,created_at,updated_at,deleted_at,registration_generation) " +
      "SELECT ?,?,?,?,?,?,NULL,0 WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?)",
    ).bind("device-stale", INSTALLATION_A, TOKEN_A, "sandbox", NOW, NOW, INSTALLATION_A, 0).run();
    const guardedEnrollment = await testEnv().DB.prepare(
      "INSERT INTO repo_enrollments(channel,installation_id,github_installation_id,repository_id,branch,created_at) " +
      "SELECT ?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?)",
    ).bind("channel_stale_generation", INSTALLATION_A, 101, 43, "main", NOW, INSTALLATION_A, 0).run();
    expect(guardedDevice.meta.changes).toBe(0);
    expect(guardedEnrollment.meta.changes).toBe(0);
  });

  it("returns the existing channel when enrollment creation is retried after response loss", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 101, INSTALLATION_A, NOW, 9001);
    const body = { githubInstallationID: 101, repositoryID: 42, branch: "main" };

    const first = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", body, "bearer-a"));
    expect(first.status).toBe(201);
    const created = await first.json() as { channel: string; githubInstallationID: number; repositoryID: number; branch: string };
    expect(created).toEqual({
      channel: expect.stringMatching(/^[A-Za-z0-9_-]{16,128}$/),
      githubInstallationID: 101,
      repositoryID: 42,
      branch: "main",
    });

    // Simulate losing the first successful response and retrying the exact
    // request after reinstall/local state loss.
    const retry = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", body, "bearer-a"));
    expect(retry.status).toBe(200);
    expect(await retry.json()).toEqual(created);
    expect(await row("SELECT count(*) AS count FROM repo_enrollments WHERE installation_id=? AND repository_id=42 AND branch='main' AND deleted_at IS NULL", INSTALLATION_A))
      .toEqual({ count: 1 });
  });

  it("requires legacy links without a numeric authorizer to relink instead of returning destructive empty status", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,NULL)",
      303, INSTALLATION_A, NOW);
    const response = await SELF.fetch(new Request("https://relay.test/v1/github/link/status", {
      headers: { authorization: "Bearer bearer-a" },
    }));
    expect(response.status).toBe(409);
    expect(await row("SELECT deleted_at FROM github_installations WHERE github_installation_id=303"))
      .toEqual({ deleted_at: null });
  });

  it("revalidates organization-owner authority and fails closed without clearing local link state", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 303, INSTALLATION_A, NOW, 9001);
    const priorPrivateKey = testEnv().GITHUB_APP_PRIVATE_KEY;
    testEnv().GITHUB_APP_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----\n${TEST_GITHUB_PRIVATE_KEY}\n-----END PRIVATE KEY-----`;
    setRevalidateInstallationAdministratorForTesting();
    let transientFailure = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const path = new URL(request.url).pathname;
      if (path === "/app/installations/303") return Response.json({
        id: 303, app_id: Number(testEnv().GITHUB_APP_ID),
        account: { id: 7001, login: "owner-org", type: "Organization" },
      });
      if (path === "/user/9001") return Response.json({ id: 9001, login: "former-owner", type: "User" });
      if (path === "/app/installations/303/access_tokens") return Response.json({ token: "installation-members-token" });
      if (path === "/orgs/owner-org/memberships/former-owner") {
        if (transientFailure) return Response.json({ message: "temporary failure" }, { status: 500 });
        return Response.json({
          state: "active", role: "member",
          organization: { id: 7001, login: "owner-org", type: "Organization" },
          user: { id: 9001, login: "former-owner", type: "User" },
        });
      }
      return Response.json({ message: "unexpected test request" }, { status: 500 });
    }));
    try {
      expect((await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
        githubInstallationID: 303, repositoryID: 42, branch: "main",
      }, "bearer-a"))).status).toBe(403);
      expect((await SELF.fetch(new Request("https://relay.test/v1/github/link/status", {
        headers: { authorization: "Bearer bearer-a" },
      }))).status).toBe(403);
      expect(await row("SELECT deleted_at,authorizing_user_id AS authorizingUserID FROM github_installations WHERE github_installation_id=303"))
        .toEqual({ deleted_at: null, authorizingUserID: 9001 });

      transientFailure = true;
      expect((await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
        githubInstallationID: 303, repositoryID: 42, branch: "main",
      }, "bearer-a"))).status).toBe(502);
      expect(await row("SELECT deleted_at FROM github_installations WHERE github_installation_id=303"))
        .toEqual({ deleted_at: null });
    } finally {
      testEnv().GITHUB_APP_PRIVATE_KEY = priorPrivateKey;
    }
  });

  it("makes owned enrollment deletion response-loss idempotent without exposing cross-installation rows", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedInstallation(INSTALLATION_B, "bearer-b");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    expect((await SELF.fetch(new Request(`https://relay.test/v1/enrollments/${CHANNEL_A}`, { method: "DELETE", headers: { authorization: "Bearer bearer-b" } }))).status).toBe(404);
    expect(await row("SELECT deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A)).toEqual({ deleted_at: null });

    const ownedDelete = () => SELF.fetch(new Request(`https://relay.test/v1/enrollments/${CHANNEL_A}`, {
      method: "DELETE", headers: { authorization: "Bearer bearer-a" },
    }));
    expect((await ownedDelete()).status).toBe(204);
    expect((await ownedDelete()).status).toBe(204);
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A))?.deleted_at)
      .toBeTypeOf("number");
    expect((await SELF.fetch(new Request("https://relay.test/v1/enrollments/channel_missing_owned_retry", {
      method: "DELETE", headers: { authorization: "Bearer bearer-a" },
    }))).status).toBe(404);
  });
});

describe("GitHub webhook and durable outbox", () => {
  it("rejects a wrong signature before writing", async () => {
    const response = await webhook("delivery-wrong", pushPayload(), "wrong-secret");
    expect(response.status).toBe(401);
    expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 0 });
  });

  it("authenticates kill-switched webhooks before acknowledging without writes", async () => {
    const prior = testEnv().KILL_SWITCH;
    (testEnv() as unknown as Record<string, string>).KILL_SWITCH = "true";
    try {
      expect((await webhook("delivery-disabled-bad", pushPayload(), "wrong-secret")).status).toBe(401);
      expect((await webhook("delivery-disabled", pushPayload())).status).toBe(202);
      expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 0 });
    } finally {
      (testEnv() as unknown as Record<string, string>).KILL_SWITCH = prior;
    }
  });

  it("acknowledges benign installation lifecycle events without mutating routing", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    for (const [slug, action] of [["created", "created"], ["npa", "new_permissions_accepted"], ["suspend", "suspend"], ["unsuspend", "unsuspend"]] as const) {
      expect((await webhook(`delivery-installation-${slug}`, {
        action, installation: { id: 101 }, repositories: [], sender: {},
      }, "test-webhook-secret", "installation")).status).toBe(202);
    }
    expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 0 });
    expect(await row("SELECT deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A)).toEqual({ deleted_at: null });
    expect((await webhook("delivery-installation-rogue", {
      action: "sneaky", installation: { id: 101 }, sender: {},
    }, "test-webhook-secret", "installation")).status).toBe(400);
  });

  it("tombstones routing on a signed GitHub App uninstall and permits recovery through a new installation", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-uninstall", INSTALLATION_A, TOKEN_A, "sandbox");

    expect((await webhook("delivery-installation-bad", {
      action: "deleted", installation: { id: 101 }, repositories: [], sender: {},
    }, "wrong-secret", "installation")).status).toBe(401);
    const uninstall = await webhook("delivery-installation-delete", {
      action: "deleted", installation: { id: 101 }, repositories: [], sender: {},
    }, "test-webhook-secret", "installation");
    expect(uninstall.status).toBe(202);
    expect(await row("SELECT deleted_at FROM github_installations WHERE github_installation_id=101 AND installation_id=?", INSTALLATION_A))
      .toMatchObject({ deleted_at: expect.any(Number) });
    expect(await row("SELECT deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A))
      .toMatchObject({ deleted_at: expect.any(Number) });
    expect(await row("SELECT count(*) AS count FROM device_channels WHERE channel=?", CHANNEL_A)).toEqual({ count: 0 });

    // A signed callback for the new GitHub installation has already linked it.
    // Re-enrollment must revive the retained tuple/channel without the old
    // installation's foreign key or uniqueness constraint stranding it.
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 303, INSTALLATION_A, NOW + 1, 9001);
    const recovered = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
      githubInstallationID: 303, repositoryID: 42, branch: "main",
    }, "bearer-a"));
    expect(recovered.status).toBe(201);
    expect(await recovered.json()).toEqual({
      channel: CHANNEL_A, githubInstallationID: 303, repositoryID: 42, branch: "main",
    });
    expect(await row("SELECT github_installation_id AS githubInstallationID,deleted_at FROM repo_enrollments WHERE channel=?", CHANNEL_A))
      .toEqual({ githubInstallationID: 303, deleted_at: null });
  });

  it("fails closed when kill-switch configuration is malformed", async () => {
    const prior = testEnv().KILL_SWITCH;
    (testEnv() as unknown as Record<string, string>).KILL_SWITCH = "unexpected";
    try {
      expect((await webhook("delivery-malformed-switch", pushPayload())).status).toBe(503);
      expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 0 });
    } finally {
      (testEnv() as unknown as Record<string, string>).KILL_SWITCH = prior;
    }
  });

  it("deduplicates delivery and creates one outbox row per matching channel", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedInstallation(INSTALLATION_B, "bearer-b");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedEnrollment(INSTALLATION_B, CHANNEL_B);
    expect((await webhook("delivery-1", pushPayload())).status).toBe(202);
    expect((await webhook("delivery-1", pushPayload())).status).toBe(202);
    expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 1 });
    expect(await row("SELECT count(*) AS count FROM outbox")).toEqual({ count: 2 });
    expect(await row("SELECT count(DISTINCT hint) AS count FROM outbox")).toEqual({ count: 2 });
  });

  it("recovers a committed pending row without another GitHub delivery", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-scheduled", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,NULL,NULL,?)", "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "delivery-scheduled", CHANNEL_A, "event_scheduled", NOW, NOW + 10_000);
    const send = vi.spyOn(testEnv().OUTBOX_QUEUE, "send").mockResolvedValue({} as QueueSendResponse);

    expect(await dispatchPendingOutbox(testEnv())).toBe(1);
    expect(send).toHaveBeenCalledWith({ outboxId: "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
    const persisted = await row<{ enqueued_at: number }>("SELECT enqueued_at FROM outbox WHERE id=?", "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    expect(persisted?.enqueued_at).toBeTypeOf("number");
  });

  it("recovers durable outbox rows left unsent by an earlier queue failure", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-recover", "push", 42, NOW, NOW + 10000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,NULL,NULL,?)", "out_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "delivery-recover", CHANNEL_A, "event_bbbbbbbbbbbbbbbb", NOW, NOW + 10000);
    expect((await webhook("delivery-recover", pushPayload())).status).toBe(202);
    const recovered = await row<{ enqueued_at: number }>("SELECT enqueued_at FROM outbox WHERE id='out_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'");
    expect(recovered?.enqueued_at).toBeTypeOf("number");
  });

  it("does not create outbox rows for another branch or repository", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await webhook("delivery-branch", pushPayload(42, "notes"));
    await webhook("delivery-repo", pushPayload(999, "main"));
    expect(await row("SELECT count(*) AS count FROM outbox")).toEqual({ count: 0 });
  });
});

describe("queue fan-out and APNs behavior", () => {
  async function seedOutbox() {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-a", INSTALLATION_A, TOKEN_A, "sandbox");
    await seedDevice("device-b", INSTALLATION_A, TOKEN_B, "production");
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-q", "push", 42, NOW, NOW + 10000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "delivery-q", CHANNEL_A, "event_aaaaaaaaaaaaaaaa", NOW, NOW, NOW + 10000);
  }

  it("routes a device registered before its repository enrollment", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 101, INSTALLATION_A, NOW, 9001);
    const installation = { installationID: INSTALLATION_A, bundleID: "bontecou.Sync-md", appVersion: "1" };
    expect((await SELF.fetch(jsonRequest("/v1/devices", "PUT", {
      installation, token: TOKEN_A, environment: "sandbox", registrationGeneration: 1,
    }, "bearer-a"))).status).toBe(200);
    const enrollment = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
      githubInstallationID: 101, repositoryID: 42, branch: "main",
    }, "bearer-a"));
    expect(enrollment.status).toBe(201);
    const enrolled = await enrollment.json() as { channel: string };
    const queueSend = vi.spyOn(testEnv().OUTBOX_QUEUE, "send").mockResolvedValue({} as QueueSendResponse);
    expect((await webhook("delivery-register-before-enroll", pushPayload())).status).toBe(202);
    expect(queueSend).toHaveBeenCalledTimes(1);
    const outbox = await row<{ id: string }>("SELECT id FROM outbox WHERE delivery_id='delivery-register-before-enroll'");
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(null, { status: 200 });
    }));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;

    const queued = message({ outboxId: outbox!.id });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.acked).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(new RegExp(`/3/device/${TOKEN_A}$`));
    expect(await row("SELECT count(*) AS count FROM device_channels")).toEqual({ count: 0 });
    expect(enrolled.channel).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  });

  it("routes the 101st live enrollment without a device channel snapshot", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await exec("INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) VALUES(?,?,?,NULL,?)", 101, INSTALLATION_A, NOW, 9001);
    await seedDevice("device-many-enrollments", INSTALLATION_A, TOKEN_A, "production");
    let lastChannel = "";
    for (let index = 0; index < 101; index++) {
      const response = await SELF.fetch(jsonRequest("/v1/enrollments", "POST", {
        githubInstallationID: 101, repositoryID: 1_000 + index, branch: "main",
      }, "bearer-a"));
      expect(response.status).toBe(201);
      if (index === 100) lastChannel = (await response.json() as { channel: string }).channel;
    }
    expect(await row("SELECT count(*) AS count FROM repo_enrollments WHERE installation_id=? AND deleted_at IS NULL", INSTALLATION_A))
      .toEqual({ count: 101 });
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-enrollment-101", "push", 1_100, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_10101010101010101010101010101010", "delivery-enrollment-101", lastChannel, "event_enrollment_101", NOW, NOW, NOW + 10_000);
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(null, { status: 200 });
    }));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;

    const queued = message({ outboxId: "out_10101010101010101010101010101010" });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.acked).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(new RegExp(`/3/device/${TOKEN_A}$`));
    expect(await row("SELECT count(*) AS count FROM device_channels")).toEqual({ count: 0 });
  });

  it("does not route an enrollment to a different installation's device", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await seedInstallation(INSTALLATION_B, "bearer-b", "subscription-b");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-owned", INSTALLATION_A, TOKEN_A, "production");
    await seedDevice("device-other-installation", INSTALLATION_B, TOKEN_B, "production");
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-isolation", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_12121212121212121212121212121212", "delivery-isolation", CHANNEL_A, "event_isolation", NOW, NOW, NOW + 10_000);
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(null, { status: 200 });
    }));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;

    const queued = message({ outboxId: "out_12121212121212121212121212121212" });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.acked).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(new RegExp(`/3/device/${TOKEN_A}$`));
  });

  it("suppresses tombstoned enrollments and expired entitlements", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-suppressed", INSTALLATION_A, TOKEN_A, "sandbox");
    await exec("UPDATE repo_enrollments SET deleted_at=? WHERE channel=?", NOW, CHANNEL_A);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-tombstoned", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_13131313131313131313131313131313", "delivery-tombstoned", CHANNEL_A, "event_tombstoned", NOW, NOW, NOW + 10_000);
    const fetchMock = vi.fn(async () => new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;

    const tombstoned = message({ outboxId: "out_13131313131313131313131313131313" });
    await consumeOutbox(tombstoned as unknown as Message<OutboxMessage>, testEnv());
    expect(tombstoned.state.acked).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();

    await exec("UPDATE repo_enrollments SET deleted_at=NULL WHERE channel=?", CHANNEL_A);
    await exec("UPDATE entitlements SET expires_at=? WHERE installation_id=?", NOW - 1, INSTALLATION_A);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-expired-entitlement", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_14141414141414141414141414141414", "delivery-expired-entitlement", CHANNEL_A, "event_expired", NOW, NOW, NOW + 10_000);
    const expired = message({ outboxId: "out_14141414141414141414141414141414" });
    await consumeOutbox(expired as unknown as Message<OutboxMessage>, testEnv());
    expect(expired.state.acked).toBe(true);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("fans one repository push out to independently eligible installations", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await seedInstallation(INSTALLATION_B, "bearer-b", "subscription-b");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A, 42, "main");
    await seedEnrollment(INSTALLATION_B, CHANNEL_B, 42, "main");
    await seedDevice("device-installation-a", INSTALLATION_A, TOKEN_A, "production");
    await seedDevice("device-installation-b", INSTALLATION_B, TOKEN_B, "production");
    const send = vi.spyOn(testEnv().OUTBOX_QUEUE, "send").mockResolvedValue({} as QueueSendResponse);

    expect((await webhook("delivery-multi-installation", pushPayload())).status).toBe(202);
    expect(send).toHaveBeenCalledTimes(2);
    const rows = await testEnv().DB.prepare(
      "SELECT id FROM outbox WHERE delivery_id=? ORDER BY channel",
    ).bind("delivery-multi-installation").all<{ id: string }>();
    expect(rows.results).toHaveLength(2);
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(null, { status: 200 });
    }));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    for (const outbox of rows.results) {
      const queued = message({ outboxId: outbox.id });
      await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
      expect(queued.state.acked).toBe(true);
    }

    expect(calls).toHaveLength(2);
    expect(calls.some(url => url.endsWith(`/3/device/${TOKEN_A}`))).toBe(true);
    expect(calls.some(url => url.endsWith(`/3/device/${TOKEN_B}`))).toBe(true);
  });

  it("uses the minimal opaque payload and fans out across sandbox and production", async () => {
    await seedOutbox();
    const calls: Array<{ url: string; body: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), body: String(init?.body) });
      return new Response(null, { status: 200 });
    }));
    // Avoid private-key parsing while keeping sendApns exercised through its request path.
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    const queued = message({ outboxId: "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.acked).toBe(true);
    expect(calls).toHaveLength(2);
    expect(calls.some(call => call.url.startsWith("https://api.sandbox.push.apple.com/3/device/"))).toBe(true);
    expect(calls.some(call => call.url.startsWith("https://api.push.apple.com/3/device/"))).toBe(true);
    for (const call of calls) expect(JSON.parse(call.body)).toEqual({ aps: { "content-available": 1 }, channel: CHANNEL_A, hint: "event_aaaaaaaaaaaaaaaa" });
    expect(calls.map(call => call.body).join()).not.toContain("repository");
    expect(await row("SELECT count(*) AS count FROM apns_attempts WHERE status='success'")).toEqual({ count: 2 });
  });

  it("leases each device so concurrent duplicate Queue deliveries cause one APNs side effect", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-concurrent", INSTALLATION_A, TOKEN_A, "sandbox");
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-concurrent", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_dddddddddddddddddddddddddddddddd", "delivery-concurrent", CHANNEL_A, "event_concurrent", NOW, NOW, NOW + 10_000);
    let fetchCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      fetchCalls += 1;
      await new Promise(resolve => setTimeout(resolve, 25));
      return new Response(null, { status: 200 });
    }));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    const first = message({ outboxId: "out_dddddddddddddddddddddddddddddddd" });
    const duplicate = message({ outboxId: "out_dddddddddddddddddddddddddddddddd" });

    await Promise.all([
      consumeOutbox(first as unknown as Message<OutboxMessage>, testEnv()),
      consumeOutbox(duplicate as unknown as Message<OutboxMessage>, testEnv()),
    ]);
    expect(fetchCalls).toBe(1);
    expect([first.state.acked, duplicate.state.acked].filter(Boolean)).toHaveLength(1);
    expect(first.state.retries.length + duplicate.state.retries.length).toBe(1);
    expect(await row("SELECT count(*) AS count FROM apns_attempts WHERE outbox_id='out_dddddddddddddddddddddddddddddddd'")).toEqual({ count: 1 });
  });

  it("retries transient responses without completing the outbox", async () => {
    await seedOutbox();
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ reason: "ServiceUnavailable" }), { status: 503, headers: { "content-type": "application/json" } })));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    const queued = message({ outboxId: "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.retries).toEqual([{ delaySeconds: 60 }]);
    expect(await row("SELECT completed_at FROM outbox WHERE id='out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'")).toEqual({ completed_at: null });
    expect(await row("SELECT count(*) AS count FROM apns_attempts WHERE status='transient'")).toEqual({ count: 2 });
  });

  it("does not invalidate a valid token for APNs provider configuration errors", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-config", INSTALLATION_A, TOKEN_A, "sandbox");
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-config", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_cccccccccccccccccccccccccccccccc", "delivery-config", CHANNEL_A, "event_config", NOW, NOW, NOW + 10_000);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ reason: "BadTopic" }), { status: 400, headers: { "content-type": "application/json" } })));
    const item = message({ outboxId: "out_cccccccccccccccccccccccccccccccc" });

    await consumeOutbox(item as never, testEnv());
    expect(item.state.retries).toEqual([{ delaySeconds: 60 }]);
    expect(await row("SELECT deleted_at FROM devices WHERE id='device-config'")).toEqual({ deleted_at: null });
    expect(await row("SELECT status FROM apns_attempts WHERE device_id='device-config'")).toEqual({ status: "transient" });
  });

  it("does not tombstone a newer token after a stale APNs invalid-token response", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-rotation", INSTALLATION_A, TOKEN_A, "sandbox");
    await exec("UPDATE devices SET registration_generation=1 WHERE id='device-rotation'");
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "delivery-rotation", "push", 42, NOW, NOW + 10_000);
    await exec("INSERT INTO outbox VALUES(?,?,?,?,?,?,NULL,?)", "out_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "delivery-rotation", CHANNEL_A, "event_rotation", NOW, NOW, NOW + 10_000);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ reason: "BadDeviceToken" }), { status: 400, headers: { "content-type": "application/json" } })));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    setBeforeInvalidTokenTombstoneForTesting(async () => {
      await exec("UPDATE devices SET apns_token=?,registration_generation=2,updated_at=? WHERE id='device-rotation'", TOKEN_B, NOW + 1);
    });
    const queued = message({ outboxId: "out_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" });

    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());

    expect(queued.state.acked).toBe(true);
    expect(await row("SELECT apns_token,registration_generation,deleted_at FROM devices WHERE id='device-rotation'"))
      .toEqual({ apns_token: TOKEN_B, registration_generation: 2, deleted_at: null });
    expect(await row("SELECT count(*) AS count FROM device_channels WHERE device_id='device-rotation'")).toEqual({ count: 0 });
  });

  it("invalidates permanent bad tokens and completes idempotently", async () => {
    await seedOutbox();
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ reason: "BadDeviceToken" }), { status: 400, headers: { "content-type": "application/json" } })));
    testEnv().APNS_PRIVATE_KEY = TEST_APNS_PRIVATE_KEY;
    const queued = message({ outboxId: "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
    await consumeOutbox(queued as unknown as Message<OutboxMessage>, testEnv());
    expect(queued.state.acked).toBe(true);
    expect(await row("SELECT count(*) AS count FROM devices WHERE deleted_at IS NOT NULL")).toEqual({ count: 2 });
    expect(await row("SELECT count(*) AS count FROM device_channels")).toEqual({ count: 0 });
    const second = message({ outboxId: "out_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" });
    await consumeOutbox(second as unknown as Message<OutboxMessage>, testEnv());
    expect(second.state.acked).toBe(true);
  });

  it("classifies APNs responses and payload without sensitive fields", () => {
    expect(classifyApns(200)).toBe("success");
    expect(classifyApns(410, "Unregistered")).toBe("invalidToken");
    expect(classifyApns(400, "BadTopic")).toBe("transient");
    expect(classifyApns(429)).toBe("transient");
    expect(JSON.parse(silentPayload({ channel: CHANNEL_A, hint: "event_aaaaaaaaaaaaaaaa" }))).toEqual({ aps: { "content-available": 1 }, channel: CHANNEL_A, hint: "event_aaaaaaaaaaaaaaaa" });
  });
});

describe("App Store server notifications", () => {
  it("accepts signed test and renewal-extension summary notifications without mutating entitlement state", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    const before = await row("SELECT expires_at,revoked_at,event_time FROM entitlements WHERE original_transaction_id='subscription-a'");
    const test = verifiedNotification({
      notificationUUID: "notification-test", notificationType: "TEST",
      originalTransactionId: null, expiresAt: null, revokedAt: null,
    });
    const summary = verifiedNotification({
      notificationUUID: "notification-renewal-extension", notificationType: "RENEWAL_EXTENSION",
      originalTransactionId: null, expiresAt: null, revokedAt: null,
    });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: test }))).status).toBe(202);
    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: summary }))).status).toBe(202);
    expect(await row("SELECT count(*) AS count FROM app_store_notifications WHERE processed_at IS NOT NULL")).toEqual({ count: 2 });
    expect(await row("SELECT expires_at,revoked_at,event_time FROM entitlements WHERE original_transaction_id='subscription-a'")).toEqual(before);
  });

  it("extends relay access through a verified billing grace period and rejects mismatched grace state", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await exec("UPDATE entitlements SET expires_at=? WHERE original_transaction_id='subscription-a'", NOW - 1);
    const grace = verifiedNotification({
      notificationUUID: "notification-grace", notificationType: "DID_FAIL_TO_RENEW", subtype: "GRACE_PERIOD",
      expiresAt: NOW - 1, gracePeriodExpiresAt: NOW + 100_000, eventTime: NOW + 1,
    });
    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: grace }))).status).toBe(202);
    expect(await row("SELECT expires_at,event_time FROM entitlements WHERE original_transaction_id='subscription-a'"))
      .toEqual({ expires_at: NOW + 100_000, event_time: NOW + 1 });

    const invalid = verifiedNotification({
      notificationUUID: "notification-invalid-grace", notificationType: "DID_RENEW", subtype: null,
      gracePeriodExpiresAt: NOW + 200_000,
    });
    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: invalid }))).status).toBe(400);
  });

  it("rejects unsupported or transaction-inconsistent notification types", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    const unsupported = verifiedNotification({ notificationUUID: "notification-unsupported", notificationType: "CONSUMPTION_REQUEST" });
    const fakeRevoke = verifiedNotification({ notificationUUID: "notification-fake-revoke", notificationType: "REVOKE", revokedAt: null });
    const fakeRenewalRevocation = verifiedNotification({ notificationUUID: "notification-renewal-revoked", notificationType: "DID_RENEW", revokedAt: NOW + 1 });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: unsupported }))).status).toBe(400);
    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: fakeRevoke }))).status).toBe(400);
    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: fakeRenewalRevocation }))).status).toBe(400);
    expect(await row("SELECT count(*) AS count FROM app_store_notifications")).toEqual({ count: 0 });
  });

  it("retries an existing unprocessed notification and marks it complete atomically", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await exec(
      "INSERT INTO app_store_notifications(notification_uuid,notification_type,original_transaction_id,received_at,event_time,processed_at,retention_until) VALUES(?,?,?,?,?,NULL,?)",
      "notification-pending", "REVOKE", "subscription-a", NOW, NOW + 2_500, NOW + 86_400_000,
    );
    const payload = verifiedNotification({
      notificationUUID: "notification-pending", notificationType: "REVOKE",
      revokedAt: NOW + 2_500, eventTime: NOW + 2_500,
    });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: payload }))).status).toBe(202);
    expect((await row<{ processed_at: number }>("SELECT processed_at FROM app_store_notifications WHERE notification_uuid='notification-pending'"))?.processed_at).toBeTypeOf("number");
    expect((await row<{ revoked_at: number }>("SELECT revoked_at FROM entitlements WHERE original_transaction_id='subscription-a'"))?.revoked_at).toBe(NOW + 2_500);
  });

  it("deduplicates the verified notification UUID without a second mutation", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    const payload = verifiedNotification({ notificationUUID: "notification-dedupe", expiresAt: NOW + 123_456, eventTime: NOW + 1 });
    const request = () => jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: payload });

    expect((await SELF.fetch(request())).status).toBe(202);
    expect((await SELF.fetch(request())).status).toBe(202);
    expect(await row("SELECT count(*) AS count FROM app_store_notifications WHERE notification_uuid='notification-dedupe'")).toEqual({ count: 1 });
    expect(await row("SELECT expires_at,event_time FROM entitlements WHERE original_transaction_id='subscription-a'")).toEqual({ expires_at: NOW + 123_456, event_time: NOW + 1 });
  });

  it("ignores an out-of-order verified revocation after a newer entitlement event", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await exec("UPDATE entitlements SET event_time=?,expires_at=? WHERE original_transaction_id=?", NOW + 500, NOW + 999_999, "subscription-a");
    const stale = verifiedNotification({
      notificationUUID: "notification-stale-revocation",
      notificationType: "REVOKE",
      expiresAt: NOW - 100,
      revokedAt: NOW - 50,
      eventTime: NOW + 100,
    });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: stale }))).status).toBe(202);
    expect(await row("SELECT expires_at,revoked_at,event_time FROM entitlements WHERE original_transaction_id='subscription-a'")).toEqual({
      expires_at: NOW + 999_999,
      revoked_at: null,
      event_time: NOW + 500,
    });
    expect(await row("SELECT revoked_at FROM sessions WHERE installation_id=?", INSTALLATION_A)).toEqual({ revoked_at: null });
    expect(await row("SELECT count(*) AS count FROM app_store_notifications WHERE notification_uuid='notification-stale-revocation'")).toEqual({ count: 1 });
  });

  it("tombstones expired installation access without requiring a still-valid client session", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-expired", INSTALLATION_A, TOKEN_A, "sandbox");
    const expired = verifiedNotification({
      notificationUUID: "notification-expired",
      notificationType: "EXPIRED",
      expiresAt: NOW + 2_000,
      revokedAt: null,
      eventTime: NOW + 2_000,
    });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: expired }))).status).toBe(202);
    expect((await row<{ revoked_at: number }>("SELECT revoked_at FROM sessions WHERE installation_id=?", INSTALLATION_A))?.revoked_at).toBeTypeOf("number");
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))?.deleted_at).toBeTypeOf("number");
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM installations WHERE id=?", INSTALLATION_A))?.deleted_at).toBeTypeOf("number");
  });

  it("applies a newer verified revocation and tombstones installation access", async () => {
    await seedInstallation(INSTALLATION_A, "bearer-a", "subscription-a");
    await seedEnrollment(INSTALLATION_A, CHANNEL_A);
    await seedDevice("device-revoked", INSTALLATION_A, TOKEN_A, "sandbox");
    const revocation = verifiedNotification({
      notificationUUID: "notification-new-revocation",
      notificationType: "REVOKE",
      revokedAt: NOW + 2_000,
      eventTime: NOW + 2_000,
    });

    expect((await SELF.fetch(jsonRequest("/v1/app-store/notifications", "POST", { signedPayload: revocation }))).status).toBe(202);
    expect((await row<{ revoked_at: number }>("SELECT revoked_at FROM sessions WHERE installation_id=?", INSTALLATION_A))?.revoked_at).toBeTypeOf("number");
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM devices WHERE installation_id=?", INSTALLATION_A))?.deleted_at).toBeTypeOf("number");
    expect((await row<{ deleted_at: number }>("SELECT deleted_at FROM installations WHERE id=?", INSTALLATION_A))?.deleted_at).toBeTypeOf("number");
  });
});

describe("retention", () => {
  it("deletes expired delivery and notification records while retaining current ones", async () => {
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "old-delivery", "push", 1, NOW - 1000, NOW - 1);
    await exec("INSERT INTO webhook_deliveries VALUES(?,?,?,?,NULL,?)", "new-delivery", "push", 1, NOW, NOW + 1000);
    await exec("INSERT INTO app_store_notifications VALUES(?,?,?,?,?,?,?)", "old-notification", "EXPIRED", null, NOW - 1000, NOW - 1000, NOW - 1000, NOW - 1);
    await exec("INSERT INTO app_store_notifications VALUES(?,?,?,?,?,?,?)", "new-notification", "DID_RENEW", null, NOW, NOW, NOW, NOW + 1000);
    await cleanupRetention(testEnv(), NOW);
    expect(await row("SELECT count(*) AS count FROM webhook_deliveries")).toEqual({ count: 1 });
    expect(await row("SELECT count(*) AS count FROM app_store_notifications")).toEqual({ count: 1 });
  });
});
