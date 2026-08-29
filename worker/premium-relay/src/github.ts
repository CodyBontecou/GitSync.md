import { b64url, HttpError, pemBytes } from "./core";
import type { Env } from "./types";
const enc = new TextEncoder();

interface GitHubAccount {
  id: number;
  login: string;
  type: string;
}

interface GitHubInstallation {
  id: number;
  app_id: number;
  account: GitHubAccount | null;
}

export interface GitHubAdministratorProof {
  userId: number;
}

async function appJwt(env: Env, now = Date.now()): Promise<string> {
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = b64url(enc.encode(JSON.stringify({ iat: Math.floor(now / 1000) - 60, exp: Math.floor(now / 1000) + 540, iss: env.GITHUB_APP_ID })));
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(env.GITHUB_APP_PRIVATE_KEY), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${payload}`));
  return `${header}.${payload}.${b64url(new Uint8Array(sig))}`;
}
async function github(env: Env, path: string, init: RequestInit = {}, installation = false): Promise<Response> {
  const inputHeaders = new Headers(init.headers);
  const token = installation ? inputHeaders.get("authorization") : `Bearer ${await appJwt(env)}`;
  const headers = new Headers(inputHeaders);
  headers.set("accept", "application/vnd.github+json");
  headers.set("authorization", token ?? "");
  headers.set("user-agent", "GitSync-Premium-Relay");
  headers.set("x-github-api-version", "2022-11-28");
  const res = await fetch(`https://api.github.com${path}`, { ...init, headers });
  if (!res.ok) throw new HttpError(res.status === 404 ? 404 : 502, "GitHub request failed"); return res;
}

function account(value: unknown): GitHubAccount {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new HttpError(502, "GitHub account response invalid");
  const record = value as Record<string, unknown>;
  if (!Number.isSafeInteger(record.id) || Number(record.id) <= 0 || typeof record.login !== "string" || !record.login || typeof record.type !== "string") {
    throw new HttpError(502, "GitHub account response invalid");
  }
  return { id: Number(record.id), login: record.login, type: record.type };
}

export async function getInstallation(env: Env, id: number): Promise<GitHubInstallation> {
  const v = await github(env, `/app/installations/${id}`).then(r => r.json()) as Record<string, unknown>;
  if (v.id !== id || String(v.app_id) !== env.GITHUB_APP_ID) throw new HttpError(403, "wrong GitHub App installation");
  return { id, app_id: Number(v.app_id), account: v.account === null || v.account === undefined ? null : account(v.account) };
}

export async function exchangeGitHubUserCode(env: Env, code: string): Promise<string> {
  const body = new URLSearchParams({
    client_id: env.GITHUB_CLIENT_ID,
    client_secret: env.GITHUB_CLIENT_SECRET,
    code,
    redirect_uri: env.GITHUB_AUTHORIZATION_CALLBACK_URL,
  });
  const response = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/x-www-form-urlencoded",
      "user-agent": "GitSync-Premium-Relay",
    },
    body,
  });
  if (!response.ok) throw new HttpError(502, "GitHub authorization failed");
  const value = await response.json() as { access_token?: unknown; token_type?: unknown; error?: unknown };
  if (value.error !== undefined || typeof value.access_token !== "string" || !value.access_token ||
      (value.token_type !== undefined && String(value.token_type).toLowerCase() !== "bearer")) {
    throw new HttpError(403, "GitHub authorization failed");
  }
  return value.access_token;
}

async function authenticatedUser(env: Env, token: string): Promise<GitHubAccount> {
  return account(await github(env, "/user", { headers: { authorization: `Bearer ${token}` } }, true).then(response => response.json()));
}

function stableNumericId(value: unknown): number {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new HttpError(502, "GitHub membership response invalid");
  const id = (value as Record<string, unknown>).id;
  if (!Number.isSafeInteger(id) || Number(id) <= 0) throw new HttpError(502, "GitHub membership response invalid");
  return Number(id);
}

function requireOrganizationOwner(membership: Record<string, unknown>, organizationId: number, userId: number): void {
  if (membership.state !== "active" || membership.role !== "admin" ||
      stableNumericId(membership.organization) !== organizationId || stableNumericId(membership.user) !== userId) {
    throw new HttpError(403, "GitHub installation administrator required");
  }
}

