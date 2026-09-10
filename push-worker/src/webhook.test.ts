import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker, {
  collapseID,
  notificationText,
  opaqueRateLimitKey,
  parseRegisterRequest,
  shouldPruneDevice,
  summarizePushEvent,
  timingSafeEqualHex,
  verifyGithubSignature,
  type Env,
} from "./index";

// ---------------------------------------------------------------------------
// Webhook end-to-end seam: mocked APNs, Map-backed fake REGISTRY KV,
// promise-collecting ExecutionContext stub. No network, all in-process.
// ---------------------------------------------------------------------------

const { sendApnsMock } = vi.hoisted(() => ({ sendApnsMock: vi.fn() }));
vi.mock("./apns", () => ({ sendApns: sendApnsMock }));
const consoleInfoMock = vi.spyOn(console, "info").mockImplementation(() => {});

class RoutingMap extends Map<string, string> {
  override set(key: string, value: string): this {
    super.set(key, value);
    if (!key.startsWith("device:")) return this;
    const secret = key.slice("device:".length);
    for (const existing of [...this.keys()]) {
      if (existing.startsWith("route:") && existing.endsWith(`:${secret}`)) super.delete(existing);
    }
    try {
      const device = JSON.parse(value) as { repos?: unknown; linkedInstallations?: unknown };
      if (Array.isArray(device.linkedInstallations)) {
        for (const installation of device.linkedInstallations) {
          if (typeof installation === "object" && installation !== null
              && typeof (installation as { id?: unknown }).id === "number") {
            super.set(`route:github-app:${(installation as { id: number }).id}:${secret}`, "1");
          }
        }
      }
    } catch {
      // Malformed-record tests intentionally leave this device unindexed.
    }
    return this;
  }
}

class FakeRegistry {
  store = new RoutingMap();
  deletedKeys: string[] = [];
  /** Every put() call in order, with its options (for TTL assertions). */
  putCalls: Array<{
    key: string;
    value: string;
    options?: { expirationTtl?: number; expiration?: number };
  }> = [];
  /** Keys that list() reports but get() returns null for (simulates a delete race). */
  nullOnGet = new Set<string>();
  /** When set, list() pages in chunks of this size with cursors (KV pagination). */
  pageSize = 0;
  /** Every list() call's options, in order (for cursor-round-trip assertions). */
  listCalls: Array<{ prefix?: string; cursor?: string }> = [];

  async get(key: string): Promise<string | null> {
    if (this.nullOnGet.has(key)) return null;
    return this.store.get(key) ?? null;
  }
  async put(
    key: string,
    value: string,
    options?: { expirationTtl?: number; expiration?: number },
  ): Promise<void> {
    this.store.set(key, value);
    this.putCalls.push({ key, value, options });
  }
  async delete(key: string): Promise<void> {
    this.deletedKeys.push(key);
    this.store.delete(key);
  }
  async list(options: { prefix?: string; cursor?: string }) {
    this.listCalls.push(options);
    const names = [...this.store.keys()]
      .filter((k) => k.startsWith(options.prefix ?? ""))
      .sort();
    if (this.pageSize <= 0) {
      return { keys: names.map((name) => ({ name })), list_complete: true };
    }
    // KV semantics: cursor is an opaque offset into the remaining result set.
    const start = options.cursor ? Number(options.cursor) : 0;
    const page = names.slice(start, start + this.pageSize);
    const next = start + this.pageSize;
    return {
      keys: page.map((name) => ({ name })),
      list_complete: next >= names.length,
      cursor: next < names.length ? String(next) : undefined,
    };
  }
}

const LEGACY_WEBHOOK_SECRET = "test-webhook-secret";
const APP_WEBHOOK_SECRET = "test-github-app-webhook-secret";
const apnsOk = () => new Response(null, { status: 200 });

function makeEnv(registry: FakeRegistry): Env {
  // Most delivery tests exercise a cached owner proof. Tests covering owner
  // revalidation explicitly remove this seam before invoking the webhook.
  registry.store.set("github-admin:101:42", "1");
  return {
    REGISTRY: registry as unknown as Env["REGISTRY"],
    GITHUB_APP_WEBHOOK_SECRET: APP_WEBHOOK_SECRET,
    GITHUB_APP_ID: "123456",
    GITHUB_APP_CLIENT_ID: "IvTestClient123",
    GITHUB_APP_CLIENT_SECRET: "test-client-secret",
    GITHUB_APP_PRIVATE_KEY_PKCS8: "test-private-key",
    GITHUB_APP_SLUG: "gitsync-md-push-sync-test",
    GITHUB_APP_CALLBACK_URL: "https://push.example.test/v1/github-app/oauth/callback",
    APNS_KEY_P8: "test-key-p8",
    APNS_KEY_ID: "KEYID12345",
    APNS_TEAM_ID: "TEAMID12345",
    APNS_TOPIC: "com.example.app",
    NOTIFY_COLLAPSE_SECONDS: "120",
    REGISTER_RATE_LIMIT_PER_HOUR: "20",
  };
}

async function signedWebhookRequest(event: string, payload: unknown, secret = APP_WEBHOOK_SECRET): Promise<Request> {
  const body = JSON.stringify(payload);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(body)));
  const sig = "sha256=" + Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
  return new Request("https://push.example.test/v1/github-webhook", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-github-event": event,
      "x-hub-signature-256": sig,
    },
    body,
  });
}

/** Runs the webhook and drains ctx.waitUntil background work before returning. */
async function runWebhook(env: Env, request: Request): Promise<Response> {
  const pending: Promise<unknown>[] = [];
  const ctx = {
    waitUntil: (p: Promise<unknown>) => {
      pending.push(p);
    },
  } as unknown as ExecutionContext;
  const response = await worker.fetch(request, env, ctx);
  await Promise.all(pending);
  return response;
}

