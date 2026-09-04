import { describe, expect, it } from "vitest";
import {
  notificationText,
  parseRegisterRequest,
  summarizePushEvent,
  timingSafeEqualHex,
  verifyGithubSignature,
} from "./index";

describe("timingSafeEqualHex", () => {
  it("matches identical hex", () => {
    expect(timingSafeEqualHex("aabbcc", "aabbcc")).toBe(true);
  });
  it("rejects different values", () => {
    expect(timingSafeEqualHex("aabbcc", "aabbcd")).toBe(false);
  });
  it("rejects length mismatch without throwing", () => {
    expect(timingSafeEqualHex("aa", "aabb")).toBe(false);
  });
  it("rejects non-hex input", () => {
    expect(timingSafeEqualHex("zz", "zz")).toBe(false);
  });
});

describe("verifyGithubSignature", () => {
  const secret = "s3cret";
  const body = JSON.stringify({ zen: "Design for failure." });

  async function sign(payload: string, key: string): Promise<string> {
    const cryptoKey = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(key),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const mac = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(payload)));
    return "sha256=" + Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
  }

  it("accepts a valid signature", async () => {
    const header = await sign(body, secret);
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, header, secret)).toBe(true);
  });
  it("rejects a wrong secret", async () => {
    const header = await sign(body, "other");
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, header, secret)).toBe(false);
  });
  it("rejects a missing header", async () => {
    expect(await verifyGithubSignature(new TextEncoder().encode(body).buffer as ArrayBuffer, null, secret)).toBe(false);
  });
});

describe("parseRegisterRequest", () => {
  const token = "a".repeat(64);

  it("accepts a valid registration and normalizes repo names", () => {
    const result = parseRegisterRequest({
      token,
      environment: "development",
      deviceSecret: "abc-123-def",
      repos: ["CodyBontecou/GitSync.md", "codybontecou/gitsync.md", "user/travel"],
    });
    expect(result).not.toBeNull();
    expect(result!.repos).toEqual(["codybontecou/gitsync.md", "user/travel"]);
    expect(result!.environment).toBe("development");
  });
  it("rejects a malformed token", () => {
    expect(parseRegisterRequest({ token: "xyz", environment: "production", deviceSecret: "abc-123-def", repos: [] })).toBeNull();
  });
  it("rejects a malformed repo name", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "abc-123-def", repos: ["no slash"] })).toBeNull();
  });
  it("rejects a short device secret", () => {
    expect(parseRegisterRequest({ token, environment: "production", deviceSecret: "short", repos: [] })).toBeNull();
  });
  it("rejects unknown environment", () => {
    expect(parseRegisterRequest({ token, environment: "staging", deviceSecret: "abc-123-def", repos: [] })).toBeNull();
  });
});

describe("summarizePushEvent", () => {
  it("extracts repo, commit count, and deletion flag", () => {
    const summary = summarizePushEvent({
      repository: { full_name: "CodyBontecou/travel" },
      commits: [{}, {}, {}],
    });
    expect(summary).toEqual({ repoFullName: "codybontecou/travel", commitCount: 3, isDeletion: false });
  });
  it("flags deletions", () => {
    const summary = summarizePushEvent({ repository: { full_name: "a/b" }, deleted: true, commits: [] });
    expect(summary.isDeletion).toBe(true);
  });
  it("survives malformed payloads", () => {
    expect(summarizePushEvent(null)).toEqual({ repoFullName: null, commitCount: 0, isDeletion: false });
  });
});

describe("notificationText", () => {
  it("singularizes one commit", () => {
    expect(notificationText("a/b", 1).body).toBe("1 new commit — tap to sync");
  });
  it("pluralizes multiple commits", () => {
    expect(notificationText("a/b", 3).body).toBe("3 new commits — tap to sync");
  });
});
