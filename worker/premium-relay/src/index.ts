import {
  exactKeys, HttpError, integer, json, log, noContent, opaqueId, positiveSetting,
  randomToken, sha256, strictJson, text, uuid,
} from "./core";
import { verifyNotification, verifyTransaction } from "./verifier";
import {
  exchangeGitHubUserCode, getInstallation, proveInstallationAdministrator, proveRepository,
  revalidateInstallationAdministrator, revokeGitHubUserToken, verifyWebhook,
} from "./github";
import { sendApns } from "./apns";
import type { Env, OutboxMessage } from "./types";

const currentTime = () => Date.now();
let beforeInvalidTokenTombstone: (() => Promise<void>) | undefined;
let beforeEntitlementAuthorizationBatch: (() => Promise<void>) | undefined;
let beforeDeviceRegistrationBatch: (() => Promise<void>) | undefined;
let beforeDeviceDeletionBatch: (() => Promise<void>) | undefined;
let beforeGitHubLinkCompletionBatch: (() => Promise<void>) | undefined;
let beforeEnrollmentBatch: (() => Promise<void>) | undefined;
let proveRepositoryAccess = proveRepository;
let revalidateInstallationAdmin = revalidateInstallationAdministrator;
export function setBeforeInvalidTokenTombstoneForTesting(hook?: () => Promise<void>): void {
  beforeInvalidTokenTombstone = hook;
}
export function setBeforeEntitlementAuthorizationBatchForTesting(hook?: () => Promise<void>): void {
  beforeEntitlementAuthorizationBatch = hook;
}
export function setBeforeDeviceRegistrationBatchForTesting(hook?: () => Promise<void>): void {
  beforeDeviceRegistrationBatch = hook;
}
export function setBeforeDeviceDeletionBatchForTesting(hook?: () => Promise<void>): void {
  beforeDeviceDeletionBatch = hook;
}
export function setBeforeGitHubLinkCompletionBatchForTesting(hook?: () => Promise<void>): void {
  beforeGitHubLinkCompletionBatch = hook;
}
export function setBeforeEnrollmentBatchForTesting(hook?: () => Promise<void>): void {
  beforeEnrollmentBatch = hook;
}
export function setProveRepositoryForTesting(hook?: typeof proveRepository): void {
  proveRepositoryAccess = hook ?? proveRepository;
}
export function setRevalidateInstallationAdministratorForTesting(hook?: typeof revalidateInstallationAdministrator): void {
  revalidateInstallationAdmin = hook ?? revalidateInstallationAdministrator;
}
const IOS_APNS_TOKEN = /^[0-9a-f]{64}$/;
const BRANCH_INVALID = /[\x00-\x1f~^:?*[\\]/;

interface Authorization {
  installationID: string;
  tokenHash: string;
}

function killSwitchEnabled(env: Env): boolean {
  if (env.KILL_SWITCH === "true") return true;
  if (env.KILL_SWITCH === "false") return false;
  throw new HttpError(503, "invalid kill-switch configuration");
}

function requireEnabled(env: Env): void {
  if (killSwitchEnabled(env)) throw new HttpError(503, "temporarily disabled");
}

function nonPlaceholder(value: string, name: string, maximum = 4096): string {
  const normalized = value?.trim();
  if (!normalized || normalized.length > maximum || /replace-with|example\.com|^0+$|^TEAMID|^KEYID/i.test(normalized)) {
    throw new HttpError(503, `invalid ${name} configuration`);
  }
  return normalized;
}

function requireRelayConfiguration(env: Env): void {
  killSwitchEnabled(env);
  nonPlaceholder(env.BUNDLE_ID, "bundle ID", 256);
  if (!env.PRODUCT_IDS?.split(",").map(value => value.trim()).filter(Boolean).length) throw new HttpError(503, "invalid product configuration");
  configuredEnvironments(env);
  nonPlaceholder(env.GITHUB_APP_ID, "GitHub App ID", 32);
  nonPlaceholder(env.GITHUB_WEBHOOK_SECRET, "GitHub webhook secret", 4096);
  nonPlaceholder(env.GITHUB_APP_PRIVATE_KEY, "GitHub App private key", 100_000);
  nonPlaceholder(env.APNS_PRIVATE_KEY, "APNs private key", 100_000);
  nonPlaceholder(env.APNS_TEAM_ID, "APNs team ID", 64);
  nonPlaceholder(env.APNS_KEY_ID, "APNs key ID", 64);
  nonPlaceholder(env.APNS_TOPIC, "APNs topic", 256);
}

function exactHttpsCallback(value: string, expectedPath: string): string {
  let callback: URL;
  try { callback = new URL(value); } catch { throw new HttpError(503, "link unavailable"); }
  if (callback.protocol !== "https:" || callback.username || callback.password || callback.search || callback.hash ||
      callback.pathname !== expectedPath) throw new HttpError(503, "link unavailable");
  return callback.toString();
}

function requireGitHubLinkConfiguration(env: Env): { setupCallback: string; authorizationCallback: string } {
  try {
    nonPlaceholder(env.GITHUB_APP_SLUG, "GitHub App slug", 128);
    nonPlaceholder(env.GITHUB_CLIENT_ID, "GitHub client ID", 256);
    nonPlaceholder(env.GITHUB_CLIENT_SECRET, "GitHub client secret", 4096);
    return {
      setupCallback: exactHttpsCallback(nonPlaceholder(env.GITHUB_CALLBACK_URL, "GitHub callback URL", 2048), "/v1/github/callback"),
      authorizationCallback: exactHttpsCallback(
        nonPlaceholder(env.GITHUB_AUTHORIZATION_CALLBACK_URL, "GitHub authorization callback URL", 2048),
        "/v1/github/authorize/callback",
      ),
    };
  } catch {
    throw new HttpError(503, "link unavailable");
  }
}

function requireExactCallbackRequest(request: Request, configured: string): void {
  const incoming = new URL(request.url);
  incoming.search = "";
  incoming.hash = "";
  if (incoming.toString() !== configured) throw new HttpError(400, "invalid callback");
}

function configuredEnvironments(env: Env): Set<string> {
  const values = env.APP_STORE_ENVIRONMENTS.split(",").map(value => value.trim()).filter(Boolean);
  if (!values.length) throw new Error("APP_STORE_ENVIRONMENTS is empty");
  return new Set(values);
}

async function authorize(request: Request, env: Env): Promise<Authorization> {
  const header = request.headers.get("authorization");
  if (!header?.startsWith("Bearer ") || header.length > 300 || header.length <= 7) throw new HttpError(401, "unauthorized");
  const tokenHash = await sha256(header.slice(7));
  const row = await env.DB.prepare(
    "SELECT installation_id FROM sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?",
  ).bind(tokenHash, currentTime()).first<{ installation_id: string }>();
  if (!row) throw new HttpError(401, "unauthorized");
  return { installationID: row.installation_id, tokenHash };
}

async function requireActiveEntitlement(installationID: string, env: Env): Promise<void> {
  const row = await env.DB.prepare(
    "SELECT 1 AS active FROM entitlements WHERE installation_id = ? AND revoked_at IS NULL AND expires_at > ? LIMIT 1",
  ).bind(installationID, currentTime()).first();
  if (!row) throw new HttpError(403, "active entitlement required");
}

async function putEntitlement(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  const body = await strictJson(request, 24_000);
  exactKeys(body, ["installation", "proof"]);
  if (typeof body.installation !== "object" || body.installation === null || Array.isArray(body.installation)) throw new HttpError(400, "invalid installation");
  if (typeof body.proof !== "object" || body.proof === null || Array.isArray(body.proof)) throw new HttpError(400, "invalid proof");
  const installation = body.installation as Record<string, unknown>;
  const proof = body.proof as Record<string, unknown>;
  exactKeys(installation, ["installationID", "bundleID", "appVersion"]);
  exactKeys(proof, ["productID", "transactionID", "originalTransactionID", "expirationDate", "environment", "signedTransaction"]);
  const installationID = uuid(installation.installationID);
  const bundleID = text(installation.bundleID, "bundle ID", 256);
  const appVersion = text(installation.appVersion, "app version", 64);
  const signedTransaction = text(proof.signedTransaction, "signed transaction", 20_000);
  const verified = await verifyTransaction(env.STOREKIT_VERIFIER, signedTransaction);
  const allowedProducts = new Set(env.PRODUCT_IDS.split(",").map(value => value.trim()).filter(Boolean));
  const proofExpiration = proof.expirationDate === null ? null : Date.parse(String(proof.expirationDate));
  if (
    verified.bundleId !== env.BUNDLE_ID || bundleID !== env.BUNDLE_ID ||
    !allowedProducts.has(verified.productId) || proof.productID !== verified.productId ||
    String(proof.transactionID) !== verified.transactionId ||
    String(proof.originalTransactionID) !== verified.originalTransactionId ||
    String(proof.environment).toLowerCase() !== verified.environment.toLowerCase() ||
    (proofExpiration !== null && proofExpiration !== verified.expiresAt) ||
    !configuredEnvironments(env).has(verified.environment) ||
    verified.appAccountToken?.toLowerCase() !== installationID.toLowerCase() ||
    verified.revokedAt !== null
  ) throw new HttpError(403, "entitlement not active");

  const current = currentTime();
  const retained = await env.DB.prepare(
    "SELECT expires_at,revoked_at,event_time FROM entitlements WHERE installation_id=? AND original_transaction_id=?",
  ).bind(installationID, verified.originalTransactionId).first<{ expires_at: number; revoked_at: number | null; event_time: number }>();
  // A verified StoreKit current entitlement may still expose the original
  // transaction expiration while Apple billing grace is represented by a
  // newer verified server notification. Preserve that later server expiry;
  // never let a stale transaction extend access by itself.
  const effectiveExpiresAt = retained && retained.revoked_at === null && retained.event_time > verified.eventTime
    ? Math.max(verified.expiresAt, retained.expires_at)
    : verified.expiresAt;
  if (effectiveExpiresAt <= current) throw new HttpError(403, "entitlement not active");

  const owner = await env.DB.prepare(
    "SELECT installation_id FROM entitlements WHERE original_transaction_id=? LIMIT 1",
  ).bind(verified.originalTransactionId).first<{ installation_id: string }>();
  if (owner && owner.installation_id !== installationID) {
    throw new HttpError(409, "subscription is already linked to another installation");
  }
  if (retained?.revoked_at !== null && retained?.revoked_at !== undefined) {
    throw new HttpError(403, "entitlement state is stale");
  }

  // Snapshot the installation deletion generation before writing. A concurrent
  // authenticated purge increments it; the conditional upsert/session insert
  // then fail closed instead of resurrecting a deleted installation.
  const priorInstallation = await env.DB.prepare(
    "SELECT deletion_generation,deleted_at FROM installations WHERE id=?",
  ).bind(installationID).first<{ deletion_generation: number; deleted_at: number | null }>();
  const explicitDeletion = await env.DB.prepare(
    "SELECT 1 AS deleted FROM installation_deletions WHERE installation_id=?",
  ).bind(installationID).first();
  if (explicitDeletion) throw new HttpError(409, "installation was explicitly deleted");
  const deletionGeneration = priorInstallation?.deletion_generation ?? 0;
  const canReactivate = priorInstallation?.deleted_at !== null && priorInstallation?.deleted_at !== undefined;
  const token = randomToken();
  const deletionToken = randomToken();
  const timestamp = currentTime();
  const expiresAt = Math.min(
    effectiveExpiresAt,
    timestamp + positiveSetting(env.SESSION_TTL_SECONDS, "SESSION_TTL_SECONDS", 90 * 86_400) * 1000,
  );
  await beforeEntitlementAuthorizationBatch?.();
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO installations(id,bundle_id,app_version,created_at,updated_at,deleted_at,deletion_generation) VALUES(?,?,?,?,?,NULL,?) " +
      "ON CONFLICT(id) DO UPDATE SET bundle_id=excluded.bundle_id,app_version=excluded.app_version,updated_at=excluded.updated_at,deleted_at=NULL " +
      "WHERE installations.deletion_generation=excluded.deletion_generation " +
      "AND (installations.deleted_at IS NULL OR (?=1 AND NOT EXISTS(SELECT 1 FROM installation_deletions WHERE installation_id=installations.id)))",
    ).bind(installationID, bundleID, appVersion, timestamp, timestamp, deletionGeneration, canReactivate ? 1 : 0),
    env.DB.prepare(
      "INSERT INTO entitlements(installation_id,original_transaction_id,product_id,environment,expires_at,revoked_at,event_time,verified_at,updated_at) " +
      "SELECT ?,?,?,?,?,NULL,?,?,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?) " +
      "ON CONFLICT(installation_id,original_transaction_id) DO UPDATE SET " +
      "product_id=excluded.product_id,environment=excluded.environment,expires_at=excluded.expires_at,revoked_at=NULL,event_time=excluded.event_time,verified_at=excluded.verified_at,updated_at=excluded.updated_at " +
      "WHERE entitlements.event_time<=excluded.event_time AND entitlements.revoked_at IS NULL",
    ).bind(installationID, verified.originalTransactionId, verified.productId, verified.environment, effectiveExpiresAt,
      Math.max(verified.eventTime, retained?.event_time ?? 0), timestamp, timestamp, installationID, deletionGeneration),
    env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE installation_id = ? AND revoked_at IS NULL").bind(timestamp, installationID),
    env.DB.prepare(
      "INSERT INTO installation_deletion_keys(installation_id,key_hash,created_at) " +
      "SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?) " +
      "ON CONFLICT(key_hash) DO NOTHING",
    ).bind(installationID, await sha256(deletionToken), timestamp, installationID, deletionGeneration),
    env.DB.prepare(
      "INSERT INTO sessions(token_hash,installation_id,expires_at,created_at) " +
      "SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?)",
    ).bind(await sha256(token), installationID, expiresAt, timestamp, installationID, deletionGeneration),
  ]);
  const session = await env.DB.prepare(
    "SELECT 1 AS created FROM sessions WHERE token_hash=? AND installation_id=? AND revoked_at IS NULL",
  ).bind(await sha256(token), installationID).first();
  if (!session) throw new HttpError(409, "installation changed during authorization");
  return json({ installationID, token, deletionToken, expiresAt: new Date(expiresAt).toISOString() });
}

