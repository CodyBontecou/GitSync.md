import { beforeEach, describe, expect, it, vi } from "vitest";
import worker, {
  notificationText,
  parseRegisterRequest,
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

class FakeRegistry {
  store = new Map<string, string>();
  deletedKeys: string[] = [];
  /** Every put() call in order, with its options (for TTL assertions). */
  putCalls: Array<{ key: string; value: string; options?: { expirationTtl?: number } }> = [];
  /** Keys that list() reports but get() returns null for (simulates a delete race). */
  nullOnGet = new Set<string>();

  async get(key: string): Promise<string | null> {
    if (this.nullOnGet.has(key)) return null;
    return this.store.get(key) ?? null;
  }
  async put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void> {
    this.store.set(key, value);
    this.putCalls.push({ key, value, options });
  }
  async delete(key: string): Promise<void> {
    this.deletedKeys.push(key);
    this.store.delete(key);
  }
  async list(options: { prefix?: string }) {
    const names = [...this.store.keys()]
      .filter((k) => k.startsWith(options.prefix ?? ""))
      .sort();
    return { keys: names.map((name) => ({ name })), list_complete: true };
  }
}

const WEBHOOK_SECRET = "test-webhook-secret";
const apnsOk = () => new Response(null, { status: 200 });

function makeEnv(registry: FakeRegistry): Env {
  return {
    REGISTRY: registry as unknown as Env["REGISTRY"],
    GITHUB_WEBHOOK_SECRET: WEBHOOK_SECRET,
    APNS_KEY_P8: "test-key-p8",
    APNS_KEY_ID: "KEYID12345",
    APNS_TEAM_ID: "TEAMID12345",
    APNS_TOPIC: "com.example.app",
    NOTIFY_COLLAPSE_SECONDS: "120",
    REGISTER_RATE_LIMIT_PER_HOUR: "20",
  };
}

async function signedWebhookRequest(event: string, payload: unknown): Promise<Request> {
  const body = JSON.stringify(payload);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(WEBHOOK_SECRET),
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

function pushEvent(repo: string, commitCount = 2) {
  return {
    repository: { full_name: repo },
    commits: Array.from({ length: commitCount }, (_, i) => ({ message: `c${i}` })),
  };
}

function deviceRecord(token: string, repos: string[]): string {
  return JSON.stringify({ token, environment: "development", repos, updatedAt: 1 });
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
  it("rejects a malformed repo name", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "abc-123-def", repos: ["no slash"] })).toBeNull();
  });
  it("rejects a short device secret", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "short", repos: [] })).toBeNull();
  });
  it("rejects unknown environment", () => {
    expect(parseRegisterRequest({ token, environment: "staging", deviceSecret: "abc-123-def", repos: [] })).toBeNull();
  });
});

describe("summarizePushEvent", () => {
  it("extracts repo, commit count, and deletion flag", () => {
    const summary = summarizePushEvent({
      repository: { full_name: "CodyBontecou/travel" },
      commits: [{}, {}, {}],
    });
    expect(summary).toEqual({ repoFullName: "codybontecou/travel", commitCount: 3, isDeletion: false });
  });
  it("flags deletions", () => {
    const summary = summarizePushEvent({ repository: { full_name: "a/b" }, deleted: true, commits: [] });
    expect(summary.isDeletion).toBe(true);
  });
  it("survives malformed payloads", () => {
    expect(summarizePushEvent(null)).toEqual({ repoFullName: null, commitCount: 0, isDeletion: false });
  });
});

describe("notificationText", () => {
  it("singularizes one commit", () => {
    expect(notificationText("a/b", 1).body).toBe("1 new commit — tap to sync");
  });
  it("pluralizes multiple commits", () => {
    expect(notificationText("a/b", 3).body).toBe("3 new commits — tap to sync");
  });
});