function pushEvent(repo: string, commitCount = 2, branch = "main", ownerID = 9001) {
  return {
    repository: { full_name: repo, owner: { id: ownerID } },
    installation: { id: 101, node_id: "synthetic" },
    ref: `refs/heads/${branch}`,
    after: "a".repeat(40),
    commits: Array.from({ length: commitCount }, (_, i) => ({ message: `c${i}` })),
  };
}

async function notificationThrottleKey(repo: string, token: string, branch = "main"): Promise<string> {
  return `notif:${await collapseID(repo, branch)}:${token}`;
}

function linkedInstallation(
  id = 101,
  status: "active" | "suspended" = "active",
  accountLogin = "acme",
) {
  return {
    id,
    accountID: 9001,
    accountLogin,
    accountType: "Organization",
    authorizingUserID: 42,
    repositorySelection: "all",
    htmlURL: `https://github.com/settings/installations/${id}`,
    status,
    connectedAt: 1,
  };
}

function deviceRecord(
  token: string,
  repos: string[],
  linkedInstallations: unknown[] = [linkedInstallation()],
): string {
  return JSON.stringify({ token, environment: "development", repos, updatedAt: 1, linkedInstallations });
}

function registerRequest(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("https://push.example.test/v1/register", {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

function unregisterRequest(body: unknown): Request {
  return new Request("https://push.example.test/v1/unregister", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

function githubAppPost(path: string, body: unknown, ip = "203.0.113.22"): Request {
  return new Request(`https://push.example.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": ip },
    body: JSON.stringify(body),
  });
}

async function testGitHubAppPrivateKey(): Promise<string> {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  if (!("privateKey" in pair)) throw new Error("expected key pair");
  const exported = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  if (!(exported instanceof ArrayBuffer)) throw new Error("expected PKCS#8 bytes");
  const bytes = new Uint8Array(exported);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const lines = (btoa(binary).match(/.{1,64}/g) ?? []).join("\n");
  return `-----BEGIN PRIVATE KEY-----\n${lines}\n-----END PRIVATE KEY-----`;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("timingSafeEqualHex", () => {
  it("matches identical hex", () => {
    expect(timingSafeEqualHex("aabbcc", "aabbcc")).toBe(true);
  });
  it("rejects different values", () => {
    expect(timingSafeEqualHex("aabbcc", "aabbcd")).toBe(false);
  });
  it("rejects length mismatch without throwing", () => {
    expect(timingSafeEqualHex("aa", "aabb")).toBe(false);
  });
  it("rejects non-hex input", () => {
    expect(timingSafeEqualHex("zz", "zz")).toBe(false);
  });
});

describe("verifyGithubSignature", () => {
  const secret = "s3cret";
  const body = JSON.stringify({ zen: "Design for failure." });

  async function sign(payload: string, key: string): Promise<string> {
    const cryptoKey = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(key),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const mac = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(payload)));
    return "sha256=" + Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
  }

  it("accepts a valid signature", async () => {
    const header = await sign(body, secret);
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, header, secret)).toBe(true);
  });
  it("rejects a wrong secret", async () => {
    const header = await sign(body, "other");
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, header, secret)).toBe(false);
  });
  it("rejects a missing header", async () => {
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, null, secret)).toBe(false);
  });
});

describe("parseRegisterRequest", () => {
  const token = "a".repeat(64);

  it("accepts a valid registration and normalizes repo names", () => {
    const result = parseRegisterRequest({
      token,
      environment: "development",
      deviceSecret: "abc-123-def",
      repos: ["CodyBontecou/GitSync.md", "codybontecou/gitsync.md", "user/travel"],
    });
    expect(result).not.toBeNull();
    expect(result!.repos).toEqual(["codybontecou/gitsync.md", "user/travel"]);
    expect(result!.environment).toBe("development");
  });
  it("rejects a malformed token", () => {
    expect(parseRegisterRequest({ token: "xyz", environment: "production", deviceSecret: "abc-123-def", repos: [] })).toBeNull();
  });
  it("rejects malformed or traversal-shaped repo names", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "abc-123-def", repos: ["no slash"] })).toBeNull();
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "abc-123-def", repos: ["../vault"] })).toBeNull();
  });
  it("rejects a short device secret", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "short", repos: [] })).toBeNull();
  });
  it("rejects unknown environment", () => {
    expect(parseRegisterRequest({ token, environment: "staging", deviceSecret: "abc-123-def", repos: [] })).toBeNull();
  });
});

describe("shouldPruneDevice", () => {
  it("prunes only responses proving that the device token is unusable", () => {
    expect(shouldPruneDevice(410, "Unregistered")).toBe(true);
    expect(shouldPruneDevice(400, "BadDeviceToken")).toBe(true);
    expect(shouldPruneDevice(400, "DeviceTokenNotForTopic")).toBe(true);
    expect(shouldPruneDevice(400, "BadTopic")).toBe(false);
    expect(shouldPruneDevice(403, "InvalidProviderToken")).toBe(false);
    expect(shouldPruneDevice(500, null)).toBe(false);
  });
});

describe("summarizePushEvent", () => {
  it("extracts repository, branch, head, commit count, and deletion flag", () => {
    const summary = summarizePushEvent({
      repository: { full_name: "CodyBontecou/travel" },
      ref: "refs/heads/feature/notes",
      after: "A".repeat(40),
      commits: [{}, {}, {}],
    });
    expect(summary).toEqual({
      repoFullName: "codybontecou/travel",
      branch: "feature/notes",
      headSHA: "a".repeat(40),
      commitCount: 3,
      isDeletion: false,
      installationID: null,
    });
  });
  it("flags deletions", () => {
    const summary = summarizePushEvent({
      repository: { full_name: "a/b" },
      ref: "refs/heads/main",
      after: "0".repeat(40),
      deleted: true,
      commits: [],
    });
    expect(summary.isDeletion).toBe(true);
  });
  it("does not treat a tag push as a branch push", () => {
    const summary = summarizePushEvent({
      repository: { full_name: "a/b" },
      ref: "refs/tags/v1.0.0",
      after: "a".repeat(40),
      commits: [],
    });
    expect(summary.branch).toBeNull();
  });
  it("rejects repository names outside the canonical GitHub routing envelope", () => {
    expect(summarizePushEvent({ repository: { full_name: "../device:secret" } }).repoFullName).toBeNull();
    expect(summarizePushEvent({ repository: { full_name: "owner/repo/extra" } }).repoFullName).toBeNull();
  });
  it("rejects branch hints that cannot fit the app's safe routing envelope", () => {
    expect(summarizePushEvent({ ref: "refs/heads/bad\nbranch" }).branch).toBeNull();
    expect(summarizePushEvent({ ref: `refs/heads/${"🙂".repeat(100)}` }).branch).toBeNull();
  });
  it("accepts only complete SHA-1 or SHA-256 object IDs", () => {
    expect(summarizePushEvent({ after: "a".repeat(64) }).headSHA).toBe("a".repeat(64));
    expect(summarizePushEvent({ after: "a".repeat(41) }).headSHA).toBeNull();
  });
  it("survives malformed payloads", () => {
    expect(summarizePushEvent(null)).toEqual({
      repoFullName: null,
      branch: null,
      headSHA: null,
      commitCount: 0,
      isDeletion: false,
      installationID: null,
    });
  });
});

describe("notificationText", () => {
  it("singularizes one commit", () => {
    expect(notificationText("a/b", 1).body).toBe("1 new commit — sync requested; tap to check");
  });
  it("pluralizes multiple commits", () => {
    expect(notificationText("a/b", 3).body).toBe("3 new commits — sync requested; tap to check");
  });
});

describe("collapseID", () => {
  it("is stable, branch-specific, ASCII, and within APNs' 64-byte limit", async () => {
    const first = await collapseID("acme/private-vault", "feature/日本語");
    expect(first).toBe(await collapseID("acme/private-vault", "feature/日本語"));
    expect(first).not.toBe(await collapseID("acme/private-vault", "main"));
    expect(first).toMatch(/^repo:[0-9a-f]+$/);
    expect(new TextEncoder().encode(first).byteLength).toBeLessThanOrEqual(64);
  });
});

describe("github-webhook device pruning", () => {
  const repo = "acme/app";
  const tokenA = "a".repeat(64);
  const tokenB = "b".repeat(64);
  const tokenC = "c".repeat(64);
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk()); // default: delivery succeeds
    consoleInfoMock.mockClear();
  });

  it("does not notify for tag pushes or deleted branches", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    const tagPush = { ...pushEvent(repo), ref: "refs/tags/v1.0.0" };
    const deletion = { ...pushEvent(repo), deleted: true };

    expect((await runWebhook(env, await signedWebhookRequest("push", tagPush))).status).toBe(200);
    expect((await runWebhook(env, await signedWebhookRequest("push", deletion))).status).toBe(200);
    expect(sendApnsMock).not.toHaveBeenCalled();
  });

  it("does not scan unindexed device records and routes them after registration repairs the index", async () => {
    Map.prototype.set.call(registry.store, "device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).not.toHaveBeenCalled();
    expect(registry.listCalls.at(-1)?.prefix).toBe("route:github-app:101:");

    const registration = await runWebhook(env, registerRequest({
      token: tokenA,
      environment: "development",
      deviceSecret: "secret-one",
      repos: [repo],
    }));
    expect(registration.status).toBe(200);
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
  });

  it("prunes device:<secret> when APNs rejects with 410 (unregistered)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(null, { status: 410 }));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);

    // The real registration and its current/stale route indexes are deleted...
    expect(registry.deletedKeys).toEqual([
      "device:secret-one",
      "route:legacy:acme/app:secret-one",
      "route:github-app:101:secret-one",
    ]);
    expect(registry.store.has("device:secret-one")).toBe(false);
    // ...and the old garbage key (device:<repo>:<token>) is neither deleted nor written.
    expect(registry.deletedKeys).not.toContain(`device:${repo}:${tokenA}`);
    expect(registry.store.has(`device:${repo}:${tokenA}`)).toBe(false);
    // A rejected delivery is left unthrottled; a later registration/event may retry.
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(false);
  });

  it("prunes device:<secret> when APNs identifies a bad device token", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(
      JSON.stringify({ reason: "BadDeviceToken" }),
      { status: 400 },
    ));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(registry.deletedKeys).toEqual([
      "device:secret-one",
      "route:legacy:acme/app:secret-one",
      "route:github-app:101:secret-one",
    ]);
    expect(registry.store.has("device:secret-one")).toBe(false);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(false);
  });

  it("keeps a valid registration when APNs rejects relay configuration", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(
      JSON.stringify({ reason: "BadTopic" }),
      { status: 400 },
    ));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(false);
  });

  it("keeps the registration when delivery succeeds (200)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(true);
    const notification = sendApnsMock.mock.calls[0][1];
    expect(notification.contentAvailable).toBe(true);
    expect(notification.userInfo).toEqual({
      repo,
      branch: "main",
      head: "a".repeat(40),
      hint: "a".repeat(40),
    });
    expect(new TextEncoder().encode(notification.collapseId).byteLength).toBeLessThanOrEqual(64);
  });

  it("routes GitHub App pushes only to devices linked to that active installation", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    registry.store.set("device:secret-two", deviceRecord(tokenB, [repo], [linkedInstallation(202)]));
    registry.store.set("device:secret-three", deviceRecord(tokenC, [repo], []));
    registry.store.set("github-admin:101:42", "1");
    // GitHub's real push webhook uses the lightweight installation shape;
    // immutable account identity comes from repository.owner.id.
    const payload = { ...pushEvent(repo), installation: { id: 101, node_id: "synthetic" } };

    const response = await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(response.status).toBe(200);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(sendApnsMock.mock.calls[0][1].token).toBe(tokenA);
    expect(registry.listCalls.every((call) => call.prefix === "route:github-app:101:")).toBe(true);
    expect(consoleInfoMock.mock.calls.at(-1)?.[1]).toMatchObject({
      matched: 1,
      accepted: 1,
      failed: 0,
      failureKinds: {},
    });
  });

  it("revalidates an uncached organization owner before routing an App push", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    registry.store.delete("github-admin:101:42");
    env.GITHUB_APP_PRIVATE_KEY_PKCS8 = await testGitHubAppPrivateKey();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 101,
        app_id: 123456,
        account: { id: 9001, login: "acme", type: "Organization" },
        repository_selection: "all",
        html_url: "https://github.com/organizations/acme/settings/installations/101",
        suspended_at: null,
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: "installation-token-for-tests" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, login: "octocat", type: "User" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        state: "active",
        role: "admin",
        organization: { id: 9001 },
        user: { id: 42 },
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const payload = { ...pushEvent(repo), installation: { id: 101 } };

    await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.get("github-admin:101:42")).toBe("1");
    expect(registry.putCalls.find((call) => call.key === "github-admin:101:42")?.options).toEqual({
      expirationTtl: 300,
    });
    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(JSON.parse(fetchMock.mock.calls[1][1]?.body as string)).toEqual({ permissions: { members: "read" } });
    expect(fetchMock.mock.calls[4][0]).toBe("https://api.github.com/installation/token");
    expect(fetchMock.mock.calls[4][1]?.method).toBe("DELETE");
  });

  it("fails closed and unlinks a device when organization-owner revalidation is definitively lost", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    registry.store.delete("github-admin:101:42");
    env.GITHUB_APP_PRIVATE_KEY_PKCS8 = await testGitHubAppPrivateKey();
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 101,
        app_id: 123456,
        account: { id: 9001, login: "acme", type: "Organization" },
        repository_selection: "all",
        html_url: "https://github.com/organizations/acme/settings/installations/101",
        suspended_at: null,
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: "installation-token-for-tests" }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, login: "octocat", type: "User" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        state: "active",
        role: "member",
        organization: { id: 9001 },
        user: { id: 42 },
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 })));
    const payload = { ...pushEvent(repo), installation: { id: 101 } };

    await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(sendApnsMock).not.toHaveBeenCalled();
    expect(JSON.parse(registry.store.get("device:secret-one")!).linkedInstallations).toEqual([]);
    expect(consoleInfoMock.mock.calls.at(-1)?.[1]).toMatchObject({ unauthorized: 1, accepted: 0 });
  });

  it("suppresses a delivery but preserves the link and route on transient GitHub failure", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    registry.store.delete("github-admin:101:42");
    env.GITHUB_APP_PRIVATE_KEY_PKCS8 = await testGitHubAppPrivateKey();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(new Response(null, { status: 503 })));
    const payload = { ...pushEvent(repo), installation: { id: 101 } };

    await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(sendApnsMock).not.toHaveBeenCalled();
    expect(JSON.parse(registry.store.get("device:secret-one")!).linkedInstallations).toHaveLength(1);
    expect(registry.store.has("route:github-app:101:secret-one")).toBe(true);
    expect(consoleInfoMock.mock.calls.at(-1)?.[1]).toMatchObject({ unauthorized: 0, accepted: 0 });
  });

  it("rejects an App push whose numeric installation owner does not match the linked installation", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    const payload = { ...pushEvent(repo, 2, "main", 1234), installation: { id: 101 } };

    await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(sendApnsMock).not.toHaveBeenCalled();
  });

  it("rejects malformed GitHub App installation metadata before routing", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo], [linkedInstallation(101)]));
    const { installation: _, ...malformed } = pushEvent(repo);

    await runWebhook(env, await signedWebhookRequest("push", malformed));

    expect(sendApnsMock).not.toHaveBeenCalled();
  });

  it("does not route an App push through a suspended installation", async () => {
    registry.store.set(
      "device:secret-one",
      deviceRecord(tokenA, [repo], [linkedInstallation(101, "suspended")]),
    );
    const payload = { ...pushEvent(repo), installation: { id: 101 } };

    await runWebhook(
      env,
      await signedWebhookRequest("push", payload, env.GITHUB_APP_WEBHOOK_SECRET),
    );

    expect(sendApnsMock).not.toHaveBeenCalled();
  });

  it("keeps the registration on other APNs errors (500)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(null, { status: 500 }));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(false);
  });

  it("keeps the registration when APNs delivery throws", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => Promise.reject(new Error("network down")));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200); // webhook already acked; a later event may retry
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(false);
  });

  it("coarsens delivery failures without retaining endpoint or token text", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => Promise.reject(new Error(
      `fetch https://api.push.apple.com/3/device/${tokenA} failed`,
    )));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    const summary = consoleInfoMock.mock.calls.at(-1)?.[1];
    const serialized = JSON.stringify(summary);
    expect(serialized).not.toContain(tokenA);
    expect(serialized).not.toContain(repo);
    expect(serialized).not.toContain("api.push.apple.com");
    expect(serialized).toContain("Error:Network");
  });

  it("leaves devices subscribed to other repos untouched", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, ["other/repo"]));
    registry.store.set("device:secret-two", deviceRecord(tokenB, [repo]));
    sendApnsMock.mockImplementation((_cfg, n) =>
      Promise.resolve(n.token === tokenB ? new Response(null, { status: 410 }) : apnsOk()),
    );

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(registry.deletedKeys).toEqual([
      "device:secret-two",
      "route:legacy:acme/app:secret-two",
      "route:github-app:101:secret-two",
    ]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(registry.store.has("device:secret-two")).toBe(false);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
  });

  it("still notifies a second subscriber when the first token is stale", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    registry.store.set("device:secret-two", deviceRecord(tokenB, [repo]));
    sendApnsMock.mockImplementation((_cfg, n) =>
      Promise.resolve(n.token === tokenA ? new Response(null, { status: 410 }) : apnsOk()),
    );

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(2);
    const notifiedTokens = sendApnsMock.mock.calls.map((call) => call[1].token);
    expect(notifiedTokens.sort()).toEqual([tokenA, tokenB].sort());
    expect(registry.deletedKeys).toEqual([
      "device:secret-one",
      "route:legacy:acme/app:secret-one",
      "route:github-app:101:secret-one",
    ]);
    expect(registry.store.has("device:secret-two")).toBe(true);
  });

  it("skips malformed or missing records without throwing", async () => {
    registry.store.set("device:secret-bad", "{not json");
    registry.store.set("device:secret-vanish", deviceRecord(tokenA, [repo]));
    registry.nullOnGet.add("device:secret-vanish");
    registry.store.set("device:secret-good", deviceRecord(tokenB, [repo]));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(sendApnsMock.mock.calls[0][1].token).toBe(tokenB);
    expect(registry.deletedKeys).toEqual(["route:github-app:101:secret-vanish"]);
  });
});