function deterministicDeviceID(installationID: string, environment: string): Promise<string> {
  return sha256(`device\u001f${installationID}\u001f${environment}`).then(hash => `dev_${hash.slice(0, 36)}`);
}

async function putDevice(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  const auth = await authorize(request, env);
  await requireActiveEntitlement(auth.installationID, env);
  const body = await strictJson(request);
  exactKeys(body, ["installation", "token", "environment", "channels", "registrationGeneration"],
    ["installation", "token", "environment", "registrationGeneration"]);
  if (typeof body.installation !== "object" || body.installation === null || Array.isArray(body.installation)) throw new HttpError(400, "invalid installation");
  const installation = body.installation as Record<string, unknown>;
  exactKeys(installation, ["installationID", "bundleID", "appVersion"]);
  const installationID = uuid(installation.installationID);
  if (installationID !== auth.installationID) throw new HttpError(403, "cross-installation access denied");
  if (installation.bundleID !== env.BUNDLE_ID) throw new HttpError(400, "invalid bundle ID");
  text(installation.appVersion, "app version", 64);
  const token = text(body.token, "APNs token", 64).toLowerCase();
  if (!IOS_APNS_TOKEN.test(token)) throw new HttpError(400, "invalid APNs token");
  if (body.environment !== "sandbox" && body.environment !== "production") throw new HttpError(400, "invalid APNs environment");
  if (typeof body.registrationGeneration !== "number" || !Number.isSafeInteger(body.registrationGeneration) || body.registrationGeneration < 0) {
    throw new HttpError(400, "invalid registration generation");
  }
  const registrationGeneration = body.registrationGeneration as number;
  if (body.channels !== undefined) {
    if (!Array.isArray(body.channels)) throw new HttpError(400, "invalid channels");
    // Legacy clients still send their complete opaque-channel snapshot. Keep
    // syntax validation for wire compatibility, but enrollment rows now own
    // routing and device registration is constant-size for new clients.
    for (const channel of body.channels) opaqueId(channel, "channel");
  }
  const generation = await env.DB.prepare(
    "SELECT deletion_generation FROM installations WHERE id=? AND deleted_at IS NULL",
  ).bind(installationID).first<{ deletion_generation: number }>();
  if (!generation) throw new HttpError(409, "installation changed");
  const deviceID = await deterministicDeviceID(installationID, body.environment);
  const timestamp = currentTime();
  await beforeDeviceRegistrationBatch?.();
  await env.DB.prepare(
    "INSERT INTO devices(id,installation_id,apns_token,apns_environment,created_at,updated_at,deleted_at,registration_generation) " +
    "SELECT ?,?,?,?,?,?,NULL,? WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?) " +
    "ON CONFLICT(id) DO UPDATE SET apns_token=excluded.apns_token,apns_environment=excluded.apns_environment,updated_at=excluded.updated_at,deleted_at=NULL,registration_generation=excluded.registration_generation " +
    "WHERE installation_id=excluded.installation_id AND excluded.registration_generation>=devices.registration_generation " +
    "AND EXISTS(SELECT 1 FROM installations WHERE id=excluded.installation_id AND deleted_at IS NULL AND deletion_generation=?)",
  ).bind(deviceID, installationID, token, body.environment, timestamp, timestamp, registrationGeneration,
    installationID, generation.deletion_generation, generation.deletion_generation).run();
  return json({ ok: true });
}

