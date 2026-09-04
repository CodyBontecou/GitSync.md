import { sendApns, type ApnsConfig } from "./apns";

export interface Env {
  REGISTRY: KVNamespace;
  GITHUB_WEBHOOK_SECRET: string;
  APNS_KEY_P8: string;
  APNS_KEY_ID: string;
  APNS_TEAM_ID: string;
  APNS_TOPIC: string;
  NOTIFY_COLLAPSE_SECONDS: string;
  REGISTER_RATE_LIMIT_PER_HOUR: string;
}

interface DeviceRecord {
  token: string;
  environment: "development" | "production";
  repos: string[];
  updatedAt: number;
}

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

// ---------------------------------------------------------------------------
// Pure helpers (unit tested in webhook.test.ts)
// ---------------------------------------------------------------------------

export function timingSafeEqualHex(a: string, b: string): boolean {
  if (!/^[0-9a-f]*$/.test(a) || !/^[0-9a-f]*$/.test(b) || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function verifyGithubSignature(
  rawBody: ArrayBuffer,
  header: string | null,
  secret: string,
): Promise<boolean> {
  if (!header?.startsWith("sha256=")) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, rawBody));
  const hex = Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
  return timingSafeEqualHex(hex, header.slice("sha256=".length));
}

export const DEVICE_TOKEN_RE = /^[0-9a-f]{64}$/;
export const REPO_NAME_RE = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
export const DEVICE_SECRET_RE = /^[A-Za-z0-9-]{8,64}$/;

export function parseRegisterRequest(body: unknown): DeviceRecord | null {
  if (typeof body !== "object" || body === null) return null;
  const { token, environment, repos, deviceSecret } = body as Record<string, unknown>;
  if (typeof token !== "string" || !DEVICE_TOKEN_RE.test(token)) return null;
  if (environment !== "development" && environment !== "production") return null;
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret)) return null;
  if (!Array.isArray(repos) || repos.length > 200) return null;
  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const r of repos) {
    if (typeof r !== "string") return null;
    const name = r.toLowerCase();
    if (!REPO_NAME_RE.test(name)) return null;
    if (!seen.has(name)) {
      seen.add(name);
      normalized.push(name);
    }
  }
  return { token, environment, repos: normalized, updatedAt: Date.now() };
}

export interface PushEventSummary {
  repoFullName: string | null;
  commitCount: number;
  isDeletion: boolean;
}

export function summarizePushEvent(body: unknown): PushEventSummary {
  if (typeof body !== "object" || body === null) {
    return { repoFullName: null, commitCount: 0, isDeletion: false };
  }
  const { repository, commits, deleted } = body as Record<string, unknown>;
  const fullName =
    typeof repository === "object" && repository !== null
      ? (repository as Record<string, unknown>).full_name
      : null;
  return {
    repoFullName: typeof fullName === "string" ? fullName.toLowerCase() : null,
    commitCount: Array.isArray(commits) ? commits.length : 0,
    isDeletion: deleted === true,
  };
}

export function notificationText(repo: string, count: number): { title: string; body: string } {
  return {
    title: repo,
    body: count === 1 ? "1 new commit — tap to sync" : `${count} new commits — tap to sync`,
  };
}

// ---------------------------------------------------------------------------
// Request handlers
// ---------------------------------------------------------------------------

async function rateLimited(env: Env, ip: string): Promise<boolean> {
  const limit = parseInt(env.REGISTER_RATE_LIMIT_PER_HOUR || "20", 10);
  const key = `rl:${ip}`;
  const current = parseInt((await env.REGISTRY.get(key)) || "0", 10);
  if (current >= limit) return true;
  await env.REGISTRY.put(key, String(current + 1), { expirationTtl: 3600 });
  return false;
}

async function handleRegister(env: Env, request: Request): Promise<Response> {
  const raw = await request.arrayBuffer();
  if (raw.byteLength > 16 * 1024) return json(413, { error: "payload too large" });
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return json(400, { error: "invalid json" });
  }
  const deviceSecret = (parsed as Record<string, unknown> | null)?.deviceSecret;
  const record = parseRegisterRequest(parsed);
  if (!record || typeof deviceSecret !== "string") return json(400, { error: "invalid registration" });

  await env.REGISTRY.put(`device:${deviceSecret}`, JSON.stringify(record));
  return json(200, { ok: true, repos: record.repos.length });
}

