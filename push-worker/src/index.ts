import { sendApns, type ApnsConfig } from "./apns";
import {
  exchangeGitHubOAuthCode,
  githubAppConfigurationIsPresent,
  installationIDFrom,
  parseLinkedInstallation,
  proveInstallationAdministrator,
  revalidateInstallationAdministrator,
  revokeGitHubUserToken,
  type GitHubAppEnv,
  type LinkedGitHubInstallation,
} from "./github-app";

export interface Env extends GitHubAppEnv {
  REGISTRY: KVNamespace;
  GITHUB_APP_WEBHOOK_SECRET: string;
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
  linkedInstallations: LinkedGitHubInstallation[];
}

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
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
export const GITHUB_LINK_STATE_RE = /^[A-Za-z0-9_-]{32,128}$/;
const MAX_LINKED_INSTALLATIONS = 50;
const GITHUB_LINK_TTL_SECONDS = 15 * 60;
const GITHUB_ADMIN_CACHE_TTL_SECONDS = 5 * 60;
const DEVICE_RETENTION_SECONDS = 90 * 24 * 60 * 60;
const MAX_WEBHOOK_BYTES = 10 * 1024 * 1024;

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
    const [owner, repository] = name.split("/");
    if ([owner, repository].some((segment) => segment === "." || segment === "..")) return null;
    if (!seen.has(name)) {
      seen.add(name);
      normalized.push(name);
    }
  }
  return {
    token,
    environment,
    repos: normalized,
    updatedAt: Date.now(),
    linkedInstallations: [],
  };
}

export function parseStoredDeviceRecord(raw: string): DeviceRecord | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const value = parsed as Record<string, unknown>;
  if (typeof value.token !== "string" || !DEVICE_TOKEN_RE.test(value.token)) return null;
  if (value.environment !== "development" && value.environment !== "production") return null;
  if (!Array.isArray(value.repos) || value.repos.length > 200) return null;
  const repos: string[] = [];
  const seenRepos = new Set<string>();
  for (const candidate of value.repos) {
    if (typeof candidate !== "string") return null;
    const repo = candidate.toLowerCase();
    if (!REPO_NAME_RE.test(repo)) return null;
    const segments = repo.split("/");
    if (segments.some((segment) => segment === "." || segment === "..")) return null;
    if (!seenRepos.has(repo)) {
      seenRepos.add(repo);
      repos.push(repo);
    }
  }
  const installations: LinkedGitHubInstallation[] = [];
  const seenInstallations = new Set<number>();
  const candidates = value.linkedInstallations === undefined ? [] : value.linkedInstallations;
  if (!Array.isArray(candidates) || candidates.length > MAX_LINKED_INSTALLATIONS) return null;
  for (const candidate of candidates) {
    const installation = parseLinkedInstallation(candidate);
    if (!installation) return null;
    if (!seenInstallations.has(installation.id)) {
      seenInstallations.add(installation.id);
      installations.push(installation);
    }
  }
  return {
    token: value.token,
    environment: value.environment,
    repos,
    updatedAt: typeof value.updatedAt === "number" && Number.isFinite(value.updatedAt)
      ? value.updatedAt
      : 0,
    linkedInstallations: installations,
  };
}

export interface PushEventSummary {
  repoFullName: string | null;
  branch: string | null;
  headSHA: string | null;
  commitCount: number;
  isDeletion: boolean;
  installationID: number | null;
}

export function summarizePushEvent(body: unknown): PushEventSummary {
  if (typeof body !== "object" || body === null) {
    return {
      repoFullName: null,
      branch: null,
      headSHA: null,
      commitCount: 0,
      isDeletion: false,
      installationID: null,
    };
  }
  const { repository, ref, after, commits, deleted, installation } = body as Record<string, unknown>;
  const fullName =
    typeof repository === "object" && repository !== null
      ? (repository as Record<string, unknown>).full_name
      : null;
  const normalizedFullName = typeof fullName === "string" ? fullName.toLowerCase() : null;
  const validFullName = normalizedFullName && REPO_NAME_RE.test(normalizedFullName)
    && normalizedFullName.split("/").every((segment) => segment !== "." && segment !== "..")
    ? normalizedFullName
    : null;
  const candidateBranch = typeof ref === "string" && ref.startsWith("refs/heads/")
    ? ref.slice("refs/heads/".length)
    : null;
  const branch = candidateBranch
    && new TextEncoder().encode(candidateBranch).length <= 255
    && !/[\u0000-\u001f\u007f]/.test(candidateBranch)
    ? candidateBranch
    : null;
  const headSHA = typeof after === "string" && /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i.test(after)
    ? after.toLowerCase()
    : null;
  return {
    repoFullName: validFullName,
    branch,
    headSHA,
    commitCount: Array.isArray(commits) ? commits.length : 0,
    isDeletion: deleted === true,
    installationID: typeof installation === "object" && installation !== null
      ? installationIDFrom((installation as Record<string, unknown>).id)
      : null,
  };
}