describe("github-webhook device pruning", () => {
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
  });

  it("prunes device:<secret> when APNs rejects with 410 (unregistered)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(null, { status: 410 }));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);

    // The real registration key is deleted...
    expect(registry.deletedKeys).toEqual(["device:secret-one"]);
    expect(registry.store.has("device:secret-one")).toBe(false);
    // ...and the old garbage key (device:<repo>:<token>) is neither deleted nor written.
    expect(registry.deletedKeys).not.toContain(`device:${repo}:${tokenA}`);
    expect(registry.store.has(`device:${repo}:${tokenA}`)).toBe(false);
    // Throttle-key semantics unchanged: still written after pruning (harmless, see index.ts).
    expect(registry.store.has(`notif:${repo}:${tokenA}`)).toBe(true);
  });

  it("prunes device:<secret> when APNs rejects with 400 (bad token)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(null, { status: 400 }));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(registry.deletedKeys).toEqual(["device:secret-one"]);
    expect(registry.store.has("device:secret-one")).toBe(false);
  });

  it("keeps the registration when delivery succeeds (200)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200);
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has(`notif:${repo}:${tokenA}`)).toBe(true);
  });

  it("keeps the registration on other APNs errors (500)", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => new Response(null, { status: 500 }));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
  });

  it("keeps the registration when APNs delivery throws", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));
    sendApnsMock.mockImplementation(() => Promise.reject(new Error("network down")));

    const response = await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(response.status).toBe(200); // webhook already acked; failure logged only
    expect(registry.deletedKeys).toEqual([]);
    expect(registry.store.has("device:secret-one")).toBe(true);
  });

  it("leaves devices subscribed to other repos untouched", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, ["other/repo"]));
    registry.store.set("device:secret-two", deviceRecord(tokenB, [repo]));
    sendApnsMock.mockImplementation((_cfg, n) =>
      Promise.resolve(n.token === tokenB ? new Response(null, { status: 410 }) : apnsOk()),
    );

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(registry.deletedKeys).toEqual(["device:secret-two"]);
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
    expect(registry.deletedKeys).toEqual(["device:secret-one"]);
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
    expect(registry.deletedKeys).toEqual([]);
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
  });

  it("writes notif:<repo>:<token> with the NOTIFY_COLLAPSE_SECONDS ttl", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));

    const throttleKey = `notif:${repo}:${tokenA}`;
    expect(registry.store.get(throttleKey)).toBe("1");
    const put = registry.putCalls.filter((c) => c.key === throttleKey).at(-1);
    expect(put?.options).toEqual({ expirationTtl: 120 }); // NOTIFY_COLLAPSE_SECONDS from makeEnv
  });

  it("skips a device whose throttle key is already present", async () => {
    registry.store.set("device:secret-one", deviceRecord(tokenA, [repo]));

    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has(`notif:${repo}:${tokenA}`)).toBe(true);

    // Second push for the same repo: the throttle key suppresses the resend.
    await runWebhook(env, await signedWebhookRequest("push", pushEvent(repo, 3)));
    expect(sendApnsMock).toHaveBeenCalledTimes(1);
    expect(registry.store.has("device:secret-one")).toBe(true);
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
    expect(registry.store.has(`notif:${repo}:${tokenA}`)).toBe(true);
    expect(registry.store.has(`notif:${repo}:${tokenB}`)).toBe(true);
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
    expect(registry.store.get(`rl:${ip}`)).toBe("20");
    const rlPut = registry.putCalls.filter((c) => c.key === `rl:${ip}`).at(-1);
    expect(rlPut?.options).toEqual({ expirationTtl: 3600 });
  });

  it("tracks the counter per IP: a different address is unaffected", async () => {
    registry.store.set(`rl:${ip}`, "20"); // this IP is already at the limit
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
    expect(await other.json()).toEqual({ ok: true, repos: 1 });
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
    expect(await response.json()).toEqual({ ok: true, repos: 2 }); // deduped after normalization

    const stored = JSON.parse(registry.store.get("device:acme-secret-1")!);
    expect(stored.repos).toEqual(["acme/app", "user/other"]);
    expect(stored.token).toBe("a".repeat(64));
    expect(stored.environment).toBe("development");
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
    expect(registry.deletedKeys).toEqual(["device:secret-one"]);
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

  it("answers GET /healthz", async () => {
    const response = await runWebhook(env, new Request("https://push.example.test/healthz"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
  });
});