describe("github-webhook throttle skip", () => {
  const repo = "acme/app";
  const tokenA = "a".repeat(64);
  const tokenB = "b".repeat(64);
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk()); // default: delivery succeeds
    consoleInfoMock.mockClear();
  });

  it("writes a bounded branch-scoped throttle key with the configured ttl", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));

    const throttleKey = await notificationThrottleKey(repo, tokenA);
    expect(registry.store.get(throttleKey)).toBe("1");
    const put = registry.putCalls.filter((c) => c.key === throttleKey).at(-1);
    expect(put?.options).toEqual({ expirationTtl: 120 }); // NOTIFY_COLLAPSE_SECONDS from makeEnv
  });

  it("skips a device whose throttle key is already present", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(true);

    // Second push for the same repo and branch: the throttle key suppresses the resend.
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo, 3)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(consoleInfoMock.mock.calls.at(-1)?.[1]).toMatchObject({
      matched: 1,
      accepted: 0,
      throttled: 1,
      pruned: 0,
      rejected: 0,
      failed: 0,
    });
  });

  it("throttles per token: a newly added device still gets its first notification", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    // Webhook one: device one is notified.
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);

    // Webhook two: device one is throttled, still exactly one send total.
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);

    // Webhook three after adding a second subscriber to the same repo:
    // only the second device receives a notification.
    registry.store.set("device:secret-two", deviceRecord(tokenB, [repo]));
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(2);
    expect(sendApnsMock.mock.calls[0][1].token).toBe(tokenA);
    expect(sendApnsMock.mock.calls[1][1].token).toBe(tokenB);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA))).toBe(true);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenB))).toBe(true);
  });

  it("does not let a feature-branch push throttle the configured branch", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo, 1, "feature/notes")));
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo, 1, "main")));

    expect(sendApnsMock).toHaveBeenCalledTimes(2);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA, "feature/notes"))).toBe(true);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenA, "main"))).toBe(true);
  });
});