export function notificationText(repo: string, count: number): { title: string; body: string } {
  return {
    title: repo,
    body: count === 1
      ? "1 new commit — sync requested; tap to check"
      : `${count} new commits — sync requested; tap to check`,
  };
}

function githubAppRoutePrefix(installationID: number): string {
  return `route:github-app:${installationID}:`;
}

function retiredRepositoryRouteKey(repo: string, deviceSecret: string): string {
  return `route:legacy:${repo.toLowerCase()}:${deviceSecret}`;
}

function githubAppRouteKey(installationID: number, deviceSecret: string): string {
  return `${githubAppRoutePrefix(installationID)}${deviceSecret}`;
}

/** Stable ASCII collapse ID bounded to APNs' 64-byte header limit. */
export async function collapseID(repo: string, branch: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`${repo}\u0000${branch}`),
  ));
  const hex = Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `repo:${hex.slice(0, 59)}`;
}

interface GitHubLinkStateRecord {
  deviceSecret: string;
  stage: "install" | "oauth";
  expiresAt: number;
  installationID?: number;
  codeVerifier?: string;
}

function randomURLSafe(byteCount: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteCount));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function parseLinkState(raw: string): GitHubLinkStateRecord | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const value = parsed as Record<string, unknown>;
  if (typeof value.deviceSecret !== "string" || !DEVICE_SECRET_RE.test(value.deviceSecret)) return null;
  if (value.stage !== "install" && value.stage !== "oauth") return null;
  if (typeof value.expiresAt !== "number" || !Number.isFinite(value.expiresAt)) return null;
  const result: GitHubLinkStateRecord = {
    deviceSecret: value.deviceSecret,
    stage: value.stage,
    expiresAt: value.expiresAt,
  };
  if (value.installationID !== undefined) {
    const installationID = installationIDFrom(value.installationID);
    if (installationID === null) return null;
    result.installationID = installationID;
  }
  if (value.codeVerifier !== undefined) {
    if (typeof value.codeVerifier !== "string" || !/^[A-Za-z0-9_-]{43,128}$/.test(value.codeVerifier)) {
      return null;
    }
    result.codeVerifier = value.codeVerifier;
  }
  return result;
}

function singleQueryValue(url: URL, name: string): string | null {
  const values = url.searchParams.getAll(name);
  return values.length === 1 && values[0] ? values[0] : null;
}

function noStoreRedirect(location: string): Response {
  return new Response(null, {
    status: 302,
    headers: {
      location,
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
    },
  });
}