async function deleteDevice(request: Request, env: Env): Promise<Response> {
  const auth = await authorize(request, env);
  const body = await strictJson(request);
  exactKeys(body, ["installationID", "token", "environment", "maximumRegistrationGeneration"], ["installationID", "environment"]);
  const installationID = uuid(body.installationID);
  if (installationID !== auth.installationID) throw new HttpError(403, "cross-installation access denied");
  if (body.environment !== "sandbox" && body.environment !== "production") throw new HttpError(400, "invalid APNs environment");
  const token = body.token === null || body.token === undefined ? null : text(body.token, "APNs token", 64).toLowerCase();
  if (token !== null && !IOS_APNS_TOKEN.test(token)) throw new HttpError(400, "invalid APNs token");
  const maximumGeneration = body.maximumRegistrationGeneration === null || body.maximumRegistrationGeneration === undefined
    ? null
    : body.maximumRegistrationGeneration;
  if (maximumGeneration !== null &&
      (typeof maximumGeneration !== "number" || !Number.isSafeInteger(maximumGeneration) || maximumGeneration < 0)) {
    throw new HttpError(400, "invalid maximum registration generation");
  }
  const tokenClause = token === null ? "" : " AND apns_token = ?";
  const generationClause = maximumGeneration === null ? "" : " AND registration_generation <= ?";
  const bindings: unknown[] = [installationID, body.environment];
  if (token !== null) bindings.push(token);
  if (maximumGeneration !== null) bindings.push(maximumGeneration);
  const ids = await env.DB.prepare(
    `SELECT id,apns_token,registration_generation FROM devices WHERE installation_id = ? AND apns_environment = ? AND deleted_at IS NULL${tokenClause}${generationClause}`,
  ).bind(...bindings).all<{ id: string; apns_token: string; registration_generation: number }>();
  const timestamp = currentTime();
  await beforeDeviceDeletionBatch?.();
  if (ids.results.length) await env.DB.batch([
    ...ids.results.map(row => env.DB.prepare(
      "DELETE FROM device_channels WHERE device_id = ? AND EXISTS(SELECT 1 FROM devices WHERE id=? AND registration_generation=? " +
      "AND deleted_at IS NULL AND apns_token=?)",
    ).bind(row.id, row.id, row.registration_generation, row.apns_token)),
    ...ids.results.map(row => env.DB.prepare(
      "UPDATE devices SET deleted_at=?,apns_token='deleted-'||id,updated_at=? WHERE id=? AND registration_generation=? " +
      "AND deleted_at IS NULL AND apns_token=?",
    ).bind(timestamp, timestamp, row.id, row.registration_generation, row.apns_token)),
  ]);
  return noContent();
}

async function startGitHubLink(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  requireGitHubLinkConfiguration(env);
  const auth = await authorize(request, env);
  await requireActiveEntitlement(auth.installationID, env);
  const state = randomToken();
  const timestamp = currentTime();
  const expiresAt = timestamp + positiveSetting(env.LINK_STATE_TTL_SECONDS, "LINK_STATE_TTL_SECONDS", 3600) * 1000;
  const generation = await env.DB.prepare(
    "SELECT deletion_generation FROM installations WHERE id=? AND deleted_at IS NULL",
  ).bind(auth.installationID).first<{ deletion_generation: number }>();
  if (!generation) throw new HttpError(409, "installation changed");
  await env.DB.prepare("INSERT INTO github_link_states(state_hash,installation_id,deletion_generation,expires_at,created_at) VALUES(?,?,?,?,?)")
    .bind(await sha256(state), auth.installationID, generation.deletion_generation, expiresAt, timestamp).run();
  const url = new URL(`https://github.com/apps/${encodeURIComponent(env.GITHUB_APP_SLUG)}/installations/new`);
  url.searchParams.set("state", state);
  return json({ url: url.toString(), expiresAt: new Date(expiresAt).toISOString() });
}

function redirectToGitHubAuthorization(env: Env, state: string, callback: string): Response {
  const authorization = new URL("https://github.com/login/oauth/authorize");
  authorization.searchParams.set("client_id", env.GITHUB_CLIENT_ID);
  authorization.searchParams.set("redirect_uri", callback);
  authorization.searchParams.set("state", state);
  return new Response(null, {
    status: 302,
    headers: {
      "cache-control": "no-store",
      location: authorization.toString(),
      pragma: "no-cache",
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
    },
  });
}

