/**
 * APNs provider: token-based (JWT ES256) connections over HTTP/2.
 *
 * WebCrypto's ECDSA signature output is the raw IEEE P1363 r||s format,
 * which is exactly the JWS ES256 signature encoding APNs expects.
 */

export interface ApnsConfig {
  keyP8: string;
  keyId: string;
  teamId: string;
  topic: string;
}

export interface ApnsNotification {
  token: string;
  environment: "development" | "production";
  title: string;
  body: string;
  collapseId: string;
  userInfo: Record<string, unknown>;
}

let cachedJwt: { value: string; expiresAt: number } | null = null;

function base64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): Uint8Array {
  const body = pem
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const bin = atob(body);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

async function providerJwt(config: ApnsConfig): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedJwt && cachedJwt.expiresAt > now + 60) return cachedJwt.value;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(config.keyP8),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const encoder = new TextEncoder();
  const header = base64url(encoder.encode(JSON.stringify({ alg: "ES256", kid: config.keyId })));
  const claims = base64url(encoder.encode(JSON.stringify({ iss: config.teamId, iat: now })));
  const data = new Uint8Array(await new Blob([`${header}.${claims}`]).arrayBuffer());
  const sig = new Uint8Array(await crypto.subtle.sign("ES256", key, data));

  const jwt = `${header}.${claims}.${base64url(sig)}`;
  cachedJwt = { value: jwt, expiresAt: now + 30 * 60 }; // Apple allows up to 1h; refresh at 30min.
  return jwt;
}

export async function sendApns(config: ApnsConfig, n: ApnsNotification): Promise<Response> {
  const jwt = await providerJwt(config);
  const host =
    n.environment === "development" ? "api.sandbox.push.apple.com" : "api.push.apple.com";
  return fetch(`https://${host}/3/device/${n.token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${jwt}`,
      "apns-push-type": "alert",
      "apns-topic": config.topic,
      "apns-priority": "10",
      "apns-collapse-id": n.collapseId,
    },
    body: JSON.stringify({
      aps: {
        alert: { title: n.title, body: n.body },
        "interruption-level": "passive",
      },
      ...n.userInfo,
    }),
  });
}