async function handleUnregister(env: Env, request: Request): Promise<Response> {
  let parsed: unknown;
  try {
    parsed = await request.json();
  } catch {
    return json(400, { error: "invalid json" });
  }
  const deviceSecret = (parsed as Record<string, unknown> | null)?.deviceSecret;
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret)) {
    return json(400, { error: "invalid deviceSecret" });
  }
  await env.REGISTRY.delete(`device:${deviceSecret}`);
  return json(200, { ok: true });
}

async function handleGithubWebhook(env: Env, request: Request, ctx: ExecutionContext): Promise<Response> {
  const raw = await request.arrayBuffer();
  const valid = await verifyGithubSignature(raw, request.headers.get("x-hub-signature-256"), env.GITHUB_WEBHOOK_SECRET);
  if (!valid) return json(401, { error: "bad signature" });

  const event = request.headers.get("x-github-event");
  if (event === "ping") return json(200, { ok: true });
  if (event !== "push") return json(200, { ok: true, ignored: event });

  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return json(400, { error: "invalid json" });
  }
  const summary = summarizePushEvent(parsed);
  if (!summary.repoFullName || summary.isDeletion) return json(200, { ok: true });

  const collapseTtl = parseInt(env.NOTIFY_COLLAPSE_SECONDS || "120", 10);
  const apnsConfig: ApnsConfig = {
    keyP8: env.APNS_KEY_P8,
    keyId: env.APNS_KEY_ID,
    teamId: env.APNS_TEAM_ID,
    topic: env.APNS_TOPIC,
  };

  // Scan subscribed devices. Personal-scale by design (dozens, not millions).
  ctx.waitUntil(
    (async () => {
      let cursor: string | undefined;
      do {
        const page = await env.REGISTRY.list({ prefix: "device:", cursor });
        const devices = await Promise.all(
          page.keys
            .map((k) => k.name.slice("device:".length))
            .filter((s) => DEVICE_SECRET_RE.test(s))
            .map(async (secret) => {
              const raw = await env.REGISTRY.get(`device:${secret}`);
              if (!raw) return null;
              try {
                return { secret, record: JSON.parse(raw) as DeviceRecord };
              } catch {
                return null;
              }
            }),
        );
        for (const entry of devices) {
          if (!entry || !entry.record.repos.includes(summary.repoFullName!)) continue;
          const { secret, record: device } = entry;
          const throttleKey = `notif:${summary.repoFullName}:${device.token}`;
          if (await env.REGISTRY.get(throttleKey)) continue;
          const text = notificationText(summary.repoFullName!, summary.commitCount);
          try {
            const response = await sendApns(apnsConfig, {
              token: device.token,
              environment: device.environment,
              title: text.title,
              body: text.body,
              collapseId: `repo:${summary.repoFullName}`,
              userInfo: { repo: summary.repoFullName },
            });
            if (response.status === 410 || response.status === 400) {
              // Token no longer valid — drop the registration. Registrations live under
              // device:<secret> (see the KV schema in wrangler.toml), so prune that key;
              // the app re-registers via /v1/register on next launch. The throttle key
              // below is still written, which is harmless: the registration is gone, so
              // future scans never reach this token again.
              await env.REGISTRY.delete(`device:${secret}`);
            }
          } catch {
            // Per-device delivery failures are logged only; the webhook has already been acked.
          }
          await env.REGISTRY.put(throttleKey, "1", { expirationTtl: collapseTtl });
        }
        cursor = page.list_complete ? undefined : page.cursor;
      } while (cursor);
    })(),
  );

  return json(200, { ok: true });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/healthz") return json(200, { ok: true });

    if (request.method === "POST" && path === "/v1/register") {
      const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
      if (await rateLimited(env, ip)) return json(429, { error: "rate limited" });
      return handleRegister(env, request);
    }
    if (request.method === "POST" && path === "/v1/unregister") return handleUnregister(env, request);
    if (request.method === "POST" && path === "/v1/github-webhook") {
      return handleGithubWebhook(env, request, ctx);
    }

    return json(404, { error: "not found" });
  },
};
