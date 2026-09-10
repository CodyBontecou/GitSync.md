/**
 * GitHub App authentication helpers.
 *
 * The relay uses an app JWT only to verify installation metadata. During the
 * connection flow it exchanges a short-lived OAuth code for a user token,
 * proves personal ownership or active organization-owner authority, requests
 * immediate token revocation, and never stores the token or forwards it to APNs.
 */

export interface GitHubAppEnv {
  GITHUB_APP_ID: string;
  GITHUB_APP_CLIENT_ID: string;
  GITHUB_APP_CLIENT_SECRET: string;
  /** PKCS#8 PEM. GitHub downloads PKCS#1; convert it before storing the secret. */
  GITHUB_APP_PRIVATE_KEY_PKCS8: string;
  GITHUB_APP_SLUG: string;
  GITHUB_APP_CALLBACK_URL: string;
}

export interface LinkedGitHubInstallation {
  id: number;
  accountID: number;
  accountLogin: string;
  accountType: string;
  authorizingUserID: number;
  repositorySelection: "all" | "selected";
  htmlURL: string;
  status: "active" | "suspended";
  connectedAt: number;
}

interface GitHubInstallationResponse {
  id?: unknown;
  app_id?: unknown;
  account?: unknown;
  repository_selection?: unknown;
  html_url?: unknown;
  suspended_at?: unknown;
}

const CLIENT_ID_RE = /^[A-Za-z0-9._-]{8,128}$/;
const APP_SLUG_RE = /^[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?$/;
const LOGIN_RE = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?$/;

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): Uint8Array {
  if (!pem.includes("-----BEGIN PRIVATE KEY-----") || !pem.includes("-----END PRIVATE KEY-----")) {
    throw new Error("GitHubAppPrivateKeyFormat");
  }
  const body = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  if (!body || !/^[A-Za-z0-9+/=]+$/.test(body)) throw new Error("GitHubAppPrivateKeyFormat");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export function githubAppConfigurationIsPresent(env: GitHubAppEnv): boolean {
  if (!/^[1-9][0-9]{0,15}$/.test(env.GITHUB_APP_ID ?? "")) return false;
  if (!CLIENT_ID_RE.test(env.GITHUB_APP_CLIENT_ID ?? "")) return false;
  if (!APP_SLUG_RE.test(env.GITHUB_APP_SLUG ?? "")) return false;
  try {
    const callback = new URL(env.GITHUB_APP_CALLBACK_URL);
    return callback.protocol === "https:"
      && callback.pathname === "/v1/github-app/oauth/callback"
      && !callback.username && !callback.password && !callback.search && !callback.hash
      && Boolean(env.GITHUB_APP_CLIENT_SECRET)
      && Boolean(env.GITHUB_APP_PRIVATE_KEY_PKCS8);
  } catch {
    return false;
  }
}

/** GitHub requires RS256, iat in the past for clock skew, and exp <= 10 min. */
export async function githubAppJWT(env: GitHubAppEnv, now = Math.floor(Date.now() / 1000)): Promise<string> {
  if (!CLIENT_ID_RE.test(env.GITHUB_APP_CLIENT_ID)) throw new Error("GitHubAppClientIDInvalid");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(env.GITHUB_APP_PRIVATE_KEY_PKCS8),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const encoder = new TextEncoder();
  const header = base64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64url(encoder.encode(JSON.stringify({
    iat: now - 60,
    exp: now + 9 * 60,
    iss: env.GITHUB_APP_CLIENT_ID,
  })));
  const signingInput = encoder.encode(`${header}.${claims}`);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, signingInput));
  return `${header}.${claims}.${base64url(signature)}`;
}

function validInstallationID(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function nestedNumericID(value: unknown): number | null {
  if (typeof value !== "object" || value === null) return null;
  const id = (value as Record<string, unknown>).id;
  return validInstallationID(id) ? id : null;
}

function validGitHubURL(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 500) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname === "github.com"
      && !url.username && !url.password && !url.search && !url.hash;
  } catch {
    return false;
  }
}

