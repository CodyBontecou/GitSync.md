import { afterEach, describe, expect, it, vi } from "vitest";
import {
  exchangeGitHubOAuthCode,
  githubAppConfigurationIsPresent,
  githubAppJWT,
  installationIDFrom,
  parseInstallationMetadata,
  parseLinkedInstallation,
  proveInstallationAdministrator,
  type GitHubAppEnv,
} from "./github-app";

function base64urlDecode(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function pem(label: string, bytes: ArrayBuffer): string {
  const binary = String.fromCharCode(...new Uint8Array(bytes));
  const lines = btoa(binary).match(/.{1,64}/g) ?? [];
  return `-----BEGIN ${label}-----\n${lines.join("\n")}\n-----END ${label}-----`;
}

async function testKeyPair(): Promise<{ privatePEM: string; publicKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  if (!("privateKey" in pair)) throw new Error("expected key pair");
  const exported = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  if (!(exported instanceof ArrayBuffer)) throw new Error("expected PKCS#8 bytes");
  return {
    privatePEM: pem("PRIVATE KEY", exported),
    publicKey: pair.publicKey,
  };
}

function env(privateKey = "test-private-key"): GitHubAppEnv {
  return {
    GITHUB_APP_ID: "123456",
    GITHUB_APP_CLIENT_ID: "IvTestClient123",
    GITHUB_APP_CLIENT_SECRET: "client-secret-for-tests",
    GITHUB_APP_PRIVATE_KEY_PKCS8: privateKey,
    GITHUB_APP_SLUG: "gitsync-md-push-sync-test",
    GITHUB_APP_CALLBACK_URL: "https://push.example.test/v1/github-app/oauth/callback",
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("GitHub App JWT", () => {
  it("creates a GitHub-compatible RS256 token with bounded claims", async () => {
    const keyPair = await testKeyPair();
    const now = 1_800_000_000;
    const token = await githubAppJWT(env(keyPair.privatePEM), now);
    const [header, claims, signature] = token.split(".");

    expect(JSON.parse(new TextDecoder().decode(base64urlDecode(header)))).toEqual({
      alg: "RS256",
      typ: "JWT",
    });
    expect(JSON.parse(new TextDecoder().decode(base64urlDecode(claims)))).toEqual({
      iat: now - 60,
      exp: now + 540,
      iss: "IvTestClient123",
    });
    expect(await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      keyPair.publicKey,
      base64urlDecode(signature),
      new TextEncoder().encode(`${header}.${claims}`),
    )).toBe(true);
  });

  it("rejects GitHub's downloaded PKCS#1 envelope until it is converted to PKCS#8", async () => {
    await expect(githubAppJWT(env(
      "-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----",
    ))).rejects.toThrow("GitHubAppPrivateKeyFormat");
  });
});

describe("GitHub installation metadata validation", () => {
  const response = {
    id: 123,
    app_id: 123456,
    account: { id: 9001, login: "octo-org", type: "Organization" },
    repository_selection: "selected",
    html_url: "https://github.com/settings/installations/123",
    suspended_at: null,
  };

  it("normalizes an active installation and round-trips stored metadata", () => {
    const installation = parseInstallationMetadata(response, 42, 43);
    expect(installation).toEqual({
      id: 123,
      accountID: 9001,
      accountLogin: "octo-org",
      accountType: "Organization",
      authorizingUserID: 42,
      repositorySelection: "selected",
      htmlURL: "https://github.com/settings/installations/123",
      status: "active",
      connectedAt: 43,
    });
    expect(parseLinkedInstallation(installation)).toEqual(installation);
  });

  it("marks suspension and rejects malformed or off-origin management URLs", () => {
    expect(parseInstallationMetadata({ ...response, suspended_at: "2026-01-01" }, 42)?.status).toBe("suspended");
    expect(parseInstallationMetadata({ ...response, html_url: "https://evil.example/install/123" }, 42)).toBeNull();
    expect(parseInstallationMetadata({
      ...response,
      account: { id: 9001, login: "bad/name", type: "User" },
    }, 42)).toBeNull();
  });

  it("accepts only positive safe installation IDs", () => {
    expect(installationIDFrom("123")).toBe(123);
    expect(installationIDFrom(456)).toBe(456);
    expect(installationIDFrom("0")).toBeNull();
    expect(installationIDFrom("1e3")).toBeNull();
    expect(installationIDFrom(Number.MAX_SAFE_INTEGER + 1)).toBeNull();
  });
});

describe("GitHub App administrator proof", () => {
  it("links a personal installation only to the same immutable user ID", async () => {
    const keyPair = await testKeyPair();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, login: "octocat", type: "User" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 123,
        app_id: 123456,
        account: { id: 42, login: "octocat", type: "User" },
        repository_selection: "all",
        html_url: "https://github.com/settings/installations/123",
        suspended_at: null,
      }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const installation = await proveInstallationAdministrator(
      env(keyPair.privatePEM),
      123,
      "github-user-token-for-tests",
    );
    expect(installation.accountID).toBe(42);
    expect(installation.authorizingUserID).toBe(42);
  });
});

describe("GitHub App OAuth exchange", () => {
  it("sends the PKCE verifier and returns the token only to the ownership-proof caller", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify({ access_token: "github-user-token-for-tests" }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const token = await exchangeGitHubOAuthCode(env(), "a".repeat(40), "v".repeat(64));
    expect(token).toBe("github-user-token-for-tests");
    expect(fetchMock.mock.calls[0][0]).toBe("https://github.com/login/oauth/access_token");
    const exchangeBody = String(fetchMock.mock.calls[0][1]?.body);
    expect(exchangeBody).toContain("code_verifier=");
  });
});

describe("GitHub App configuration", () => {
  it("requires a valid slug, HTTPS callback path, and all secrets", () => {
    expect(githubAppConfigurationIsPresent(env())).toBe(true);
    expect(githubAppConfigurationIsPresent({ ...env(), GITHUB_APP_ID: "" })).toBe(false);
    expect(githubAppConfigurationIsPresent({ ...env(), GITHUB_APP_SLUG: "" })).toBe(false);
    expect(githubAppConfigurationIsPresent({
      ...env(),
      GITHUB_APP_CALLBACK_URL: "http://push.example.test/v1/github-app/oauth/callback",
    })).toBe(false);
    expect(githubAppConfigurationIsPresent({ ...env(), GITHUB_APP_CLIENT_SECRET: "" })).toBe(false);
  });
});
