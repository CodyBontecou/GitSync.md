/** Request-scoped FCM HTTP v1 sender. Provider credentials and responses never enter logs. */
export interface FcmNotification {
  token: string;
  environment: "development" | "production";
  collapseId: string;
  title: string;
  body: string;
  repo: string;
  branch: string;
  head?: string;
  hint: string;
}
export interface FcmDelivery { ok: boolean; status: number; unregistered: boolean }
export const FCM_TOKEN_RE = /^[A-Za-z0-9_.:-]{32,4096}$/;
const OAUTH_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("FcmResponseInvalid");
  return value as Record<string, unknown>;
}
async function boundedJSON(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("FcmResponseInvalid");
  const chunks: Uint8Array[] = []; let length = 0;
  try {
    while (true) {
      const result = await reader.read(); if (result.done) break;
      length += result.value.byteLength;
      if (length > 16 * 1024) throw new Error("FcmResponseTooLarge");
      chunks.push(result.value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  const body = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body)); }
  catch { throw new Error("FcmResponseInvalid"); }
}
function base64url(value: Uint8Array): string {
  let binary = ""; for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
interface Account { project: string; email: string; privateKey: string }
function account(raw: string | undefined): Account {
  if (!raw || raw.length > 64 * 1024) throw new Error("FcmNotConfigured");
  let parsed: Record<string, unknown>;
  try { parsed = object(JSON.parse(raw)); } catch { throw new Error("FcmNotConfigured"); }
  const { project_id: project, client_email: email, private_key: privateKey } = parsed;
  if (typeof project !== "string" || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(project)
      || typeof email !== "string" || !/^[A-Za-z0-9_.-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$/.test(email)
      || typeof privateKey !== "string" || privateKey.length > 16 * 1024
      || !privateKey.startsWith("-----BEGIN PRIVATE KEY-----")) throw new Error("FcmNotConfigured");
  return { project, email, privateKey };
}
export function fcmConfigurationIsPresent(raw: string | undefined): boolean {
  try { account(raw); return true; } catch { return false; }
}

export class FcmSender {
  // This instance belongs to one webhook delivery, sharing one OAuth exchange across its fan-out.
  private authorization: Promise<{ account: Account; token: string }> | undefined;
  constructor(private readonly raw: string | undefined, private readonly fetcher: typeof fetch = fetch) {}
  private async authorize(): Promise<{ account: Account; token: string }> {
    const sender = account(this.raw);
    const pem = sender.privateKey.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
    let key: CryptoKey;
    try {
      const der = Uint8Array.from(atob(pem), character => character.charCodeAt(0));
      key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    } catch { throw new Error("FcmCredentialInvalid"); }
    const encoder = new TextEncoder(); const now = Math.floor(Date.now() / 1000);
    const unsigned = [
      base64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" }))),
      base64url(encoder.encode(JSON.stringify({ iss: sender.email, scope: SCOPE, aud: OAUTH_URL, iat: now, exp: now + 3600 }))),
    ].join(".");
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
    const response = await this.fetcher(OAUTH_URL, {
      method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, redirect: "error",
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64url(new Uint8Array(signature))}` }),
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) { await response.body?.cancel(); throw new Error("FcmAuthorizationFailed"); }
    const data = object(await boundedJSON(response));
    if (typeof data.access_token !== "string" || !/^[A-Za-z0-9_.~-]{16,4096}$/.test(data.access_token)
        || data.token_type !== "Bearer" || typeof data.expires_in !== "number" || data.expires_in <= 0) throw new Error("FcmAuthorizationInvalid");
    return { account: sender, token: data.access_token };
  }
  async send(notification: FcmNotification): Promise<FcmDelivery> {
    if (!FCM_TOKEN_RE.test(notification.token) || !/^[a-f0-9]{64}$/.test(notification.collapseId)
        || !/^[A-Za-z0-9._:-]{1,128}$/.test(notification.hint)
        || !/^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/.test(notification.repo)
        || !notification.branch || new TextEncoder().encode(notification.branch).length > 255
        || /[\u0000-\u001f\u007f]/.test(notification.branch)
        || notification.title.length > 256 || notification.body.length > 1024
        || notification.head !== undefined && !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(notification.head)) throw new Error("FcmNotificationInvalid");
    this.authorization ??= this.authorize();
    const authorization = await this.authorization;
    const response = await this.fetcher(`https://fcm.googleapis.com/v1/projects/${authorization.account.project}/messages:send`, {
      method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${authorization.token}` }, redirect: "error",
      signal: AbortSignal.timeout(15_000),
      body: JSON.stringify({ message: {
        token: notification.token,
        data: { type: "gitsync.push.v1", repo: notification.repo, branch: notification.branch, hint: notification.hint,
          ...(notification.head ? { head: notification.head } : {}), title: notification.title, body: notification.body },
        android: { priority: "HIGH", ttl: "3600s", collapse_key: notification.collapseId,
          restricted_package_name: notification.environment === "development" ? "md.gitsync.md.debug" : "md.gitsync.md" },
      } }),
    });
    const data = object(await boundedJSON(response));
    if (response.ok) {
      if (typeof data.name !== "string" || !data.name.startsWith(`projects/${authorization.account.project}/messages/`)) throw new Error("FcmAcknowledgementInvalid");
      return { ok: true, status: response.status, unregistered: false };
    }
    const error = data.error && typeof data.error === "object" ? object(data.error) : {};
    const details = Array.isArray(error.details) ? error.details : [];
    const unregistered = response.status === 404 && details.some(value => {
      if (!value || typeof value !== "object") return false;
      const detail = object(value);
      return detail["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.errorCode === "UNREGISTERED";
    });
    return { ok: false, status: response.status, unregistered };
  }
}