function noStoreHTML(message: string): Response {
  const escaped = message
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
  return new Response(
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>GitSync.md</title><p>${escaped}</p>`,
    {
      status: 200,
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "no-store",
        "content-security-policy": "default-src 'none'; base-uri 'none'; form-action 'none'",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
      },
    },
  );
}

export function githubAdministratorCacheKey(installation: LinkedGitHubInstallation): string {
  return `github-admin:${installation.id}:${installation.authorizingUserID}`;
}

function githubInstallationTombstoneKey(installationID: number): string {
  return `github-installation-deleted:${installationID}`;
}

function boundedFailureKind(error: unknown): string {
  if (!(error instanceof Error)) return "UnknownError";
  const name = /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(error.name) ? error.name : "Error";
  // Never retain arbitrary provider/KV error text: it can echo a URL, token,
  // route key, repository, or device secret. Coarse mechanics are sufficient.
  const message = error.message.toLowerCase();
  if (message.includes("timeout") || message.includes("timed out")) return `${name}:Timeout`;
  if (message.includes("network") || message.includes("fetch")) return `${name}:Network`;
  if (message.includes("crypto") || message.includes("key")) return `${name}:Crypto`;
  return `${name}:Other`;
}

function appCallbackURL(state: string, result: "connected" | "error", error?: string): string {
  const callback = new URL("syncmd://github-app");
  callback.searchParams.set("state", state);
  callback.searchParams.set("result", result);
  if (error) callback.searchParams.set("error", error);
  return callback.toString();
}

async function requestJSON(request: Request, maximumBytes = 4 * 1024): Promise<unknown | null> {
  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) return null;
  const raw = await request.arrayBuffer();
  if (raw.byteLength > maximumBytes) return null;
  try {
    return JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return null;
  }
}

async function codeChallenge(verifier: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  let binary = "";
  for (const byte of digest) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ---------------------------------------------------------------------------
// Request handlers
// ---------------------------------------------------------------------------

export async function opaqueRateLimitKey(secret: string, scope: string, ip: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const digest = new Uint8Array(await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`${scope}\u0000${ip}`),
  ));
  const hex = Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `rl:${scope}:${hex}`;
}

async function rateLimited(env: Env, ip: string, scope = "register"): Promise<boolean> {
  const limit = parseInt(env.REGISTER_RATE_LIMIT_PER_HOUR || "20", 10);
  const key = await opaqueRateLimitKey(env.GITHUB_APP_WEBHOOK_SECRET, scope, ip);
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

  const existingRaw = await env.REGISTRY.get(`device:${deviceSecret}`);
  const existing = existingRaw ? parseStoredDeviceRecord(existingRaw) : null;
  if (existing) {
    const retained = await Promise.all(existing.linkedInstallations.map(async (installation) =>
      await env.REGISTRY.get(githubInstallationTombstoneKey(installation.id)) ? null : installation,
    ));
    record.linkedInstallations = retained.filter(
      (installation): installation is LinkedGitHubInstallation => installation !== null,
    );
  }
  await putDeviceRecord(env, deviceSecret, record);
  await reconcileDeviceIndexes(env, deviceSecret, existing, record);
  return json(200, {
    ok: true,
    repos: record.repos.length,
    installations: record.linkedInstallations.length,
  });
}

async function handleUnregister(env: Env, request: Request): Promise<Response> {
  const parsed = await requestJSON(request);
  const deviceSecret = (parsed as Record<string, unknown> | null)?.deviceSecret;
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret)) {
    return json(400, { error: "invalid deviceSecret" });
  }
  const raw = await env.REGISTRY.get(`device:${deviceSecret}`);
  const device = raw ? parseStoredDeviceRecord(raw) : null;
  if (device) {
    await deleteDevice(env, deviceSecret, device);
  } else {
    await env.REGISTRY.delete(`device:${deviceSecret}`);
  }
  return json(200, { ok: true });
}

function githubAppUnavailable(env: Env): Response | null {
  return githubAppConfigurationIsPresent(env)
    ? null
    : json(503, { error: "github app unavailable" });
}

async function handleGitHubAppLinkStart(env: Env, request: Request): Promise<Response> {
  const unavailable = githubAppUnavailable(env);
  if (unavailable) return unavailable;
  const parsed = await requestJSON(request);
  const deviceSecret = (parsed as Record<string, unknown> | null)?.deviceSecret;
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret)) {
    return json(400, { error: "invalid deviceSecret" });
  }
  const deviceRaw = await env.REGISTRY.get(`device:${deviceSecret}`);
  if (!deviceRaw || !parseStoredDeviceRecord(deviceRaw)) {
    return json(409, { error: "push registration required" });
  }

  const state = randomURLSafe(32);
  const linkState: GitHubLinkStateRecord = {
    deviceSecret,
    stage: "install",
    expiresAt: Date.now() + GITHUB_LINK_TTL_SECONDS * 1000,
  };
  await env.REGISTRY.put(`github-link-state:${state}`, JSON.stringify(linkState), {
    expirationTtl: GITHUB_LINK_TTL_SECONDS,
  });
  const installationURL = new URL(`https://github.com/apps/${env.GITHUB_APP_SLUG}/installations/new`);
  installationURL.searchParams.set("state", state);
  return json(200, { ok: true, state, url: installationURL.toString() });
}

async function handleGitHubAppSetup(env: Env, request: Request): Promise<Response> {
  const unavailable = githubAppUnavailable(env);
  if (unavailable) return unavailable;
  const url = new URL(request.url);
  const state = singleQueryValue(url, "state");
  const installationID = installationIDFrom(singleQueryValue(url, "installation_id"));
  if (!state && installationID !== null) {
    // GitHub can redirect here after someone manages an installation directly,
    // outside a device-bound link flow. Never trust or bind that bare ID.
    return noStoreHTML("GitHub access was updated. Return to GitSync.md to refresh Push Sync.");
  }
  if (!state || !GITHUB_LINK_STATE_RE.test(state) || installationID === null) {
    return json(400, { error: "invalid setup callback" });
  }
  const stateRaw = await env.REGISTRY.get(`github-link-state:${state}`);
  const linkState = stateRaw ? parseLinkState(stateRaw) : null;
  if (!linkState || linkState.stage !== "install" || linkState.expiresAt < Date.now()) {
    return json(400, { error: "expired setup callback" });
  }
  const deviceRaw = await env.REGISTRY.get(`device:${linkState.deviceSecret}`);
  if (!deviceRaw || !parseStoredDeviceRecord(deviceRaw)) {
    await env.REGISTRY.delete(`github-link-state:${state}`);
    return json(409, { error: "push registration required" });
  }

  const codeVerifier = randomURLSafe(64);
  const oauthState: GitHubLinkStateRecord = {
    ...linkState,
    stage: "oauth",
    installationID,
    codeVerifier,
  };
  await env.REGISTRY.put(`github-link-state:${state}`, JSON.stringify(oauthState), {
    expirationTtl: GITHUB_LINK_TTL_SECONDS,
  });
  const authorizationURL = new URL("https://github.com/login/oauth/authorize");
  authorizationURL.searchParams.set("client_id", env.GITHUB_APP_CLIENT_ID);
  authorizationURL.searchParams.set("redirect_uri", env.GITHUB_APP_CALLBACK_URL);
  authorizationURL.searchParams.set("state", state);
  authorizationURL.searchParams.set("code_challenge", await codeChallenge(codeVerifier));
  authorizationURL.searchParams.set("code_challenge_method", "S256");
  authorizationURL.searchParams.set("prompt", "select_account");
  return noStoreRedirect(authorizationURL.toString());
}

async function handleGitHubAppOAuthCallback(env: Env, request: Request): Promise<Response> {
  const unavailable = githubAppUnavailable(env);
  if (unavailable) return unavailable;
  const url = new URL(request.url);
  const state = singleQueryValue(url, "state");
  if (!state || !GITHUB_LINK_STATE_RE.test(state)) {
    return json(400, { error: "invalid authorization callback" });
  }
  const stateRaw = await env.REGISTRY.get(`github-link-state:${state}`);
  const linkState = stateRaw ? parseLinkState(stateRaw) : null;
  if (!linkState || linkState.stage !== "oauth" || linkState.expiresAt < Date.now()
      || linkState.installationID === undefined || !linkState.codeVerifier) {
    return json(400, { error: "expired authorization callback" });
  }
  // Make the state single-use before any external request. OAuth codes are also
  // one-time, but deleting first closes replay even if GitHub is unavailable.
  await env.REGISTRY.delete(`github-link-state:${state}`);

  const oauthError = singleQueryValue(url, "error");
  const code = singleQueryValue(url, "code");
  if (oauthError || !code) {
    return noStoreRedirect(appCallbackURL(state, "error", oauthError === "access_denied" ? "cancelled" : "authorization_failed"));
  }

  try {
    if (await env.REGISTRY.get(githubInstallationTombstoneKey(linkState.installationID))) {
      throw new Error("GitHubInstallationDeleted");
    }
    const userToken = await exchangeGitHubOAuthCode(env, code, linkState.codeVerifier);
    let installation: LinkedGitHubInstallation;
    try {
      installation = await proveInstallationAdministrator(env, linkState.installationID, userToken);
    } finally {
      try {
        await revokeGitHubUserToken(env, userToken);
      } catch {
        // The token is never stored and expires; revocation is defense in depth.
        console.info("GitHub App user token revocation failed");
      }
    }
    const deviceRaw = await env.REGISTRY.get(`device:${linkState.deviceSecret}`);
    const device = deviceRaw ? parseStoredDeviceRecord(deviceRaw) : null;
    if (!device) throw new Error("PushRegistrationMissing");
    const existingIndex = device.linkedInstallations.findIndex((item) => item.id === installation.id);
    if (existingIndex >= 0) {
      device.linkedInstallations[existingIndex] = {
        ...installation,
        connectedAt: device.linkedInstallations[existingIndex].connectedAt,
      };
    } else {
      if (device.linkedInstallations.length >= MAX_LINKED_INSTALLATIONS) {
        throw new Error("GitHubInstallationLimit");
      }
      device.linkedInstallations.push(installation);
    }
    await putDeviceRecord(env, linkState.deviceSecret, device);
    await env.REGISTRY.put(githubAppRouteKey(installation.id, linkState.deviceSecret), "1", {
      expiration: deviceExpiration(device),
    });
    if (await env.REGISTRY.get(githubInstallationTombstoneKey(installation.id))) {
      device.linkedInstallations = device.linkedInstallations.filter((item) => item.id !== installation.id);
      await putDeviceRecord(env, linkState.deviceSecret, device);
      await env.REGISTRY.delete(githubAppRouteKey(installation.id, linkState.deviceSecret));
      throw new Error("GitHubInstallationDeleted");
    }
    try {
      await env.REGISTRY.put(githubAdministratorCacheKey(installation), "1", {
        expirationTtl: GITHUB_ADMIN_CACHE_TTL_SECONDS,
      });
    } catch {
      // Linking remains valid; the first App delivery will revalidate.
      console.info("GitHub App owner-proof cache write failed");
    }
    return noStoreRedirect(appCallbackURL(state, "connected"));
  } catch (error) {
    const kind = error instanceof Error ? error.message.split(":", 1)[0].slice(0, 80) : "UnknownError";
    console.info("GitHub App connection failed", { kind });
    const reason = kind === "GitHubInstallationAdministratorRequired"
      ? "account_owner_required"
      : "verification_failed";
    return noStoreRedirect(appCallbackURL(state, "error", reason));
  }
}

async function handleGitHubAppStatus(env: Env, request: Request): Promise<Response> {
  const parsed = await requestJSON(request);
  const deviceSecret = (parsed as Record<string, unknown> | null)?.deviceSecret;
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret)) {
    return json(400, { error: "invalid deviceSecret" });
  }
  const raw = await env.REGISTRY.get(`device:${deviceSecret}`);
  const device = raw ? parseStoredDeviceRecord(raw) : null;
  if (!device) return json(404, { error: "registration not found" });
  const retained = await Promise.all(device.linkedInstallations.map(async (installation) =>
    await env.REGISTRY.get(githubInstallationTombstoneKey(installation.id)) ? null : installation,
  ));
  const linkedInstallations = retained.filter(
    (installation): installation is LinkedGitHubInstallation => installation !== null,
  );
  if (linkedInstallations.length !== device.linkedInstallations.length) {
    const removed = device.linkedInstallations.filter(
      (installation) => !linkedInstallations.some((retainedInstallation) => retainedInstallation.id === installation.id),
    );
    device.linkedInstallations = linkedInstallations;
    await putDeviceRecord(env, deviceSecret, device);
    await Promise.all(removed.map((installation) =>
      env.REGISTRY.delete(githubAppRouteKey(installation.id, deviceSecret)),
    ));
  }
  const installations = linkedInstallations.map((installation) => ({
    id: installation.id,
    accountLogin: installation.accountLogin,
    accountType: installation.accountType,
    repositorySelection: installation.repositorySelection,
    htmlURL: installation.htmlURL,
    status: installation.status,
    connectedAt: installation.connectedAt,
  }));
  return json(200, { ok: true, installations });
}