describe("github-webhook route-index pagination", () => {
  const repo = "acme/app";
  const tokenA = "a".repeat(64);
  const tokenB = "b".repeat(64);
  const tokenC = "c".repeat(64);
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk());
  });

  it("notifies subscribers across every KV page via the cursor loop", async () => {
    registry.store.set("device:secret-a", deviceRecord(tokenA, [repo]));
    registry.store.set("device:secret-b", deviceRecord(tokenB, [repo]));
    registry.store.set("device:secret-c", deviceRecord(tokenC, [repo]));
    // One key per page → the scan must follow two cursor round-trips.
    registry.pageSize = 1;

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);

    // All three indexed subscribers were found across three pages.
    expect(sendApnsMock).toHaveBeenCalledTimes(3);
    const notified = sendApnsMock.mock.calls.map((call) => call[1].token).sort();
    expect(notified).toEqual([tokenA, tokenB, tokenC].sort());
    // Every device got its throttle key.
    for (const token of [tokenA, tokenB, tokenC]) {
      expect(registry.store.has(await notificationThrottleKey(repo, token))).toBe(true);
    }
    // The route index actually paged: first call without a cursor, then two cursor follow-ups.
    expect(registry.listCalls.length).toBe(3);
    expect(registry.listCalls[0].cursor).toBeUndefined();
    expect(registry.listCalls[1].cursor).toBe("1");
    expect(registry.listCalls[2].cursor).toBe("2");
    expect(registry.listCalls.every((call) => call.prefix === "route:github-app:101:")).toBe(true);
  });

  it("recovers at the next webhook when a mid-scan prune shifts the cursor", async () => {
    registry.store.set("device:secret-a", deviceRecord(tokenA, [repo]));
    registry.store.set("device:secret-b", deviceRecord(tokenB, [repo]));
    registry.pageSize = 1;
    // Page one's device rejects with 410 and is pruned mid-route scan.
    sendApnsMock.mockImplementation((_cfg, n) =>
      Promise.resolve(n.token === tokenA ? new Response(null, { status: 410 }) : apnsOk()),
    );

    // Pass one: A is attempted + pruned. With index-shifted pagination (our
    // fake's semantics — real KV is eventually consistent, so production may
    // see either outcome), the deletion shifts the cursor past B for THIS pass.
    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.deletedKeys).toEqual([
      "device:secret-a",
      "route:legacy:acme/app:secret-a",
      "route:github-app:101:secret-a",
    ]);
    expect(registry.store.has("device:secret-b")).toBe(true);
    // No throttle key for B — it was never notified.
    expect(registry.store.has(await notificationThrottleKey(repo, tokenB))).toBe(false);

    // Pass two (next push to the repo): B is the only device left and gets
    // its notification — the miss was transient, not a lost subscription.
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo, 2)));
    expect(sendApnsMock).toHaveBeenCalledTimes(2);
    expect(sendApnsMock.mock.calls[1][1].token).toBe(tokenB);
    expect(registry.store.has(await notificationThrottleKey(repo, tokenB))).toBe(true);
    expect(registry.store.has("device:secret-b")).toBe(true);
  });
});

