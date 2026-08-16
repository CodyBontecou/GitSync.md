import { b64url, HttpError, pemBytes } from "./core";
import type { Env } from "./types";
const enc = new TextEncoder();

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
export async function getInstallation(env: Env, id: number): Promise<{ id:number; app_id:number }> {
  const v = await github(env, `/app/installations/${id}`).then(r => r.json()) as { id:number; app_id:number };
  if (v.id !== id || String(v.app_id) !== env.GITHUB_APP_ID) throw new HttpError(403,"wrong GitHub App installation"); return v;
}
export async function proveRepository(env: Env, installationId: number, repositoryId: number): Promise<void> {
  const tokenRes = await github(env, `/app/installations/${installationId}/access_tokens`, { method: "POST", body: JSON.stringify({ permissions: { contents: "read" } }), headers: { "content-type": "application/json" } });
  const token = (await tokenRes.json() as {token?:unknown}).token; if (typeof token !== "string") throw new HttpError(502,"GitHub token response invalid");
  await github(env, `/repositories/${repositoryId}`, { headers: { authorization: `Bearer ${token}` } }, true);
}
export async function verifyWebhook(secret: string, raw: ArrayBuffer, signature: string | null): Promise<boolean> {
  if (!signature?.startsWith("sha256=") || !/^[0-9a-f]{64}$/.test(signature.slice(7))) return false;
  const bytes = new Uint8Array(signature.slice(7).match(/../g)!.map(x => parseInt(x,16)));
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name:"HMAC", hash:"SHA-256" }, false, ["verify"]);
  return crypto.subtle.verify("HMAC", key, bytes, raw);
}
