import { describe, expect, it } from "vitest";
import { apnsPayload, providerJwt, type ApnsNotification } from "./apns";

function notification(contentAvailable: boolean): ApnsNotification {
  return {
    token: "a".repeat(64),
    environment: "development",
    title: "acme/vault",
    body: "1 new commit — sync requested; tap to check",
    collapseId: "repo:abc",
    contentAvailable,
    userInfo: {
      repo: "acme/vault",
      branch: "main",
      head: "b".repeat(40),
      hint: "delivery-123",
    },
  };
}

describe("providerJwt", () => {
  it("signs an ES256 JWT through the WebCrypto ECDSA algorithm", async () => {
    const keyPair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    ) as CryptoKeyPair;
    const exportedPrivateKey = await crypto.subtle.exportKey("pkcs8", keyPair.privateKey) as ArrayBuffer;
    const privateKeyDER = new Uint8Array(exportedPrivateKey);
    const base64 = btoa(String.fromCharCode(...privateKeyDER));
    const keyP8 = [
      "-----BEGIN PRIVATE KEY-----",
      ...(base64.match(/.{1,64}/g) ?? []),
      "-----END PRIVATE KEY-----",
    ].join("\n");

    const jwt = await providerJwt({
      keyP8,
      keyId: "KEYID12345",
      teamId: "TEAMID12345",
      topic: "com.example.app",
    });
    const [encodedHeader, encodedClaims, encodedSignature] = jwt.split(".");
    expect(JSON.parse(atob(encodedHeader.replace(/-/g, "+").replace(/_/g, "/")))).toEqual({
      alg: "ES256",
      kid: "KEYID12345",
    });

    const paddedSignature = encodedSignature
      .replace(/-/g, "+")
      .replace(/_/g, "/")
      .padEnd(Math.ceil(encodedSignature.length / 4) * 4, "=");
    const signature = Uint8Array.from(atob(paddedSignature), (character) => character.charCodeAt(0));
    expect(signature).toHaveLength(64);
    await expect(crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      keyPair.publicKey,
      signature,
      new TextEncoder().encode(`${encodedHeader}.${encodedClaims}`),
    )).resolves.toBe(true);
  });
});

describe("apnsPayload", () => {
  it("combines a visible fallback alert with an opportunistic background wake", () => {
    const payload = JSON.parse(apnsPayload(notification(true)));

    expect(payload.aps).toEqual({
      alert: {
        title: "acme/vault",
        body: "1 new commit — sync requested; tap to check",
      },
      "content-available": 1,
      "interruption-level": "passive",
    });
    expect(payload.repo).toBe("acme/vault");
    expect(payload.branch).toBe("main");
    expect(payload.head).toBe("b".repeat(40));
    expect(payload.hint).toBe("delivery-123");
  });

  it("omits content-available when background wake is not requested", () => {
    const payload = JSON.parse(apnsPayload(notification(false)));
    expect(payload.aps["content-available"]).toBeUndefined();
  });
});