async function completeGitHubSetup(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  const configuration = requireGitHubLinkConfiguration(env);
  requireExactCallbackRequest(request, configuration.setupCallback);
  const url = new URL(request.url);
  const allowed = new Set(["state", "installation_id", "setup_action"]);
  if ([...url.searchParams.keys()].some(key => !allowed.has(key)) || url.searchParams.getAll("setup_action").length > 1) {
    throw new HttpError(400, "invalid callback");
  }
  const states = url.searchParams.getAll("state");
  const installations = url.searchParams.getAll("installation_id");
  if (states.length !== 1 || installations.length !== 1) throw new HttpError(400, "invalid callback");
  const state = text(states[0], "state", 200);
  const githubInstallationID = Number(installations[0]);
  if (!Number.isSafeInteger(githubInstallationID) || githubInstallationID <= 0) throw new HttpError(400, "invalid callback");
  const stateHash = await sha256(state);
  const row = await env.DB.prepare(
    "SELECT s.installation_id,s.deletion_generation FROM github_link_states s JOIN installations i ON i.id=s.installation_id " +
    "WHERE s.state_hash=? AND s.consumed_at IS NULL AND s.expires_at>? AND i.deleted_at IS NULL AND i.deletion_generation=s.deletion_generation",
  ).bind(stateHash, currentTime()).first<{ installation_id: string; deletion_generation: number }>();
  if (!row) throw new HttpError(400, "invalid state");
  await getInstallation(env, githubInstallationID);
  const authorizationState = randomToken();
  const authorizationStateHash = await sha256(authorizationState);
  const timestamp = currentTime();
  const authorizationExpiresAt = timestamp + positiveSetting(env.LINK_STATE_TTL_SECONDS, "LINK_STATE_TTL_SECONDS", 3600) * 1000;
  const setupNonce = randomToken(16);
  const rotated = await env.DB.prepare(
    "UPDATE github_link_states SET consumed_at=?,consumed_nonce=?,github_installation_id=?,authorization_state_hash=?,authorization_expires_at=? " +
    "WHERE state_hash=? AND installation_id=? AND deletion_generation=? AND consumed_at IS NULL AND expires_at>? " +
    "AND EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?)",
  ).bind(timestamp, setupNonce, githubInstallationID, authorizationStateHash, authorizationExpiresAt,
    stateHash, row.installation_id, row.deletion_generation, timestamp,
    row.installation_id, row.deletion_generation).run();
  if (!rotated.meta.changes) throw new HttpError(409, "invalid state");
  return redirectToGitHubAuthorization(env, authorizationState, configuration.authorizationCallback);
}

function githubLinkCompletionPage(): Response {
  return new Response(
    "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Background Sync linked</title><body><p>Background Sync is linked.</p><p><a href=\"syncmd://assist-linked\">Return to GitSync.md</a></p></body></html>",
    {
      status: 200,
      headers: {
        "cache-control": "no-store",
        "content-security-policy": "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        "content-type": "text/html; charset=utf-8",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
        "x-frame-options": "DENY",
      },
    },
  );
}

async function completeGitHubAuthorization(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  const configuration = requireGitHubLinkConfiguration(env);
  requireExactCallbackRequest(request, configuration.authorizationCallback);
  const url = new URL(request.url);
  const allowed = new Set(["code", "state"]);
  if ([...url.searchParams.keys()].some(key => !allowed.has(key))) throw new HttpError(400, "invalid callback");
  const codes = url.searchParams.getAll("code");
  const states = url.searchParams.getAll("state");
  if (codes.length !== 1 || states.length !== 1) throw new HttpError(400, "invalid callback");
  const code = text(codes[0], "code", 1024);
  const stateHash = await sha256(text(states[0], "state", 200));
  const row = await env.DB.prepare(
    "SELECT s.installation_id,s.deletion_generation,s.github_installation_id FROM github_link_states s " +
    "JOIN installations i ON i.id=s.installation_id WHERE s.authorization_state_hash=? AND s.consumed_at IS NOT NULL " +
    "AND s.authorized_at IS NULL AND s.authorization_expires_at>? AND s.github_installation_id IS NOT NULL " +
    "AND i.deleted_at IS NULL AND i.deletion_generation=s.deletion_generation",
  ).bind(stateHash, currentTime()).first<{
    installation_id: string; deletion_generation: number; github_installation_id: number;
  }>();
  if (!row) throw new HttpError(400, "invalid state");

  let userToken: string | undefined;
  try {
    userToken = await exchangeGitHubUserCode(env, code);
    const administrator = await proveInstallationAdministrator(env, row.github_installation_id, userToken);
    const timestamp = currentTime();
    const authorizationNonce = randomToken(16);
    await beforeGitHubLinkCompletionBatch?.();
    const [, linked] = await env.DB.batch([
      env.DB.prepare(
        "UPDATE github_link_states SET authorized_at=?,authorized_nonce=? WHERE authorization_state_hash=? " +
        "AND installation_id=? AND deletion_generation=? AND github_installation_id=? AND authorized_at IS NULL " +
        "AND authorization_expires_at>? AND EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?)",
      ).bind(timestamp, authorizationNonce, stateHash, row.installation_id, row.deletion_generation,
        row.github_installation_id, timestamp, row.installation_id, row.deletion_generation),
      env.DB.prepare(
        "INSERT INTO github_installations(github_installation_id,installation_id,linked_at,deleted_at,authorizing_user_id) " +
        "SELECT github_installation_id,installation_id,?,NULL,? FROM github_link_states WHERE authorization_state_hash=? " +
        "AND installation_id=? AND deletion_generation=? AND github_installation_id=? AND authorized_at=? AND authorized_nonce=? " +
        "AND EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?) " +
        "AND NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=github_link_states.github_installation_id) " +
        "ON CONFLICT(github_installation_id,installation_id) DO UPDATE SET linked_at=excluded.linked_at,deleted_at=NULL,authorizing_user_id=excluded.authorizing_user_id " +
        "WHERE NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=excluded.github_installation_id) " +
        "AND EXISTS(SELECT 1 FROM github_link_states WHERE authorization_state_hash=? AND installation_id=excluded.installation_id " +
        "AND deletion_generation=? AND github_installation_id=excluded.github_installation_id AND authorized_at=? AND authorized_nonce=?)",
      ).bind(timestamp, administrator.userId, stateHash, row.installation_id, row.deletion_generation, row.github_installation_id,
        timestamp, authorizationNonce, row.installation_id, row.deletion_generation,
        stateHash, row.deletion_generation, timestamp, authorizationNonce),
    ]);
    if (!linked?.meta.changes) throw new HttpError(409, "invalid state");
    return githubLinkCompletionPage();
  } finally {
    if (userToken) {
      try { await revokeGitHubUserToken(env, userToken); }
      catch { log("github_user_token_revocation_failed"); }
    }
  }
}

async function githubLinkStatus(request: Request, env: Env): Promise<Response> {
  const auth = await authorize(request, env);
  const rows = await env.DB.prepare(
    "SELECT g.github_installation_id AS githubInstallationID,g.linked_at AS linkedAt,g.authorizing_user_id AS authorizingUserID " +
    "FROM github_installations g WHERE g.installation_id=? AND g.deleted_at IS NULL " +
    "AND NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=g.github_installation_id)",
  ).bind(auth.installationID).all<{ githubInstallationID: number; linkedAt: number; authorizingUserID: number | null }>();
  for (const row of rows.results) {
    if (row.authorizingUserID === null) throw new HttpError(409, "GitHub installation must be relinked");
    await revalidateInstallationAdmin(env, row.githubInstallationID, row.authorizingUserID);
  }
  return json({ installations: rows.results.map(row => ({
    githubInstallationID: row.githubInstallationID,
    linkedAt: new Date(row.linkedAt).toISOString(),
  })) });
}

