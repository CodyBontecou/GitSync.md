import { b64url, pemBytes } from "./core";
import type { Env } from "./types";

const encoder = new TextEncoder();
let cached: { token: string; until: number; keyId: string; teamId: string } | undefined;

function derToJose(der: Uint8Array): Uint8Array {
  let cursor = 2;
  if (der[1]! & 0x80) cursor = 2 + (der[1]! & 0x7f);
  if (der[cursor++] !== 2) throw new Error("invalid ECDSA signature");
  const rLength = der[cursor++]!;
  let r = der.slice(cursor, cursor + rLength);
  cursor += rLength;
  if (der[cursor++] !== 2) throw new Error("invalid ECDSA signature");
  const sLength = der[cursor++]!;
  let s = der.slice(cursor, cursor + sLength);
  while (r.length > 32 && r[0] === 0) r = r.slice(1);
  while (s.length > 32 && s[0] === 0) s = s.slice(1);
  if (r.length > 32 || s.length > 32) throw new Error("invalid ECDSA signature");
  const output = new Uint8Array(64);
  output.set(r, 32 - r.length);
  output.set(s, 64 - s.length);
  return output;
}

export async function apnsJwt(env: Env, currentTime = Date.now()): Promise<string> {
  if (cached && cached.until > currentTime && cached.keyId === env.APNS_KEY_ID && cached.teamId === env.APNS_TEAM_ID) {
    return cached.token;
  }
  const header = b64url(encoder.encode(JSON.stringify({ alg: "ES256", kid: env.APNS_KEY_ID })));
  const payload = b64url(encoder.encode(JSON.stringify({ iss: env.APNS_TEAM_ID, iat: Math.floor(currentTime / 1000) })));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemBytes(env.APNS_PRIVATE_KEY),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const raw = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    encoder.encode(`${header}.${payload}`),
  ));
  const signature = raw.length === 64 ? raw : derToJose(raw);
  const token = `${header}.${payload}.${b64url(signature)}`;
  cached = { token, until: currentTime + 50 * 60_000, keyId: env.APNS_KEY_ID, teamId: env.APNS_TEAM_ID };
  return token;
}

export interface SilentPushHint {
  channel: string;
  hint: string;
}

export function silentPayload(hint: SilentPushHint): string {
  return JSON.stringify({ aps: { "content-available": 1 }, channel: hint.channel, hint: hint.hint });
}

export type ApnsClass = "success" | "invalidToken" | "permanent" | "transient";
export function classifyApns(status: number, reason?: string): ApnsClass {
  if (status >= 200 && status < 300) return "success";
  if (status === 410 || ["BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered"].includes(reason ?? "")) return "invalidToken";
  if (status === 429 || status >= 500 || ["ExpiredProviderToken", "InvalidProviderToken", "TooManyProviderTokenUpdates"].includes(reason ?? "")) return "transient";
  // A non-token 4xx (BadTopic, TopicDisallowed, BadPriority,
  // PayloadEmpty, etc.) is a provider/request configuration failure, not
  // evidence that the device token should be erased. Retry to the Queue limit
  // so the event lands in the DLQ and remains visible for operator repair.
  return "transient";
}

export interface ApnsResult {
  status: number;
  reason?: string;
  class: ApnsClass;
}

export async function sendApns(
  env: Env,
  token: string,
  environment: "sandbox" | "production",
  hint: SilentPushHint,
): Promise<ApnsResult> {
  const host = environment === "sandbox" ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
  const response = await fetch(`${host}/3/device/${token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${await apnsJwt(env)}`,
      "apns-topic": env.APNS_TOPIC,
      "apns-push-type": "background",
      "apns-priority": "5",
      "apns-expiration": "0",
      "content-type": "application/json",
    },
    body: silentPayload(hint),
  });
  let reason: string | undefined;
  try {
    const value = await response.json() as { reason?: unknown };
    if (typeof value.reason === "string") reason = value.reason;
  } catch {}
  return { status: response.status, ...(reason ? { reason } : {}), class: classifyApns(response.status, reason) };
}