describe("/v1/register rate limit", () => {
  const ip = "203.0.113.7";
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk());
  });

  it("allows 20 registrations per IP, then 429s the 21st without writing a device key", async () => {
    const body = {
      token: "c".repeat(64),
      environment: "development",
      deviceSecret: "shared-secret-1",
      repos: ["acme/app"],
    };

    const results: Array<{ status: number; body: unknown }> = [];
    for (let i = 0; i < 20; i++) {
      const response = await runWebhook(env, registerRequest(body, { "cf-connecting-ip": ip }));
      results.push({ status: response.status, body: await response.json() });
    }
    expect(results.map((r) => r.status)).toEqual(new Array(20).fill(200));
    expect(results.map((r) => (r.body as { ok: boolean }).ok)).toEqual(new Array(20).fill(true));

    // The 21st identical request from the same IP is blocked — before any device write.
    const blocked = await runWebhook(
      env,
      registerRequest({ ...body, deviceSecret: "blocked-secret-1" }, { "cf-connecting-ip": ip }),
    );
    expect(blocked.status).toBe(429);
    expect(await blocked.json()).toEqual({ error: "rate limited" });
    expect(registry.store.has("device:blocked-secret-1")).toBe(false);

    // The counter for this IP sits at the limit, written with the one-hour TTL.
    const rateLimitKey = await opaqueRateLimitKey(env.GITHUB_APP_WEBHOOK_SECRET, "register", ip);
    expect(rateLimitKey).not.toContain(ip);
    expect(registry.store.get(rateLimitKey)).toBe("20");
    const rlPut = registry.putCalls.filter((c) => c.key === rateLimitKey).at(-1);
    expect(rlPut?.options).toEqual({ expirationTtl: 3600 });
  });

  it("tracks the counter per opaque IP bucket: a different address is unaffected", async () => {
    registry.store.set(
      await opaqueRateLimitKey(env.GITHUB_APP_WEBHOOK_SECRET, "register", ip),
      "20",
    ); // this IP is already at the limit
    const body = (deviceSecret: string) => ({
      token: "d".repeat(64),
      environment: "development",
      deviceSecret,
      repos: ["acme/app"],
    });

    const blocked = await runWebhook(env, registerRequest(body("blocked-secret-1"), { "cf-connecting-ip": ip }));
    expect(blocked.status).toBe(429);
    expect(await blocked.json()).toEqual({ error: "rate limited" });
    expect(registry.store.has("device:blocked-secret-1")).toBe(false);

    const other = await runWebhook(
      env,
      registerRequest(body("other-secret-1"), { "cf-connecting-ip": "198.51.100.9" }),
    );
    expect(other.status).toBe(200);
    expect(await other.json()).toEqual({ ok: true, repos: 1, installations: 0 });
    expect(registry.store.has("device:other-secret-1")).toBe(true);
  });
});