export function parseLinkedInstallation(body: unknown): LinkedGitHubInstallation | null {
  if (typeof body !== "object" || body === null) return null;
  const value = body as Record<string, unknown>;
  if (!validInstallationID(value.id) || !validInstallationID(value.accountID)
      || !validInstallationID(value.authorizingUserID)) return null;
  if (typeof value.accountLogin !== "string" || !LOGIN_RE.test(value.accountLogin)) return null;
  if (typeof value.accountType !== "string" || !/^[A-Za-z][A-Za-z ]{0,31}$/.test(value.accountType)) return null;
  if (value.repositorySelection !== "all" && value.repositorySelection !== "selected") return null;
  if (!validGitHubURL(value.htmlURL)) return null;
  if (value.status !== "active" && value.status !== "suspended") return null;
  if (typeof value.connectedAt !== "number" || !Number.isFinite(value.connectedAt) || value.connectedAt < 0) return null;
  return {
    id: value.id,
    accountID: value.accountID,
    accountLogin: value.accountLogin,
    accountType: value.accountType,
    authorizingUserID: value.authorizingUserID,
    repositorySelection: value.repositorySelection,
    htmlURL: value.htmlURL,
    status: value.status,
    connectedAt: value.connectedAt,
  };
}

export function parseInstallationMetadata(
  body: unknown,
  authorizingUserID: number,
  connectedAt = Date.now(),
): LinkedGitHubInstallation | null {
  if (typeof body !== "object" || body === null) return null;
  const installation = body as GitHubInstallationResponse;
  if (!validInstallationID(installation.id) || !validInstallationID(authorizingUserID)) return null;
  if (typeof installation.account !== "object" || installation.account === null) return null;
  const account = installation.account as Record<string, unknown>;
  if (!validInstallationID(account.id)) return null;
  if (typeof account.login !== "string" || !LOGIN_RE.test(account.login)) return null;
  if (typeof account.type !== "string" || !/^[A-Za-z][A-Za-z ]{0,31}$/.test(account.type)) return null;
  if (installation.repository_selection !== "all" && installation.repository_selection !== "selected") return null;
  if (!validGitHubURL(installation.html_url)) return null;
  return {
    id: installation.id,
    accountID: account.id,
    accountLogin: account.login,
    accountType: account.type,
    authorizingUserID,
    repositorySelection: installation.repository_selection,
    htmlURL: installation.html_url,
    status: installation.suspended_at == null ? "active" : "suspended",
    connectedAt,
  };
}

function githubHeaders(token: string): HeadersInit {
  return {
    accept: "application/vnd.github+json",
    authorization: `Bearer ${token}`,
    "user-agent": "GitSync.md-Push-Sync",
    "x-github-api-version": "2022-11-28",
  };
}

export async function fetchInstallationMetadata(
  env: GitHubAppEnv,
  installationID: number,
  authorizingUserID: number,
): Promise<LinkedGitHubInstallation> {
  if (!validInstallationID(installationID) || !validInstallationID(authorizingUserID)) {
    throw new Error("GitHubInstallationIDInvalid");
  }
  const jwt = await githubAppJWT(env);
  const response = await fetch(`https://api.github.com/app/installations/${installationID}`, {
    headers: githubHeaders(jwt),
  });
  if (!response.ok) throw new Error(`GitHubInstallationLookup:${response.status}`);
  const body = await response.json() as GitHubInstallationResponse;
  if (String(body.app_id) !== env.GITHUB_APP_ID) throw new Error("GitHubInstallationAppMismatch");
  const metadata = parseInstallationMetadata(body, authorizingUserID);
  if (!metadata || metadata.id !== installationID) throw new Error("GitHubInstallationResponseInvalid");
  return metadata;
}

