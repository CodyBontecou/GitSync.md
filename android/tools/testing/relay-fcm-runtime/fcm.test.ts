import { beforeAll, describe, expect, it, vi } from "vitest";
import { FcmSender, type FcmNotification, fcmConfigurationIsPresent } from "./fcm";
import { collapseID } from "./index";

let privateKey: string;
let publicKey: CryptoKey;
beforeAll(async () => {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  if (!("privateKey" in pair)) throw new Error("Expected key pair");
  publicKey = pair.publicKey;
  const der = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  if (!(der instanceof ArrayBuffer)) throw new Error("Expected PKCS8");
  let binary = ""; for (const byte of new Uint8Array(der)) binary += String.fromCharCode(byte);
  privateKey = `-----BEGIN PRIVATE KEY-----\n${btoa(binary)}\n-----END PRIVATE KEY-----`;
});
const config = () => JSON.stringify({ project_id: "gitsync-android-test", client_email: "push@gitsync-android-test.iam.gserviceaccount.com", private_key: privateKey });
const notification = (): FcmNotification => ({ token: "synthetic_FCM-token:" + "a".repeat(120), environment: "development",
  collapseId: "a".repeat(64), title: "acme/notes", body: "New commits", repo: "acme/notes", branch: "notes/東京", hint: "hint-123", head: "b".repeat(40) });
const authorized = () => new Response(JSON.stringify({ access_token: "synthetic-OAuth-access-token", token_type: "Bearer", expires_in: 3600 }));
const accepted = () => new Response(JSON.stringify({ name: "projects/gitsync-android-test/messages/example" }));

describe("FCM HTTP v1 transport", () => {
  it("accepts the actual relay collapse ID and rejects malformed or oversized keys before authorization", async () => {
    const id = await collapseID("acme/notes", "notes/東京");
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(accepted());
    expect((await new FcmSender(config(), fetcher).send({ ...notification(), collapseId: id })).ok).toBe(true);
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body)).message.android.collapse_key).toBe(id);
    for (const collapseId of ["repo:", "repo:" + "a".repeat(60), "repo:" + "g".repeat(59), "a".repeat(65), "repo:" + "a".repeat(59) + "\n"]) {
      const blocked = vi.fn<typeof fetch>();
      await expect(new FcmSender(config(), blocked).send({ ...notification(), collapseId })).rejects.toThrow("FcmNotificationInvalid");
      expect(blocked).not.toHaveBeenCalled();
    }
  });
  it("signs a correctly scoped service-account JWT and sends data-only package-bound Android hints", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(accepted());
    expect(await new FcmSender(config(), fetcher).send(notification())).toEqual({ ok: true, status: 200, unregistered: false });
    const [oauthURL, oauth] = fetcher.mock.calls[0];
    expect(oauthURL).toBe("https://oauth2.googleapis.com/token"); expect(oauth?.redirect).toBe("manual");
    const jwt = new URLSearchParams(String(oauth?.body)).get("assertion")!;
    const [header, claims, signature] = jwt.split(".");
    const decode = (text: string) => Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));
    const payload = JSON.parse(new TextDecoder().decode(decode(claims)));
    expect(payload.iss).toBe("push@gitsync-android-test.iam.gserviceaccount.com");
    expect(payload.scope).toBe("https://www.googleapis.com/auth/firebase.messaging");
    expect(payload.aud).toBe("https://oauth2.googleapis.com/token"); expect(payload.exp - payload.iat).toBe(3600);
    expect(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, decode(signature), new TextEncoder().encode(`${header}.${claims}`))).toBe(true);
    const [url, request] = fetcher.mock.calls[1];
    expect(url).toBe("https://fcm.googleapis.com/v1/projects/gitsync-android-test/messages:send");
    expect(request?.redirect).toBe("manual");
    expect(request?.headers).toMatchObject({ authorization: "Bearer synthetic-OAuth-access-token" });
    const sent = JSON.parse(String(request?.body)).message;
    expect(sent.notification).toBeUndefined();
    expect(sent.android).toMatchObject({ priority: "HIGH", ttl: "3600s", restricted_package_name: "com.bontecou.syncmd.debug" });
    expect(sent.data).toMatchObject({ type: "gitsync.push.v1", repo: "acme/notes", branch: "notes/東京", hint: "hint-123" });
  });
  it("shares exactly one OAuth exchange within one delivery fan-out", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async url => String(url).endsWith("/token") ? authorized() : accepted());
    const sender = new FcmSender(config(), fetcher);
    await Promise.all([sender.send(notification()), sender.send({ ...notification(), environment: "production" })]);
    expect(fetcher.mock.calls.filter(([url]) => String(url).endsWith("/token"))).toHaveLength(1);
    const payloads = fetcher.mock.calls.filter(([url]) => String(url).endsWith("messages:send")).map(([, request]) => JSON.parse(String(request?.body)));
    expect(payloads.map(x => x.message.android.restricted_package_name).sort()).toEqual(["com.bontecou.syncmd", "com.bontecou.syncmd.debug"]);
  });
  it("rejects redirects at either credential-bearing endpoint without following them", async () => {
    for (const status of [301, 302, 303, 307, 308]) {
      const redirect = () => new Response(null, { status, headers: { location: "https://unexpected.example/credentials" } });
      const oauth = vi.fn<typeof fetch>().mockResolvedValueOnce(redirect());
      await expect(new FcmSender(config(), oauth).send(notification())).rejects.toThrow("FcmAuthorizationFailed");
      expect(oauth).toHaveBeenCalledTimes(1);
      expect(oauth.mock.calls[0][1]?.redirect).toBe("manual");
      const delivery = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(redirect());
      await expect(new FcmSender(config(), delivery).send(notification())).rejects.toThrow("FcmRedirectRejected");
      expect(delivery).toHaveBeenCalledTimes(2);
      expect(delivery.mock.calls[1][1]?.redirect).toBe("manual");
    }
  });
  it("prunes only a typed UNREGISTERED device, preserving registrations on credentials/configuration failures", async () => {
    for (const [status, code, prune] of [[404, "UNREGISTERED", true], [403, "SENDER_ID_MISMATCH", false], [400, "INVALID_ARGUMENT", false], [503, "UNAVAILABLE", false]] as const) {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(new Response(JSON.stringify({ error: { details: [
        { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: code },
      ] } }), { status }));
      expect((await new FcmSender(config(), fetcher).send(notification())).unregistered).toBe(prune);
    }
  });
  it("rejects missing config, invalid tokens and unbounded/invalid acknowledgements without echoing provider data", async () => {
    expect(fcmConfigurationIsPresent(undefined)).toBe(false); expect(fcmConfigurationIsPresent(config())).toBe(true);
    const empty = vi.fn<typeof fetch>();
    await expect(new FcmSender(undefined, empty).send(notification())).rejects.toThrow("FcmNotConfigured");
    await expect(new FcmSender(config(), empty).send({ ...notification(), token: "invalid token" })).rejects.toThrow("FcmNotificationInvalid");
    expect(empty).not.toHaveBeenCalled();
    const huge = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(new Response("x".repeat(17000)));
    await expect(new FcmSender(config(), huge).send(notification())).rejects.toThrow("FcmResponseTooLarge");
    const wrong = vi.fn<typeof fetch>().mockResolvedValueOnce(authorized()).mockResolvedValueOnce(new Response(JSON.stringify({ name: "projects/another/messages/id" })));
    await expect(new FcmSender(config(), wrong).send(notification())).rejects.toThrow("FcmAcknowledgementInvalid");
  });
});