describe("/v1/register endpoint", () => {
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk());
  });

  it("stores the device record with normalized repo names and returns the count", async () => {
    const response = await runWebhook(
      env,
      registerRequest({
        token: "a".repeat(64),
        environment: "development",
        deviceSecret: "acme-secret-1",
        repos: ["Acme/App", "acme/app", "User/Other"],
      }),
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, repos: 2, installations: 0 }); // deduped after normalization

    const stored = JSON.parse(registry.store.get("device:acme-secret-1")!);
    expect(stored.repos).toEqual(["acme/app", "user/other"]);
    expect(stored.token).toBe("a".repeat(64));
    expect(stored.environment).toBe("development");
    expect(stored.linkedInstallations).toEqual([]);
    expect(registry.putCalls.map((call) => call.key).filter((key) => key.startsWith("route:"))).toEqual([]);
    expect(registry.deletedKeys).toEqual(expect.arrayContaining([
      "route:legacy:acme/app:acme-secret-1",
      "route:legacy:user/other:acme-secret-1",
    ]));
    const deviceExpiration = registry.putCalls.find(
      (call) => call.key === "device:acme-secret-1",
    )?.options?.expiration;
    expect(deviceExpiration).toBeGreaterThan(Math.floor(Date.now() / 1000) + 89 * 24 * 60 * 60);
  });

  it("preserves verified GitHub App links when an APNs token or repo inventory refreshes", async () => {
    registry.store.set(
      "device:acme-secret-1",
      deviceRecord("a".repeat(64), ["acme/old"], [linkedInstallation()]),
    );

    const response = await runWebhook(
      env,
      registerRequest({
        token: "b".repeat(64),
        environment: "production",
        deviceSecret: "acme-secret-1",
        repos: ["Acme/New"],
      }),
    );

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, repos: 1, installations: 1 });
    const stored = JSON.parse(registry.store.get("device:acme-secret-1")!);
    expect(stored.token).toBe("b".repeat(64));
    expect(stored.repos).toEqual(["acme/new"]);
    expect(stored.linkedInstallations).toEqual([linkedInstallation()]);
    expect(registry.deletedKeys).toEqual(expect.arrayContaining([
      "route:legacy:acme/old:acme-secret-1",
      "route:legacy:acme/new:acme-secret-1",
    ]));
    expect(registry.putCalls.map((call) => call.key)).toContain("route:github-app:101:acme-secret-1");
  });

  it("does not restore a deleted GitHub installation during registration repair", async () => {
    registry.store.set(
      "device:acme-secret-1",
      deviceRecord("a".repeat(64), ["acme/app"], [linkedInstallation(123)]),
    );
    registry.store.set("github-installation-deleted:123", "1");

    const response = await runWebhook(env, registerRequest({
      token: "a".repeat(64),
      environment: "development",
      deviceSecret: "acme-secret-1",
      repos: ["acme/app"],
    }));

    expect(response.status).toBe(200);
    expect(JSON.parse(registry.store.get("device:acme-secret-1")!).linkedInstallations).toEqual([]);
    expect(registry.deletedKeys).toContain("route:github-app:123:acme-secret-1");
  });

  it("rejects an invalid registration with 400 and stores no device key", async () => {
    const response = await runWebhook(
      env,
      registerRequest({
        token: "not-hex", // malformed token: parseRegisterRequest rejects
        environment: "development",
        deviceSecret: "acme-secret-1",
        repos: ["acme/app"],
      }),
    );
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid registration" });
    expect([...registry.store.keys()].filter((k) => k.startsWith("device:"))).toEqual([]);
  });

  it("rejects a JSON body over 16 KiB with 413", async () => {
    const response = await runWebhook(
      env,
      registerRequest({
        token: "a".repeat(64),
        environment: "development",
        deviceSecret: "acme-secret-1",
        repos: ["acme/app"],
        pad: "x".repeat(17 * 1024), // pushes the serialized body past 16 KiB
      }),
    );
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: "payload too large" });
    expect([...registry.store.keys()].filter((k) => k.startsWith("device:"))).toEqual([]);
  });
});

