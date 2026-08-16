import { env, SELF } from "cloudflare:test";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { APPLE_ROOTS } from "../src/roots";
import { verifyAcrossEnvironments } from "../src/index";
import {
  TEST_CA_BASE64,
  renewalInfo__JWS,
  testNotification__JWS,
  transactionInfo__JWS,
  wrongBundleId__JWS,
} from "./fixtures";

const testRoot = Buffer.from(TEST_CA_BASE64, "base64");
const testEnv = {
  ...env,
  BUNDLE_ID: "com.example",
  APP_APPLE_ID: "1234",
  ENABLE_ONLINE_CHECKS: "false",
} as unknown as Parameters<typeof verifyAcrossEnvironments>[0];

const json = (path: string, body: unknown, headers?: HeadersInit) =>
  SELF.fetch(`https://verifier.internal${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });

afterEach(() => { vi.restoreAllMocks(); });

describe("StoreKit verifier Worker", () => {
  it("pins the official Apple trust anchors", () => {
    expect(APPLE_ROOTS.map((root) => createHash("sha256").update(root).digest("hex"))).toEqual([
      "b0b1730ecbc7ff4505142c49f1295e6eda6bcaed7e2c68c5be91b5a11001f024",
      "c2b9b042dd57830e7d117dac55ac8ae19407d38e41d88f3215bc3a890444a050",
      "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179",
    ]);
  });

  it("verifies a correctly chained and signed transaction fixture", async () => {
    const decoded = await verifyAcrossEnvironments(
      testEnv,
      (candidate) => candidate.verifyAndDecodeTransaction(transactionInfo__JWS),
      [testRoot],
    );
    expect(decoded).toMatchObject({ bundleId: "com.example", environment: "Sandbox" });
  });

  it("verifies a correctly chained and signed notification fixture", async () => {
    const decoded = await verifyAcrossEnvironments(
      testEnv,
      (candidate) => candidate.verifyAndDecodeNotification(testNotification__JWS),
      [testRoot],
    );
    expect(decoded).toMatchObject({ notificationType: "TEST", notificationUUID: expect.any(String) });
  });

  it("cryptographically verifies signed renewal info used for grace-period state", async () => {
    const decoded = await verifyAcrossEnvironments(
      testEnv,
      (candidate) => candidate.verifyAndDecodeRenewalInfo(renewalInfo__JWS),
      [testRoot],
    );
    expect(decoded).toMatchObject({ environment: "Sandbox", signedDate: 1672956154000 });
  });

  it("rejects a valid signed fixture with the wrong bundle ID", async () => {
    await expect(verifyAcrossEnvironments(
      testEnv,
      (candidate) => candidate.verifyAndDecodeNotification(wrongBundleId__JWS),
      [testRoot],
    )).rejects.toMatchObject({ status: 401 });
  });

  it("rejects malformed and unsigned transaction input", async () => {
    const malformed = await json("/v1/transactions/verify", { signedTransaction: "not.a.jws" });
    expect(malformed.status).toBe(401);
    expect(await malformed.json()).toEqual({ error: "verification failed" });
  });

  it("rejects malformed and unsigned notification input", async () => {
    const malformed = await json("/v1/notifications/verify", { signedPayload: "not.a.jws" });
    expect(malformed.status).toBe(401);
    expect(await malformed.json()).toEqual({ error: "verification failed" });
  });

  it("enforces content type, exact schema, and configured body limit", async () => {
    expect((await SELF.fetch("https://verifier.internal/v1/transactions/verify", {
      method: "POST",
      body: "{}",
    })).status).toBe(415);
    expect((await json("/v1/transactions/verify", { signedTransaction: "x", extra: true })).status).toBe(400);
    expect((await json("/v1/transactions/verify", { signedTransaction: "x" }, {
      "content-length": String(Number(env.MAX_JWS_BYTES) + 1),
    })).status).toBe(413);
  });

  it("does not expose a public verification surface beyond the two POST routes", async () => {
    expect((await SELF.fetch("https://verifier.internal/v1/transactions/verify")).status).toBe(405);
    expect((await json("/unknown", {})).status).toBe(404);
  });

  it("never logs caller-supplied signed payload material on rejection", async () => {
    const marker = "SECRET_JWS_MARKER";
    const warning = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const rejected = await json("/v1/transactions/verify", { signedTransaction: `${marker}.payload.signature` });
    expect(rejected.status).toBe(401);
    expect(JSON.stringify(warning.mock.calls)).not.toContain(marker);
  });

  it("never logs caller-supplied signed payload material on unexpected verifier errors", async () => {
    const marker = "SECRET_JWS_MARKER";
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    await expect(verifyAcrossEnvironments(testEnv, async () => {
      const error = new Error(`provider error included ${marker}`);
      error.name = marker;
      Object.defineProperty(error, "cause", { value: marker });
      throw error;
    }, [testRoot])).rejects.toMatchObject({ status: 503 });
    expect(JSON.stringify(logged.mock.calls)).not.toContain(marker);
    expect(logged).toHaveBeenCalledWith("StoreKit verification unavailable", { category: "unexpected" });
  });

  it("fails closed on invalid verifier configuration", async () => {
    const original = env.ALLOWED_ENVIRONMENTS;
    (env as unknown as Record<string, string>).ALLOWED_ENVIRONMENTS = "LocalTesting";
    try {
      expect((await json("/v1/transactions/verify", { signedTransaction: "not.a.jws" })).status).toBe(503);
    } finally {
      (env as unknown as Record<string, string>).ALLOWED_ENVIRONMENTS = original;
    }
  });
});