function validBranch(value: unknown): string {
  const branch = text(value, "branch", 255);
  if (branch.startsWith("refs/") || BRANCH_INVALID.test(branch) || branch.includes("..") || branch.endsWith(".") || branch.endsWith("/") || branch.startsWith("/")) {
    throw new HttpError(400, "invalid branch");
  }
  return branch;
}

async function createEnrollment(request: Request, env: Env): Promise<Response> {
  requireEnabled(env);
  const auth = await authorize(request, env);
  await requireActiveEntitlement(auth.installationID, env);
  const body = await strictJson(request);
  exactKeys(body, ["githubInstallationID", "repositoryID", "branch"]);
  const githubInstallationID = integer(body.githubInstallationID, "installation ID");
  const repositoryID = integer(body.repositoryID, "repository ID");
  const branch = validBranch(body.branch);
  const linked = await env.DB.prepare(
    "SELECT authorizing_user_id AS authorizingUserID FROM github_installations g " +
    "WHERE github_installation_id=? AND installation_id=? AND deleted_at IS NULL AND authorizing_user_id IS NOT NULL " +
    "AND NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=g.github_installation_id)",
  ).bind(githubInstallationID, auth.installationID).first<{ authorizingUserID: number }>();
  if (!linked) throw new HttpError(403, "GitHub installation not linked");
  await revalidateInstallationAdmin(env, githubInstallationID, linked.authorizingUserID);
  await proveRepositoryAccess(env, githubInstallationID, repositoryID);
  const generation = await env.DB.prepare(
    "SELECT deletion_generation FROM installations WHERE id=? AND deleted_at IS NULL",
  ).bind(auth.installationID).first<{ deletion_generation: number }>();
  if (!generation) throw new HttpError(409, "installation changed");
  const proposedChannel = randomToken(24);
  await beforeEnrollmentBatch?.();
  try {
    const inserted = await env.DB.prepare(
      "INSERT INTO repo_enrollments(channel,installation_id,github_installation_id,repository_id,branch,created_at,deleted_at) " +
      "SELECT ?,?,?,?,?,?,NULL WHERE EXISTS(SELECT 1 FROM installations WHERE id=? AND deleted_at IS NULL AND deletion_generation=?) " +
      "AND EXISTS(SELECT 1 FROM github_installations g WHERE g.github_installation_id=? AND g.installation_id=? AND g.deleted_at IS NULL " +
      "AND g.authorizing_user_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=g.github_installation_id)) " +
      "ON CONFLICT(installation_id,repository_id,branch) DO UPDATE SET github_installation_id=excluded.github_installation_id,created_at=excluded.created_at,deleted_at=NULL " +
      "WHERE repo_enrollments.deleted_at IS NOT NULL AND EXISTS(SELECT 1 FROM installations WHERE id=excluded.installation_id AND deleted_at IS NULL AND deletion_generation=?) " +
      "AND EXISTS(SELECT 1 FROM github_installations g WHERE g.github_installation_id=excluded.github_installation_id " +
      "AND g.installation_id=excluded.installation_id AND g.deleted_at IS NULL AND g.authorizing_user_id IS NOT NULL " +
      "AND NOT EXISTS(SELECT 1 FROM github_installation_tombstones t WHERE t.github_installation_id=g.github_installation_id))",
    ).bind(proposedChannel, auth.installationID, githubInstallationID, repositoryID, branch, currentTime(),
      auth.installationID, generation.deletion_generation, githubInstallationID, auth.installationID,
      generation.deletion_generation).run();
    const enrollment = await env.DB.prepare(
      "SELECT channel,github_installation_id AS githubInstallationID FROM repo_enrollments " +
      "WHERE installation_id=? AND repository_id=? AND branch=? AND deleted_at IS NULL",
    ).bind(auth.installationID, repositoryID, branch).first<{ channel: string; githubInstallationID: number }>();
    if (!enrollment) throw new HttpError(409, "installation changed");
    if (!inserted.meta.changes && enrollment.githubInstallationID !== githubInstallationID) {
      throw new HttpError(409, "repository branch already enrolled");
    }
    // A byte-for-byte retry after a committed response loss returns the
    // existing opaque channel rather than stranding the local enrollment.
    return json({
      channel: enrollment.channel,
      githubInstallationID,
      repositoryID,
      branch,
    }, inserted.meta.changes ? 201 : 200);
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(409, "repository branch already enrolled");
  }
}

async function deleteInstallation(request: Request, env: Env): Promise<Response> {
  const deletionHeader = request.headers.get("x-installation-deletion-token");
  if (!deletionHeader || deletionHeader.length > 300) throw new HttpError(401, "deletion authorization required");
  const deletionHash = await sha256(deletionHeader);
  const deletionKey = await env.DB.prepare(
    "SELECT installation_id FROM installation_deletion_keys WHERE key_hash=?",
  ).bind(deletionHash).first<{ installation_id: string }>();
  const completed = await env.DB.prepare(
    "SELECT installation_id FROM installation_deletions WHERE token_hash=?",
  ).bind(deletionHash).first<{ installation_id: string }>();
  if (!deletionKey && completed) return noContent();
  if (!deletionKey) throw new HttpError(401, "deletion authorization required");
  const installationID = deletionKey.installation_id;
  const timestamp = currentTime();
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO installation_deletions(installation_id,token_hash,requested_at) " +
      "SELECT installation_id,key_hash,? FROM installation_deletion_keys WHERE installation_id=? " +
      "ON CONFLICT(token_hash) DO NOTHING",
    ).bind(timestamp, installationID),
    env.DB.prepare("DELETE FROM installation_deletion_keys WHERE installation_id=?").bind(installationID),
    env.DB.prepare("DELETE FROM device_channels WHERE device_id IN (SELECT id FROM devices WHERE installation_id=?)").bind(installationID),
    env.DB.prepare("UPDATE devices SET deleted_at=?,apns_token='deleted-'||id,updated_at=? WHERE installation_id=?").bind(timestamp, timestamp, installationID),
    env.DB.prepare("UPDATE repo_enrollments SET deleted_at=? WHERE installation_id=? AND deleted_at IS NULL").bind(timestamp, installationID),
    env.DB.prepare("UPDATE github_installations SET deleted_at=? WHERE installation_id=? AND deleted_at IS NULL").bind(timestamp, installationID),
    env.DB.prepare("UPDATE sessions SET revoked_at=? WHERE installation_id=? AND revoked_at IS NULL").bind(timestamp, installationID),
    env.DB.prepare("UPDATE entitlements SET revoked_at=COALESCE(revoked_at,?),updated_at=? WHERE installation_id=?").bind(timestamp, timestamp, installationID),
    env.DB.prepare("UPDATE installations SET deleted_at=?,updated_at=?,deletion_generation=deletion_generation+1 WHERE id=?").bind(timestamp, timestamp, installationID),
  ]);
  return noContent();
}