export async function exchangeGitHubOAuthCode(
  env: GitHubAppEnv,
  code: string,
  codeVerifier: string,
): Promise<string> {
  if (!/^[A-Za-z0-9._-]{16,256}$/.test(code)) throw new Error("GitHubOAuthCodeInvalid");
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(codeVerifier)) throw new Error("GitHubOAuthVerifierInvalid");
  const form = new URLSearchParams({
    client_id: env.GITHUB_APP_CLIENT_ID,
    client_secret: env.GITHUB_APP_CLIENT_SECRET,
    code,
    redirect_uri: env.GITHUB_APP_CALLBACK_URL,
    code_verifier: codeVerifier,
  });
  const response = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/x-www-form-urlencoded",
      "user-agent": "GitSync.md-Push-Sync",
    },
    body: form.toString(),
  });
  if (!response.ok) throw new Error(`GitHubOAuthExchange:${response.status}`);
  const body = await response.json() as Record<string, unknown>;
  if (typeof body.access_token !== "string" || body.access_token.length < 16 || body.access_token.length > 512) {
    throw new Error(typeof body.error === "string" ? "GitHubOAuthRejected" : "GitHubOAuthResponseInvalid");
  }
  return body.access_token;
}

interface GitHubAccount {
  id: number;
  login: string;
  type: string;
}

function parseGitHubAccount(value: unknown): GitHubAccount | null {
  if (typeof value !== "object" || value === null) return null;
  const account = value as Record<string, unknown>;
  if (!validInstallationID(account.id)) return null;
  if (typeof account.login !== "string" || !LOGIN_RE.test(account.login)) return null;
  if (typeof account.type !== "string" || !/^[A-Za-z][A-Za-z ]{0,31}$/.test(account.type)) return null;
  return { id: account.id, login: account.login, type: account.type };
}

async function authenticatedUser(userToken: string): Promise<GitHubAccount> {
  const response = await fetch("https://api.github.com/user", { headers: githubHeaders(userToken) });
  if (!response.ok) throw new Error(`GitHubUserLookup:${response.status}`);
  const user = parseGitHubAccount(await response.json());
  if (!user || user.type !== "User") throw new Error("GitHubUserResponseInvalid");
  return user;
}

/**
 * GitHub explicitly warns that setup_url installation_id is spoofable. Bind
 * only after the authorizing user proves personal ownership or active
 * organization-owner authority for the exact installation.
 */
export async function proveInstallationAdministrator(
  env: GitHubAppEnv,
  installationID: number,
  userToken: string,
): Promise<LinkedGitHubInstallation> {
  const user = await authenticatedUser(userToken);
  const installation = await fetchInstallationMetadata(env, installationID, user.id);
  if (installation.accountType === "User") {
    if (installation.accountID !== user.id) throw new Error("GitHubInstallationAdministratorRequired");
    return installation;
  }
  if (installation.accountType !== "Organization") throw new Error("GitHubInstallationAccountUnsupported");

  const response = await fetch(
    `https://api.github.com/user/memberships/orgs/${encodeURIComponent(installation.accountLogin)}`,
    { headers: githubHeaders(userToken) },
  );
  if (!response.ok) throw new Error(`GitHubMembershipLookup:${response.status}`);
  const membership = await response.json() as Record<string, unknown>;
  if (membership.state !== "active" || membership.role !== "admin"
      || nestedNumericID(membership.organization) !== installation.accountID
      || nestedNumericID(membership.user) !== user.id) {
    throw new Error("GitHubInstallationAdministratorRequired");
  }
  return installation;
}

async function installationToken(env: GitHubAppEnv, installationID: number): Promise<string> {
  const jwt = await githubAppJWT(env);
  const response = await fetch(`https://api.github.com/app/installations/${installationID}/access_tokens`, {
    method: "POST",
    headers: { ...githubHeaders(jwt), "content-type": "application/json" },
    body: JSON.stringify({ permissions: { members: "read" } }),
  });
  if (!response.ok) throw new Error(`GitHubInstallationToken:${response.status}`);
  const token = (await response.json() as Record<string, unknown>).token;
  if (typeof token !== "string" || token.length < 16 || token.length > 512) {
    throw new Error("GitHubInstallationTokenInvalid");
  }
  return token;
}