export async function proveInstallationAdministrator(env: Env, installationId: number, token: string): Promise<GitHubAdministratorProof> {
  const [user, installation] = await Promise.all([authenticatedUser(env, token), getInstallation(env, installationId)]);
  const installedAccount = installation.account;
  if (!installedAccount) throw new HttpError(403, "unsupported GitHub installation account");
  if (installedAccount.type === "User") {
    if (installedAccount.id !== user.id) throw new HttpError(403, "GitHub installation administrator required");
    return { userId: user.id };
  }
  if (installedAccount.type !== "Organization") throw new HttpError(403, "unsupported GitHub installation account");
  const membership = await github(env, `/user/memberships/orgs/${encodeURIComponent(installedAccount.login)}`, {
    headers: { authorization: `Bearer ${token}` },
  }, true).then(response => response.json()) as Record<string, unknown>;
  requireOrganizationOwner(membership, installedAccount.id, user.id);
  return { userId: user.id };
}

async function installationToken(env: Env, installationId: number, permissions: Record<string, "read">): Promise<string> {
  const response = await github(env, `/app/installations/${installationId}/access_tokens`, {
    method: "POST",
    body: JSON.stringify({ permissions }),
    headers: { "content-type": "application/json" },
  });
  const token = (await response.json() as { token?: unknown }).token;
  if (typeof token !== "string" || !token) throw new HttpError(502, "GitHub token response invalid");
  return token;
}

export async function revalidateInstallationAdministrator(env: Env, installationId: number, userId: number): Promise<void> {
  const installation = await getInstallation(env, installationId);
  const installedAccount = installation.account;
  if (!installedAccount || !Number.isSafeInteger(userId) || userId <= 0) throw new HttpError(403, "GitHub installation administrator required");
  if (installedAccount.type === "User") {
    if (installedAccount.id !== userId) throw new HttpError(403, "GitHub installation administrator required");
    return;
  }
  if (installedAccount.type !== "Organization") throw new HttpError(403, "unsupported GitHub installation account");
  const token = await installationToken(env, installationId, { members: "read" });
  const user = account(await github(env, `/user/${userId}`, {
    headers: { authorization: `Bearer ${token}` },
  }, true).then(response => response.json()));
  if (user.id !== userId || user.type !== "User") throw new HttpError(403, "GitHub installation administrator required");
  const membership = await github(env, `/orgs/${encodeURIComponent(installedAccount.login)}/memberships/${encodeURIComponent(user.login)}`, {
    headers: { authorization: `Bearer ${token}` },
  }, true).then(response => response.json()) as Record<string, unknown>;
  requireOrganizationOwner(membership, installedAccount.id, userId);
}

export async function revokeGitHubUserToken(env: Env, token: string): Promise<void> {
  const credentials = new TextEncoder().encode(`${env.GITHUB_CLIENT_ID}:${env.GITHUB_CLIENT_SECRET}`);
  let raw = "";
  for (const byte of credentials) raw += String.fromCharCode(byte);
  const response = await fetch(`https://api.github.com/applications/${encodeURIComponent(env.GITHUB_CLIENT_ID)}/token`, {
    method: "DELETE",
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Basic ${btoa(raw)}`,
      "content-type": "application/json",
      "user-agent": "GitSync-Premium-Relay",
      "x-github-api-version": "2022-11-28",
    },
    body: JSON.stringify({ access_token: token }),
  });
  if (!response.ok && response.status !== 404) throw new Error("GitHub token revocation failed");
}

export async function proveRepository(env: Env, installationId: number, repositoryId: number): Promise<void> {
  const token = await installationToken(env, installationId, { contents: "read" });
  await github(env, `/repositories/${repositoryId}`, { headers: { authorization: `Bearer ${token}` } }, true);
}
export async function verifyWebhook(secret: string, raw: ArrayBuffer, signature: string | null): Promise<boolean> {
  if (!signature?.startsWith("sha256=") || !/^[0-9a-f]{64}$/.test(signature.slice(7))) return false;
  const bytes = new Uint8Array(signature.slice(7).match(/../g)!.map(x => parseInt(x,16)));
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name:"HMAC", hash:"SHA-256" }, false, ["verify"]);
  return crypto.subtle.verify("HMAC", key, bytes, raw);
}