async function deleteEnrollment(request: Request, env: Env, channel: string): Promise<Response> {
  const auth = await authorize(request, env);
  const owned = await env.DB.prepare(
    "SELECT deleted_at AS deletedAt FROM repo_enrollments WHERE channel=? AND installation_id=?",
  ).bind(channel, auth.installationID).first<{ deletedAt: number | null }>();
  if (!owned) throw new HttpError(404, "enrollment not found");
  // Preserve cross-installation privacy while making a retry after a committed
  // response loss idempotent for the owning installation.
  if (owned.deletedAt !== null) return noContent();
  const timestamp = currentTime();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM device_channels WHERE channel=?").bind(channel),
    env.DB.prepare("UPDATE repo_enrollments SET deleted_at=? WHERE channel=? AND installation_id=? AND deleted_at IS NULL").bind(timestamp, channel, auth.installationID),
  ]);
  return noContent();
}

async function githubWebhook(request: Request, env: Env): Promise<Response> {
  killSwitchEnabled(env);
  const declaredLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > 1_000_000) throw new HttpError(413, "body too large");
  const raw = await request.arrayBuffer();
  if (raw.byteLength > 1_000_000) throw new HttpError(413, "body too large");
  if (!await verifyWebhook(env.GITHUB_WEBHOOK_SECRET, raw, request.headers.get("x-hub-signature-256"))) throw new HttpError(401, "invalid signature");
  const event = request.headers.get("x-github-event");
  if (event !== "push" && event !== "installation") throw new HttpError(400, "unsupported event");
  const deliveryID = request.headers.get("x-github-delivery");
  if (!deliveryID || deliveryID.length > 128 || !/^[A-Za-z0-9-]+$/.test(deliveryID)) throw new HttpError(400, "invalid delivery");
  let payload: unknown;
  try { payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(raw)); }
  catch { throw new HttpError(400, "invalid JSON"); }
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) throw new HttpError(400, "invalid payload");
  const record = payload as Record<string, unknown>;
  const timestamp = currentTime();
  const retentionUntil = timestamp + positiveSetting(env.WEBHOOK_RETENTION_DAYS, "WEBHOOK_RETENTION_DAYS", 365) * 86_400_000;

  if (event === "installation") {
    const allowed = new Set(["action", "installation", "repositories", "requester", "sender", "enterprise"]);
    if (Object.keys(record).some(key => !allowed.has(key))) {
      throw new HttpError(400, "unsupported installation event");
    }
    if (typeof record.installation !== "object" || record.installation === null || Array.isArray(record.installation)) {
      throw new HttpError(400, "invalid installation event");
    }
    const githubInstallationID = integer((record.installation as Record<string, unknown>).id, "installation ID");
    // Benign lifecycle events carry no relay side effects, but they must be
    // acknowledged so GitHub does not flag the hook (and its install UI) as
    // failing. Only removals mutate routing below.
    if (record.action === "created" || record.action === "new_permissions_accepted" || record.action === "suspend" || record.action === "unsuspend") {
      return json({ accepted: true }, 202);
    }
    if (record.action !== "deleted") throw new HttpError(400, "unsupported installation event");
    // GitHub App removal is a security/deletion event, not new admission. It
    // remains effective while push admission is kill-switched. The durable,
    // globally keyed tombstone makes either ordering with delayed link or
    // enrollment admission safe; new GitHub installation IDs remain usable.
    await env.DB.batch([
      env.DB.prepare(
        "INSERT OR IGNORE INTO webhook_deliveries(delivery_id,event,repository_id,received_at,retention_until) VALUES(?,?,?,?,?)",
      ).bind(deliveryID, event, githubInstallationID, timestamp, retentionUntil),
      env.DB.prepare(
        "INSERT INTO github_installation_tombstones(github_installation_id,deleted_at) " +
        "SELECT ?,? WHERE EXISTS(SELECT 1 FROM webhook_deliveries WHERE delivery_id=? AND event='installation' AND repository_id=?) " +
        "ON CONFLICT(github_installation_id) DO NOTHING",
      ).bind(githubInstallationID, timestamp, deliveryID, githubInstallationID),
      env.DB.prepare(
        "DELETE FROM device_channels WHERE channel IN (SELECT channel FROM repo_enrollments WHERE github_installation_id=? AND deleted_at IS NULL) " +
        "AND EXISTS(SELECT 1 FROM webhook_deliveries WHERE delivery_id=? AND event='installation' AND repository_id=?)",
      ).bind(githubInstallationID, deliveryID, githubInstallationID),
      env.DB.prepare(
        "UPDATE repo_enrollments SET deleted_at=? WHERE github_installation_id=? AND deleted_at IS NULL " +
        "AND EXISTS(SELECT 1 FROM webhook_deliveries WHERE delivery_id=? AND event='installation' AND repository_id=?)",
      ).bind(timestamp, githubInstallationID, deliveryID, githubInstallationID),
      env.DB.prepare(
        "UPDATE github_installations SET deleted_at=? WHERE github_installation_id=? AND deleted_at IS NULL " +
        "AND EXISTS(SELECT 1 FROM webhook_deliveries WHERE delivery_id=? AND event='installation' AND repository_id=?)",
      ).bind(timestamp, githubInstallationID, deliveryID, githubInstallationID),
    ]);
    return json({ accepted: true }, 202);
  }

  if (killSwitchEnabled(env)) return json({ accepted: true }, 202);
  const allowed = new Set(["ref", "repository", "deleted", "created", "forced", "base_ref", "before", "after", "compare", "commits", "head_commit", "pusher", "sender", "installation", "organization", "enterprise"]);
  if (Object.keys(record).some(key => !allowed.has(key))) throw new HttpError(400, "unexpected webhook field");
  if (typeof record.repository !== "object" || record.repository === null || Array.isArray(record.repository)) throw new HttpError(400, "invalid repository");
  const repository = record.repository as Record<string, unknown>;
  const repositoryID = integer(repository.id, "repository ID");
  const ref = text(record.ref, "ref", 300);
  const branch = ref.startsWith("refs/heads/") ? ref.slice(11) : null;
  if (!branch || record.deleted === true) return json({ accepted: true }, 202);
  await env.DB.batch([
    env.DB.prepare(
      "INSERT OR IGNORE INTO webhook_deliveries(delivery_id,event,repository_id,received_at,retention_until) VALUES(?,?,?,?,?)",
    ).bind(deliveryID, event, repositoryID, timestamp, retentionUntil),
    env.DB.prepare(
      "INSERT OR IGNORE INTO outbox(id,delivery_id,channel,hint,created_at,retention_until) " +
      "SELECT 'out_'||lower(hex(randomblob(16))), ?, channel, 'evt_'||lower(hex(randomblob(16))), ?, ? " +
      "FROM repo_enrollments WHERE repository_id=? AND branch=? AND deleted_at IS NULL " +
      "AND EXISTS(SELECT 1 FROM webhook_deliveries WHERE delivery_id=? AND repository_id=? AND event='push')",
    ).bind(deliveryID, timestamp, retentionUntil, repositoryID, branch, deliveryID, repositoryID),
  ]);
  // Dispatch this delivery immediately. The scheduled global dispatcher below
  // autonomously recovers committed rows even if GitHub never retries.
  await dispatchPendingOutbox(env, deliveryID);
  await env.DB.prepare("UPDATE webhook_deliveries SET completed_at=? WHERE delivery_id=?").bind(currentTime(), deliveryID).run();
  return json({ accepted: true }, 202);
}