async function handleGitHubAppUnlink(env: Env, request: Request): Promise<Response> {
  const parsed = await requestJSON(request);
  const value = parsed as Record<string, unknown> | null;
  const deviceSecret = value?.deviceSecret;
  const installationID = installationIDFrom(value?.installationID);
  if (typeof deviceSecret !== "string" || !DEVICE_SECRET_RE.test(deviceSecret) || installationID === null) {
    return json(400, { error: "invalid unlink request" });
  }
  const raw = await env.REGISTRY.get(`device:${deviceSecret}`);
  const device = raw ? parseStoredDeviceRecord(raw) : null;
  if (!device) return json(404, { error: "registration not found" });
  device.linkedInstallations = device.linkedInstallations.filter((installation) => installation.id !== installationID);
  await putDeviceRecord(env, deviceSecret, device);
  await env.REGISTRY.delete(githubAppRouteKey(installationID, deviceSecret));
  return json(200, { ok: true, installations: device.linkedInstallations.length });
}

async function apnsRejectionReason(response: Response): Promise<string | null> {
  try {
    const body = await response.json() as { reason?: unknown };
    return typeof body.reason === "string" && /^[A-Za-z][A-Za-z0-9]{0,63}$/.test(body.reason)
      ? body.reason
      : null;
  } catch {
    return null;
  }
}