describe("/v1/unregister endpoint", () => {
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk());
  });

  it("deletes the device record for a valid deviceSecret", async () => {
    registry.store.set("device:secret-one", deviceRecord("a".repeat(64), ["acme/app"]));

    const response = await runWebhook(env, unregisterRequest({ deviceSecret: "secret-one" }));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(registry.deletedKeys).toEqual([
      "device:secret-one",
      "route:legacy:acme/app:secret-one",
      "route:github-app:101:secret-one",
    ]);
    expect(registry.store.has("device:secret-one")).toBe(false);
  });

  it("returns 400 for a malformed deviceSecret and leaves the key untouched", async () => {
    registry.store.set("device:secret-one", deviceRecord("a".repeat(64), ["acme/app"]));

    const response = await runWebhook(env, unregisterRequest({ deviceSecret: "bad secret!" }));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid deviceSecret" });
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
  });
});

describe("GitHub App connection endpoints", () => {
  const deviceSecret = "github-app-device-secret";
  const token = "e".repeat(64);
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(async () => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    env.GITHUB_APP_PRIVATE_KEY_PKCS8 = await testGitHubAppPrivateKey();
    registry.store.set("device:github-app-device-secret", deviceRecord(token, ["octo-org/vault"], []));
    consoleInfoMock.mockClear();
  });

  async function beginAndReachOAuth(installationID = 123): Promise<string> {
    const start = await runWebhook(
      env,
      githubAppPost("/v1/github-app/link/start", { deviceSecret }),
    );
    expect(start.status).toBe(200);
    const startBody = await start.json() as { state: string; url: string };
    expect(startBody.state).toMatch(/^[A-Za-z0-9_-]{32,128}$/);
    expect(startBody.url).toContain(`/apps/${env.GITHUB_APP_SLUG}/installations/new`);
    expect(startBody.url).not.toContain(deviceSecret);

    const setup = await runWebhook(
      env,
      new Request(
        `https://push.example.test/v1/github-app/setup?state=${startBody.state}&installation_id=${installationID}&setup_action=install`,
      ),
    );
    expect(setup.status).toBe(302);
    const authorizationURL = new URL(setup.headers.get("location")!);
    expect(authorizationURL.origin).toBe("https://github.com");
    expect(authorizationURL.pathname).toBe("/login/oauth/authorize");
    expect(authorizationURL.searchParams.get("state")).toBe(startBody.state);
    expect(authorizationURL.searchParams.get("code_challenge_method")).toBe("S256");
    expect(authorizationURL.searchParams.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(authorizationURL.toString()).not.toContain(deviceSecret);
    return startBody.state;
  }

  it("acknowledges direct GitHub access updates without trusting or linking a bare installation ID", async () => {
    const response = await runWebhook(
      env,
      new Request("https://push.example.test/v1/github-app/setup?installation_id=123&setup_action=update"),
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations).toEqual([]);
  });

  it("requires an existing APNs registration before starting", async () => {
    registry.store.delete(`device:${deviceSecret}`);
    const response = await runWebhook(
      env,
      githubAppPost("/v1/github-app/link/start", { deviceSecret }),
    );
    expect(response.status).toBe(409);
    expect(await response.json()).toEqual({ error: "push registration required" });
  });

  it("verifies user access, links the installation, returns status, and consumes state", async () => {
    const state = await beginAndReachOAuth();
    const userToken = "github-user-token-that-must-not-be-stored";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: userToken }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, login: "octocat", type: "User" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 123,
        app_id: 123456,
        account: { id: 9001, login: "octo-org", type: "Organization" },
        repository_selection: "all",
        html_url: "https://github.com/settings/installations/123",
        suspended_at: null,
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        state: "active",
        role: "admin",
        organization: { id: 9001, login: "octo-org", type: "Organization" },
        user: { id: 42, login: "octocat", type: "User" },
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    const callback = await runWebhook(
      env,
      new Request(`https://push.example.test/v1/github-app/oauth/callback?state=${state}&code=${"a".repeat(40)}`),
    );
    expect(callback.status).toBe(302);
    expect(callback.headers.get("location")).toBe(`syncmd://github-app?state=${state}&result=connected`);

    const stored = registry.store.get(`device:${deviceSecret}`)!;
    expect(stored).not.toContain(userToken);
    expect(stored).not.toContain("a".repeat(40));
    const linked = JSON.parse(stored).linkedInstallations[0];
    expect(linked).toMatchObject({
      ...linkedInstallation(123, "active", "octo-org"),
      connectedAt: expect.any(Number),
    });
    expect(registry.store.has(`github-link-state:${state}`)).toBe(false);
    expect(registry.store.get("route:github-app:123:github-app-device-secret")).toBe("1");
    expect(registry.putCalls.map((call) => call.key)).toContain(
      "route:github-app:123:github-app-device-secret",
    );
    expect(registry.store.get("github-admin:123:42")).toBe("1");
    expect(registry.putCalls.find((call) => call.key === "github-admin:123:42")?.options).toEqual({
      expirationTtl: 300,
    });
    expect(fetchMock.mock.calls[4][0]).toBe(
      `https://api.github.com/applications/${env.GITHUB_APP_CLIENT_ID}/token`,
    );
    expect(fetchMock.mock.calls[4][1]?.method).toBe("DELETE");

    const status = await runWebhook(
      env,
      githubAppPost("/v1/github-app/status", { deviceSecret }),
    );
    expect(status.status).toBe(200);
    expect(await status.json()).toEqual({
      ok: true,
      installations: [{
        id: linked.id,
        accountLogin: linked.accountLogin,
        accountType: linked.accountType,
        repositorySelection: linked.repositorySelection,
        htmlURL: linked.htmlURL,
        status: linked.status,
        connectedAt: linked.connectedAt,
      }],
    });

    const replay = await runWebhook(
      env,
      new Request(`https://push.example.test/v1/github-app/oauth/callback?state=${state}&code=${"b".repeat(40)}`),
    );
    expect(replay.status).toBe(400);
  });

  it("rejects a callback for an installation already tombstoned by a signed deletion", async () => {
    const state = await beginAndReachOAuth();
    registry.store.set("github-installation-deleted:123", "1");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const callback = await runWebhook(
      env,
      new Request(`https://push.example.test/v1/github-app/oauth/callback?state=${state}&code=${"a".repeat(40)}`),
    );

    expect(callback.status).toBe(302);
    expect(callback.headers.get("location")).toBe(
      `syncmd://github-app?state=${state}&result=error&error=verification_failed`,
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations).toEqual([]);
  });

  it("fails closed when the authorizing user is not an account owner", async () => {
    const state = await beginAndReachOAuth();
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "github-user-token-for-tests" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, login: "octocat", type: "User" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 123,
        app_id: 123456,
        account: { id: 9001, login: "octo-org", type: "Organization" },
        repository_selection: "all",
        html_url: "https://github.com/settings/installations/123",
        suspended_at: null,
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        state: "active",
        role: "member",
        organization: { id: 9001, login: "octo-org", type: "Organization" },
        user: { id: 42, login: "octocat", type: "User" },
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 })));

    const callback = await runWebhook(
      env,
      new Request(`https://push.example.test/v1/github-app/oauth/callback?state=${state}&code=${"a".repeat(40)}`),
    );

    expect(callback.status).toBe(302);
    expect(callback.headers.get("location")).toBe(
      `syncmd://github-app?state=${state}&result=error&error=account_owner_required`,
    );
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations).toEqual([]);
    expect(registry.store.has(`github-link-state:${state}`)).toBe(false);
  });

  it("unlinks one installation without deleting the APNs registration", async () => {
    registry.store.set(
      `device:${deviceSecret}`,
      deviceRecord(token, ["octo-org/vault"], [linkedInstallation(123), linkedInstallation(456)]),
    );
    const response = await runWebhook(
      env,
      githubAppPost("/v1/github-app/unlink", { deviceSecret, installationID: 123 }),
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, installations: 1 });
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations).toEqual([
      linkedInstallation(456),
    ]);
    expect(registry.deletedKeys).toContain(`route:github-app:123:${deviceSecret}`);
    expect(registry.store.has(`route:github-app:456:${deviceSecret}`)).toBe(true);
  });

  it("tracks suspension and removes a deleted installation from every linked device", async () => {
    registry.store.set(
      `device:${deviceSecret}`,
      deviceRecord(token, ["octo-org/vault"], [linkedInstallation(123)]),
    );
    const lifecycle = (action: string) => signedWebhookRequest(
      "installation",
      { action, installation: { id: 123 } },
      env.GITHUB_APP_WEBHOOK_SECRET,
    );

    await runWebhook(env, await lifecycle("suspend"));
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations[0].status).toBe("suspended");
    await runWebhook(env, await lifecycle("unsuspend"));
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations[0].status).toBe("active");
    await runWebhook(env, await lifecycle("deleted"));
    expect(JSON.parse(registry.store.get(`device:${deviceSecret}`)!).linkedInstallations).toEqual([]);
    expect(registry.store.get("github-installation-deleted:123")).toBe("1");
    expect(registry.putCalls.find(
      (call) => call.key === "github-installation-deleted:123",
    )?.options).toEqual({ expirationTtl: 90 * 24 * 60 * 60 });
    expect(registry.deletedKeys).toContain(`route:github-app:123:${deviceSecret}`);
  });
});