const SUPPORTED_TRANSACTION_NOTIFICATION_TYPES = new Set([
  "DID_CHANGE_RENEWAL_PREF", "DID_CHANGE_RENEWAL_STATUS", "DID_FAIL_TO_RENEW", "DID_RENEW",
  "EXPIRED", "GRACE_PERIOD_EXPIRED", "OFFER_REDEEMED", "PRICE_INCREASE", "REFUND",
  "REFUND_DECLINED", "RENEWAL_EXTENDED", "REVOKE", "SUBSCRIBED",
]);
const SUPPORTED_NON_TRANSACTION_NOTIFICATION_TYPES = new Set(["TEST", "RENEWAL_EXTENSION"]);
const REVOCATION_NOTIFICATION_TYPES = new Set(["REFUND", "REVOKE"]);
const ACCESS_END_NOTIFICATION_TYPES = new Set(["EXPIRED", "GRACE_PERIOD_EXPIRED", "REFUND", "REVOKE"]);

async function appStoreNotification(request: Request, env: Env): Promise<Response> {
  const body = await strictJson(request, 200_000);
  exactKeys(body, ["signedPayload"]);
  const verified = await verifyNotification(env.STOREKIT_VERIFIER, text(body.signedPayload, "signed payload", 190_000));
  const hasTransaction = verified.originalTransactionId !== null;
  const gracePeriod = verified.notificationType === "DID_FAIL_TO_RENEW" && verified.subtype === "GRACE_PERIOD";
  if (SUPPORTED_NON_TRANSACTION_NOTIFICATION_TYPES.has(verified.notificationType)) {
    if (hasTransaction || verified.expiresAt !== null || verified.revokedAt !== null || verified.gracePeriodExpiresAt !== null) {
      throw new HttpError(400, "notification state mismatch");
    }
  } else {
    if (!SUPPORTED_TRANSACTION_NOTIFICATION_TYPES.has(verified.notificationType)) throw new HttpError(400, "unsupported notification type");
    if (!hasTransaction) throw new HttpError(400, "notification transaction required");
  }
  if (gracePeriod !== (verified.gracePeriodExpiresAt !== null)) throw new HttpError(400, "notification state mismatch");
  if (gracePeriod && verified.gracePeriodExpiresAt! <= verified.eventTime) throw new HttpError(400, "notification state mismatch");
  const revocation = REVOCATION_NOTIFICATION_TYPES.has(verified.notificationType);
  const accessEnded = ACCESS_END_NOTIFICATION_TYPES.has(verified.notificationType);
  if (revocation !== (verified.revokedAt !== null)) throw new HttpError(400, "notification state mismatch");
  if (accessEnded && !revocation && verified.expiresAt !== null && verified.expiresAt > verified.eventTime) throw new HttpError(400, "notification state mismatch");
  const timestamp = currentTime();
  const retentionUntil = timestamp + positiveSetting(env.NOTIFICATION_RETENTION_DAYS, "NOTIFICATION_RETENTION_DAYS", 365) * 86_400_000;
  const existing = await env.DB.prepare(
    "SELECT processed_at FROM app_store_notifications WHERE notification_uuid=?",
  ).bind(verified.notificationUUID).first<{ processed_at: number | null }>();
  if (existing?.processed_at !== null && existing?.processed_at !== undefined) {
    return json({ accepted: true }, 202);
  }

  // The pending dedupe row, all entitlement/access mutations, and the final
  // processed marker share one D1 batch transaction. A crash cannot leave a
  // UUID permanently acknowledged while its revocation is unapplied; an
  // existing pending row is intentionally retried.
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(
      "INSERT OR IGNORE INTO app_store_notifications(notification_uuid,notification_type,original_transaction_id,received_at,event_time,processed_at,retention_until) VALUES(?,?,?,?,?,NULL,?)",
    ).bind(verified.notificationUUID, verified.notificationType, verified.originalTransactionId, timestamp, verified.eventTime, retentionUntil),
  ];
  if (verified.originalTransactionId) {
    statements.push(env.DB.prepare(
      "UPDATE entitlements SET expires_at=COALESCE(?,expires_at),revoked_at=?,event_time=?,updated_at=? WHERE original_transaction_id=? AND event_time<=?",
    ).bind(gracePeriod ? verified.gracePeriodExpiresAt : verified.expiresAt, verified.revokedAt,
      verified.eventTime, timestamp, verified.originalTransactionId, verified.eventTime));
    if (accessEnded) {
      // Every destructive statement is conditioned on the entitlement row
      // still representing this exact signed access-ending event. A newer
      // renewal that wins first makes these statements no-ops.
      const matchPredicate = revocation
        ? "original_transaction_id=? AND revoked_at=? AND event_time=?"
        : "original_transaction_id=? AND revoked_at IS NULL AND expires_at<=? AND event_time=?";
      const matchingAccessEnd = `SELECT installation_id FROM entitlements WHERE ${matchPredicate}`;
      const matchBindings = revocation
        ? [verified.originalTransactionId, verified.revokedAt, verified.eventTime]
        : [verified.originalTransactionId, verified.eventTime, verified.eventTime];
      statements.push(
        env.DB.prepare(`UPDATE sessions SET revoked_at=? WHERE installation_id IN (${matchingAccessEnd}) AND revoked_at IS NULL`)
          .bind(timestamp, ...matchBindings),
        env.DB.prepare(`DELETE FROM device_channels WHERE device_id IN (SELECT id FROM devices WHERE installation_id IN (${matchingAccessEnd}))`)
          .bind(...matchBindings),
        env.DB.prepare(`UPDATE devices SET deleted_at=?,apns_token='deleted-'||id,updated_at=? WHERE installation_id IN (${matchingAccessEnd})`)
          .bind(timestamp, timestamp, ...matchBindings),
        env.DB.prepare(`UPDATE repo_enrollments SET deleted_at=? WHERE installation_id IN (${matchingAccessEnd}) AND deleted_at IS NULL`)
          .bind(timestamp, ...matchBindings),
        env.DB.prepare(`UPDATE github_installations SET deleted_at=? WHERE installation_id IN (${matchingAccessEnd}) AND deleted_at IS NULL`)
          .bind(timestamp, ...matchBindings),
        env.DB.prepare(`UPDATE installations SET deleted_at=?,updated_at=?,deletion_generation=deletion_generation+1 WHERE id IN (${matchingAccessEnd}) AND deleted_at IS NULL`)
          .bind(timestamp, timestamp, ...matchBindings),
      );
    }
  }
  statements.push(env.DB.prepare(
    "UPDATE app_store_notifications SET processed_at=? WHERE notification_uuid=?",
  ).bind(timestamp, verified.notificationUUID));
  await env.DB.batch(statements);
  return json({ accepted: true }, 202);
}

export async function dispatchPendingOutbox(env: Env, deliveryID?: string, limit = 100): Promise<number> {
  const query = deliveryID
    ? "SELECT id FROM outbox WHERE delivery_id=? AND enqueued_at IS NULL AND completed_at IS NULL ORDER BY created_at LIMIT ?"
    : "SELECT id FROM outbox WHERE enqueued_at IS NULL AND completed_at IS NULL ORDER BY created_at LIMIT ?";
  const statement = env.DB.prepare(query);
  const rows = deliveryID
    ? await statement.bind(deliveryID, limit).all<{ id: string }>()
    : await statement.bind(limit).all<{ id: string }>();
  let sent = 0;
  for (const row of rows.results) {
    try {
      await env.OUTBOX_QUEUE.send({ outboxId: row.id });
      await env.DB.prepare("UPDATE outbox SET enqueued_at=? WHERE id=? AND enqueued_at IS NULL").bind(currentTime(), row.id).run();
      sent += 1;
    } catch (error) {
      log("outbox_enqueue_failed");
      if (deliveryID) throw error;
      // Keep the row pending. A later cron invocation retries independently.
    }
  }
  return sent;
}