export function shouldPruneDevice(status: number, reason: string | null): boolean {
  if (status === 410) return true;
  return status === 400 && (reason === "BadDeviceToken" || reason === "DeviceTokenNotForTopic");
}

async function webhookSource(env: Env, raw: ArrayBuffer, signature: string | null): Promise<"github-app" | null> {
  return env.GITHUB_APP_WEBHOOK_SECRET
      && await verifyGithubSignature(raw, signature, env.GITHUB_APP_WEBHOOK_SECRET)
    ? "github-app"
    : null;
}

function deviceExpiration(record: DeviceRecord): number {
  const now = Math.floor(Date.now() / 1000);
  const registered = Math.min(now, Math.max(0, Math.floor(record.updatedAt / 1000)));
  return Math.max(now + 120, registered + DEVICE_RETENTION_SECONDS);
}

async function putDeviceRecord(env: Env, secret: string, record: DeviceRecord): Promise<void> {
  await env.REGISTRY.put(`device:${secret}`, JSON.stringify(record), {
    expiration: deviceExpiration(record),
  });
}

async function reconcileDeviceIndexes(
  env: Env,
  deviceSecret: string,
  previous: DeviceRecord | null,
  next: DeviceRecord | null,
): Promise<void> {
  const retiredRepositoryRoutes = new Set([...(previous?.repos ?? []), ...(next?.repos ?? [])]);
  const previousInstallations = new Set(previous?.linkedInstallations.map((item) => item.id) ?? []);
  const nextInstallations = new Set(next?.linkedInstallations.map((item) => item.id) ?? []);
  const operations: Promise<void>[] = [];
  const expiration = next ? deviceExpiration(next) : undefined;

  // Registration and unlink requests also clean indexes left by pre-GitHub-App
  // relay versions; no new repository-scoped route is ever written.
  for (const repo of retiredRepositoryRoutes) {
    operations.push(env.REGISTRY.delete(retiredRepositoryRouteKey(repo, deviceSecret)));
  }
  for (const installationID of previousInstallations) {
    if (!nextInstallations.has(installationID)) {
      operations.push(env.REGISTRY.delete(githubAppRouteKey(installationID, deviceSecret)));
    }
  }
  for (const installationID of nextInstallations) {
    operations.push(env.REGISTRY.put(githubAppRouteKey(installationID, deviceSecret), "1", { expiration }));
  }
  await Promise.all(operations);
}