async function revokeInstallationToken(token: string): Promise<void> {
  const response = await fetch("https://api.github.com/installation/token", {
    method: "DELETE",
    headers: githubHeaders(token),
  });
  if (!response.ok && response.status !== 404) throw new Error(`GitHubInstallationTokenRevocation:${response.status}`);
}

/** Revalidates a previously proved owner without retaining either API token. */
export async function revalidateInstallationAdministrator(
  env: GitHubAppEnv,
  linked: LinkedGitHubInstallation,
): Promise<LinkedGitHubInstallation> {
  const current = await fetchInstallationMetadata(env, linked.id, linked.authorizingUserID);
  if (current.status !== "active" || current.accountID !== linked.accountID
      || current.accountType !== linked.accountType) {
    throw new Error("GitHubInstallationAdministratorRequired");
  }
  if (current.accountType === "User") {
    if (current.accountID !== linked.authorizingUserID) {
      throw new Error("GitHubInstallationAdministratorRequired");
    }
    return { ...current, connectedAt: linked.connectedAt };
  }
  if (current.accountType !== "Organization") throw new Error("GitHubInstallationAccountUnsupported");

  const token = await installationToken(env, current.id);
  try {
    const userResponse = await fetch(`https://api.github.com/user/${linked.authorizingUserID}`, {
      headers: githubHeaders(token),
    });
    if (!userResponse.ok) throw new Error(`GitHubInstallationUserLookup:${userResponse.status}`);
    const user = parseGitHubAccount(await userResponse.json());
    if (!user || user.type !== "User" || user.id !== linked.authorizingUserID) {
      throw new Error("GitHubInstallationAdministratorRequired");
    }
    const membershipResponse = await fetch(
      `https://api.github.com/orgs/${encodeURIComponent(current.accountLogin)}/memberships/${encodeURIComponent(user.login)}`,
      { headers: githubHeaders(token) },
    );
    if (!membershipResponse.ok) throw new Error(`GitHubInstallationMembershipLookup:${membershipResponse.status}`);
    const membership = await membershipResponse.json() as Record<string, unknown>;
    if (membership.state !== "active" || membership.role !== "admin"
        || nestedNumericID(membership.organization) !== current.accountID
        || nestedNumericID(membership.user) !== user.id) {
      throw new Error("GitHubInstallationAdministratorRequired");
    }
  } finally {
    try {
      await revokeInstallationToken(token);
    } catch {
      // The installation token is never stored and expires after one hour.
    }
  }
  return { ...current, connectedAt: linked.connectedAt };
}

export async function revokeGitHubUserToken(env: GitHubAppEnv, userToken: string): Promise<void> {
  const credentials = new TextEncoder().encode(`${env.GITHUB_APP_CLIENT_ID}:${env.GITHUB_APP_CLIENT_SECRET}`);
  let raw = "";
  for (const byte of credentials) raw += String.fromCharCode(byte);
  const response = await fetch(
    `https://api.github.com/applications/${encodeURIComponent(env.GITHUB_APP_CLIENT_ID)}/token`,
    {
      method: "DELETE",
      headers: {
        accept: "application/vnd.github+json",
        authorization: `Basic ${btoa(raw)}`,
        "content-type": "application/json",
        "user-agent": "GitSync.md-Push-Sync",
        "x-github-api-version": "2022-11-28",
      },
      body: JSON.stringify({ access_token: userToken }),
    },
  );
  if (!response.ok && response.status !== 404) throw new Error(`GitHubTokenRevocation:${response.status}`);
}

export function installationIDFrom(value: unknown): number | null {
  if (typeof value === "number" && validInstallationID(value)) return value;
  if (typeof value !== "string" || !/^[1-9][0-9]{0,15}$/.test(value)) return null;
  const parsed = Number(value);
  return validInstallationID(parsed) ? parsed : null;
}