export async function cleanupRetention(env: Env, timestamp = currentTime()): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM app_store_notifications WHERE retention_until < ?").bind(timestamp),
    env.DB.prepare("DELETE FROM webhook_deliveries WHERE retention_until < ?").bind(timestamp),
    env.DB.prepare("DELETE FROM github_link_states WHERE COALESCE(authorization_expires_at,expires_at) < ?").bind(timestamp),
    env.DB.prepare("DELETE FROM sessions WHERE expires_at < ? OR revoked_at IS NOT NULL").bind(timestamp),
  ]);
}

export async function consumeOutbox(message: Message<OutboxMessage>, env: Env): Promise<void> {
  if (killSwitchEnabled(env)) { message.retry({ delaySeconds: 300 }); return; }
  const outboxID = message.body?.outboxId;
  if (typeof outboxID !== "string" || !/^out_[a-f0-9]{32}$/.test(outboxID)) { message.ack(); return; }
  const outbox = await env.DB.prepare(
    "SELECT channel,hint,completed_at FROM outbox WHERE id=?",
  ).bind(outboxID).first<{ channel: string; hint: string; completed_at: number | null }>();
  if (!outbox || outbox.completed_at !== null) { message.ack(); return; }
  const devices = await env.DB.prepare(
    "SELECT DISTINCT d.id,d.apns_token,d.apns_environment,d.registration_generation FROM repo_enrollments r " +
    "JOIN installations i ON i.id=r.installation_id " +
    "JOIN devices d ON d.installation_id=r.installation_id " +
    "JOIN entitlements e ON e.installation_id=r.installation_id " +
    "WHERE r.channel=? AND r.deleted_at IS NULL AND i.deleted_at IS NULL " +
    "AND d.deleted_at IS NULL AND e.revoked_at IS NULL AND e.expires_at>?",
  ).bind(outbox.channel, currentTime()).all<{ id: string; apns_token: string; apns_environment: "sandbox" | "production"; registration_generation: number }>();
  let transient = false;
  for (const device of devices.results) {
    const completed = await env.DB.prepare(
      "SELECT 1 AS complete FROM apns_attempts WHERE outbox_id=? AND device_id=? AND status IN ('success','invalidToken','permanent')",
    ).bind(outboxID, device.id).first();
    if (completed) continue;
    const leaseToken = randomToken(16);
    const leaseUntil = currentTime() + 120_000;
    const claim = await env.DB.prepare(
      "INSERT INTO outbox_claims(outbox_id,device_id,lease_token,lease_until) VALUES(?,?,?,?) " +
      "ON CONFLICT(outbox_id,device_id) DO UPDATE SET lease_token=excluded.lease_token,lease_until=excluded.lease_until " +
      "WHERE outbox_claims.lease_until<?",
    ).bind(outboxID, device.id, leaseToken, leaseUntil, currentTime()).run();
    if (!claim.meta.changes) { transient = true; continue; }
    try {
      const result = await sendApns(env, device.apns_token, device.apns_environment, { channel: outbox.channel, hint: outbox.hint });
      const timestamp = currentTime();
      await env.DB.prepare(
        "INSERT INTO apns_attempts(outbox_id,device_id,status,http_status,reason,attempted_at,completed_at) VALUES(?,?,?,?,?,?,?) " +
        "ON CONFLICT(outbox_id,device_id) DO UPDATE SET status=excluded.status,http_status=excluded.http_status,reason=excluded.reason,attempted_at=excluded.attempted_at,completed_at=excluded.completed_at",
      ).bind(outboxID, device.id, result.class, result.status, result.reason ?? null, timestamp, result.class === "transient" ? null : timestamp).run();
      if (result.class === "invalidToken") {
        await beforeInvalidTokenTombstone?.();
        await env.DB.batch([
          env.DB.prepare(
            "DELETE FROM device_channels WHERE device_id=? AND EXISTS(SELECT 1 FROM devices WHERE id=? AND apns_token=? AND registration_generation=? AND deleted_at IS NULL)",
          ).bind(device.id, device.id, device.apns_token, device.registration_generation),
          env.DB.prepare(
            "UPDATE devices SET deleted_at=?,apns_token='deleted-'||id,updated_at=? WHERE id=? AND apns_token=? AND registration_generation=? AND deleted_at IS NULL",
          ).bind(timestamp, timestamp, device.id, device.apns_token, device.registration_generation),
        ]);
      } else if (result.class === "transient") transient = true;
    } catch {
      transient = true;
    } finally {
      await env.DB.prepare("DELETE FROM outbox_claims WHERE outbox_id=? AND device_id=? AND lease_token=?")
        .bind(outboxID, device.id, leaseToken).run();
    }
  }
  if (transient) { message.retry({ delaySeconds: 60 }); return; }
  await env.DB.prepare("UPDATE outbox SET completed_at=? WHERE id=?").bind(currentTime(), outboxID).run();
  message.ack();
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method === "PUT" && path === "/v1/entitlements") return putEntitlement(request, env);
  if (request.method === "PUT" && path === "/v1/devices") return putDevice(request, env);
  if (request.method === "DELETE" && path === "/v1/devices") return deleteDevice(request, env);
  if (request.method === "DELETE" && path === "/v1/installation") return deleteInstallation(request, env);
  if (request.method === "POST" && path === "/v1/github/link/start") return startGitHubLink(request, env);
  if (request.method === "GET" && path === "/v1/github/callback") return completeGitHubSetup(request, env);
  if (request.method === "GET" && path === "/v1/github/authorize/callback") return completeGitHubAuthorization(request, env);
  if (request.method === "GET" && path === "/v1/github/link/status") return githubLinkStatus(request, env);
  if (request.method === "POST" && path === "/v1/enrollments") return createEnrollment(request, env);
  if (request.method === "POST" && path === "/v1/webhooks/github") return githubWebhook(request, env);
  if (request.method === "POST" && path === "/v1/app-store/notifications") return appStoreNotification(request, env);
  const enrollment = path.match(/^\/v1\/enrollments\/([A-Za-z0-9_-]{16,128})$/);
  if (enrollment && request.method === "DELETE") return deleteEnrollment(request, env, enrollment[1]!);
  throw new HttpError(404, "not found");
}

const worker: ExportedHandler<Env, OutboxMessage> = {
  async fetch(request, env) {
    try { requireRelayConfiguration(env); return await route(request, env); }
    catch (error) {
      const responseError = error instanceof HttpError ? error : new HttpError(500, "internal error");
      log("request_failed", { status: responseError.status });
      return json({ error: responseError.message }, responseError.status);
    }
  },
  async queue(batch, env) {
    try { requireRelayConfiguration(env); }
    catch { for (const message of batch.messages) message.retry({ delaySeconds: 300 }); return; }
    for (const message of batch.messages) await consumeOutbox(message, env);
  },
  async scheduled(_controller, env) {
    requireRelayConfiguration(env);
    if (!killSwitchEnabled(env)) await dispatchPendingOutbox(env);
    await cleanupRetention(env);
  },
};

export default worker;