describe("router fallthrough and healthz", () => {
  let registry: FakeRegistry;
  let env: Env;

  beforeEach(() => {
    registry = new FakeRegistry();
    env = makeEnv(registry);
    sendApnsMock.mockReset();
    sendApnsMock.mockImplementation(() => apnsOk());
  });

  it("returns 404 for an unknown GET path", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/nope"));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not found" });
  });

  it("returns 404 for an unknown POST endpoint under /v1", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/v1/unknown", { method: "POST" }));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not found" });
  });

  it("returns 404 when the method does not match the route", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/v1/register")); // GET on a POST route
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not found" });
  });

  it("rejects an oversized webhook before signature or JSON processing", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/v1/github-webhook", {
      method: "POST",
      headers: { "content-length": String(10 * 1024 * 1024 + 1) },
      body: "{}",
    }));
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: "payload too large" });
  });

  it("rejects a webhook signed with the retired repository-hook secret", async () => {
    const response = await runWebhook(
      env,
      await signedWebhookRequest("push", pushEvent("acme/app"), LEGACY_WEBHOOK_SECRET),
    );
    expect(response.status).toBe(401);
    expect(sendApnsMock).not.toHaveBeenCalled();
  });

  it("answers GET /healthz", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/healthz"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      ok: true,
      githubAppConfigured: true,
    });
  });
});