async function deleteDevice(env: Env, secret: string, device: DeviceRecord): Promise<void> {
  await env.REGISTRY.delete(`device:${secret}`);
  await reconcileDeviceIndexes(env, secret, device, null);
}

async function scanRoutedDevices(
  env: Env,
  prefix: string,
  visit: (secret: string, record: DeviceRecord) => Promise<void>,
): Promise<void> {
  let cursor: string | undefined;
  do {
    const page = await env.REGISTRY.list({ prefix, cursor });
    const entries = await Promise.all(page.keys.map(async (key) => {
      const secret = key.name.slice(prefix.length);
      if (!DEVICE_SECRET_RE.test(secret)) {
        await env.REGISTRY.delete(key.name);
        return null;
      }
      const raw = await env.REGISTRY.get(`device:${secret}`);
      const record = raw ? parseStoredDeviceRecord(raw) : null;
      if (!record) {
        await env.REGISTRY.delete(key.name);
        return null;
      }
      return { secret, record };
    }));
    const validEntries = entries.filter((entry): entry is NonNullable<typeof entry> => entry !== null);
    for (let offset = 0; offset < validEntries.length; offset += 10) {
      await Promise.all(validEntries.slice(offset, offset + 10).map(
        (entry) => visit(entry.secret, entry.record),
      ));
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
}

async function updateInstallationLifecycle(
  env: Env,
  installationID: number,
  action: "deleted" | "suspend" | "unsuspend",
): Promise<void> {
  let changedDevices = 0;
  if (action === "deleted") {
    await env.REGISTRY.put(githubInstallationTombstoneKey(installationID), "1", {
      expirationTtl: DEVICE_RETENTION_SECONDS,
    });
  }
  await scanRoutedDevices(env, githubAppRoutePrefix(installationID), async (secret, device) => {
    const index = device.linkedInstallations.findIndex((installation) => installation.id === installationID);
    if (index < 0) return;
    if (action === "deleted") {
      device.linkedInstallations.splice(index, 1);
    } else {
      device.linkedInstallations[index].status = action === "suspend" ? "suspended" : "active";
    }
    await putDeviceRecord(env, secret, device);
    if (action === "deleted") {
      await env.REGISTRY.delete(githubAppRouteKey(installationID, secret));
    }
    changedDevices += 1;
  });
  console.info("GitHub App installation lifecycle", { action, changedDevices });
}

async function handleGithubWebhook(env: Env, request: Request, ctx: ExecutionContext): Promise<Response> {
  const contentLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > MAX_WEBHOOK_BYTES) {
    return json(413, { error: "payload too large" });
  }
  const raw = await request.arrayBuffer();
  if (raw.byteLength > MAX_WEBHOOK_BYTES) return json(413, { error: "payload too large" });
  const source = await webhookSource(env, raw, request.headers.get("x-hub-signature-256"));
  if (!source) return json(401, { error: "bad signature" });

  const event = request.headers.get("x-github-event");
  if (event === "ping") return json(200, { ok: true });

  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return json(400, { error: "invalid json" });
  }
  if (event === "installation") {
    const value = parsed as Record<string, unknown>;
    const action = value?.action;
    const installationID = typeof value?.installation === "object" && value.installation !== null
      ? installationIDFrom((value.installation as Record<string, unknown>).id)
      : null;
    if (installationID !== null && (action === "deleted" || action === "suspend" || action === "unsuspend")) {
      ctx.waitUntil(updateInstallationLifecycle(env, installationID, action));
    }
    return json(200, { ok: true });
  }
  if (event !== "push") return json(200, { ok: true, ignored: event });

  const summary = summarizePushEvent(parsed);
  const repositoryValue = typeof parsed === "object" && parsed !== null
    ? (parsed as Record<string, unknown>).repository
    : null;
  const repositoryOwner = typeof repositoryValue === "object" && repositoryValue !== null
    ? (repositoryValue as Record<string, unknown>).owner
    : null;
  const repositoryOwnerID = typeof repositoryOwner === "object" && repositoryOwner !== null
    ? installationIDFrom((repositoryOwner as Record<string, unknown>).id)
    : null;
  // Real push payloads use GitHub's lightweight installation shape (id and
  // node_id only). Bind the installation to the immutable repository-owner ID,
  // which is the installation account for repositories it can access.
  // Reject malformed App metadata before routing.
  if (summary.installationID === null || repositoryOwnerID === null) {
    return json(200, { ok: true });
  }
  // Tag pushes and malformed/deleted branch refs cannot match a configured
  // checkout, so do not spend an APNs wake on them.
  if (!summary.repoFullName || !summary.branch || summary.isDeletion) {
    return json(200, { ok: true });
  }

  const deliveryHeader = request.headers.get("x-github-delivery");
  const hintID = deliveryHeader && /^[A-Za-z0-9._:-]{1,128}$/.test(deliveryHeader)
    ? deliveryHeader
    : summary.headSHA ?? crypto.randomUUID();
  const collapseTtl = parseInt(env.NOTIFY_COLLAPSE_SECONDS || "120", 10);
  const apnsConfig: ApnsConfig = {
    keyP8: env.APNS_KEY_P8,
    keyId: env.APNS_KEY_ID,
    teamId: env.APNS_TEAM_ID,
    topic: env.APNS_TOPIC,
  };
  const apnsCollapseID = await collapseID(summary.repoFullName, summary.branch);
  const deliveryRoutePrefix = githubAppRoutePrefix(summary.installationID);

  // Route only through the repository or installation index; each loaded
  // device record is still revalidated before APNs delivery.
  ctx.waitUntil(
    (async () => {
      const deliverySummary = {
        matched: 0,
        accepted: 0,
        throttled: 0,
        unauthorized: 0,
        pruned: 0,
        rejected: 0,
        failed: 0,
      };
      const failureKinds: Record<string, number> = {};
      interface AdministratorProof {
        result: "authorized" | "unauthorized" | "unavailable";
        refreshed?: LinkedGitHubInstallation;
      }
      const administratorProofs = new Map<string, Promise<AdministratorProof>>();
      try {
        await scanRoutedDevices(env, deliveryRoutePrefix, async (secret, device) => {
          if (!device.repos.includes(summary.repoFullName!)) return;
          const linked = device.linkedInstallations.find(
            (installation) => installation.id === summary.installationID
              && installation.accountID === repositoryOwnerID
              && installation.status === "active",
          );
          if (!linked) return;
          const proofKey = githubAdministratorCacheKey(linked);
          let proof = administratorProofs.get(proofKey);
          if (!proof) {
            proof = (async (): Promise<AdministratorProof> => {
              if (await env.REGISTRY.get(proofKey)) return { result: "authorized" };
              try {
                const refreshed = await revalidateInstallationAdministrator(env, linked);
                await env.REGISTRY.put(proofKey, "1", {
                  expirationTtl: GITHUB_ADMIN_CACHE_TTL_SECONDS,
                });
                return { result: "authorized", refreshed };
              } catch (error) {
                const kind = error instanceof Error ? error.message.split(":", 1)[0] : "UnknownError";
                const definitive = new Set([
                  "GitHubInstallationAdministratorRequired",
                  "GitHubInstallationAccountUnsupported",
                  "GitHubInstallationAppMismatch",
                  "GitHubInstallationResponseInvalid",
                ]);
                const message = error instanceof Error ? error.message : "";
                const missing = message === "GitHubInstallationLookup:404"
                  || message === "GitHubInstallationUserLookup:404"
                  || message === "GitHubInstallationMembershipLookup:404";
                const result = definitive.has(kind) || missing ? "unauthorized" : "unavailable";
                console.info("GitHub App owner revalidation failed", { result });
                return { result };
              }
            })();
            administratorProofs.set(proofKey, proof);
          }
          const proofResult = await proof;
          if (proofResult.result !== "authorized") {
            if (proofResult.result === "unauthorized") {
              device.linkedInstallations = device.linkedInstallations.filter(
                (installation) => installation.id !== linked.id
                  || installation.authorizingUserID !== linked.authorizingUserID,
              );
              await putDeviceRecord(env, secret, device);
              await env.REGISTRY.delete(githubAppRouteKey(linked.id, secret));
              deliverySummary.unauthorized += 1;
            }
            return;
          }
          if (proofResult.refreshed) {
            const index = device.linkedInstallations.findIndex(
              (installation) => installation.id === linked.id
                && installation.authorizingUserID === linked.authorizingUserID,
            );
            if (index >= 0) {
              device.linkedInstallations[index] = proofResult.refreshed;
              await putDeviceRecord(env, secret, device);
            }
          }
          deliverySummary.matched += 1;
          // Branch-scoped throttling prevents an unrelated feature-branch push
          // from suppressing the configured branch's wake. Reuse the bounded
          // hash so even maximum-length refs stay within KV's key limit.
          const throttleKey = `notif:${apnsCollapseID}:${device.token}`;
          if (await env.REGISTRY.get(throttleKey)) {
            deliverySummary.throttled += 1;
            return;
          }
          const text = notificationText(summary.repoFullName!, summary.commitCount);
          try {
            const response = await sendApns(apnsConfig, {
              token: device.token,
              environment: device.environment,
              title: text.title,
              body: text.body,
              collapseId: apnsCollapseID,
              contentAvailable: true,
              userInfo: {
                repo: summary.repoFullName,
                branch: summary.branch,
                ...(summary.headSHA ? { head: summary.headSHA } : {}),
                hint: hintID,
              },
            });
            if (response.ok) {
              deliverySummary.accepted += 1;
              await env.REGISTRY.put(throttleKey, "1", { expirationTtl: collapseTtl });
            } else {
              const reason = await apnsRejectionReason(response);
              if (shouldPruneDevice(response.status, reason)) {
                // Delete only when APNs says this device token is unusable. A malformed
                // relay request or provider configuration must not erase a valid opt-in.
                await deleteDevice(env, secret, device);
                deliverySummary.pruned += 1;
              } else {
                deliverySummary.rejected += 1;
              }
            }
          } catch (error) {
            // Keep the registration and leave the send unthrottled so a later event can retry.
            deliverySummary.failed += 1;
            const safeKind = boundedFailureKind(error);
            failureKinds[safeKind] = (failureKinds[safeKind] ?? 0) + 1;
          }
        });
      } catch (error) {
        // A route-index/KV failure must not reject waitUntil without an aggregate.
        // Leave all registrations intact so a later delivery can retry.
        deliverySummary.failed += 1;
        const safeKind = boundedFailureKind(error);
        failureKinds[safeKind] = (failureKinds[safeKind] ?? 0) + 1;
      }
      const aggregate = { ...deliverySummary, failureKinds };
      // Aggregate only delivery mechanics: never log repository names, tokens,
      // device secrets, branches, SHAs, hints, or notification contents.
      console.info("APNs delivery summary", aggregate);
    })(),
  );

  return json(200, { ok: true });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/healthz") {
      return json(200, {
        ok: true,
        githubAppConfigured: githubAppConfigurationIsPresent(env),
      });
    }

    if (request.method === "POST" && path === "/v1/register") {
      const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
      if (await rateLimited(env, ip)) return json(429, { error: "rate limited" });
      return handleRegister(env, request);
    }
    if (request.method === "POST" && path === "/v1/unregister") return handleUnregister(env, request);
    if (request.method === "POST" && path === "/v1/github-app/link/start") {
      const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
      if (await rateLimited(env, ip, "github-link")) return json(429, { error: "rate limited" });
      return handleGitHubAppLinkStart(env, request);
    }
    if (request.method === "GET" && path === "/v1/github-app/setup") {
      return handleGitHubAppSetup(env, request);
    }
    if (request.method === "GET" && path === "/v1/github-app/oauth/callback") {
      return handleGitHubAppOAuthCallback(env, request);
    }
    if (request.method === "POST" && path === "/v1/github-app/status") {
      return handleGitHubAppStatus(env, request);
    }
    if (request.method === "POST" && path === "/v1/github-app/unlink") {
      return handleGitHubAppUnlink(env, request);
    }
    if (request.method === "POST" && path === "/v1/github-webhook") {
      return handleGithubWebhook(env, request, ctx);
    }

    return json(404, { error: "not found" });
  },
};
