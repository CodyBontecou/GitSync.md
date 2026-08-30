export interface Env {
  DB: D1Database;
  INGEST_TOKEN?: string;
  DELETION_TOKEN?: string;
  MAX_BATCH_SIZE?: string;
  RETENTION_DAYS?: string;
}

type AnalyticsValue = string | number;
type AnalyticsProperties = Record<string, AnalyticsValue>;

type OnboardingEventRow = {
  id: string;
  installId: string;
  eventName: string;
  properties: AnalyticsProperties;
};

const MAX_BODY_BYTES = 64 * 1024;
const DEFAULT_MAX_BATCH_SIZE = 50;
const DEFAULT_RETENTION_DAYS = 90;

const EVENT_NAMES = new Set([
  "sync_onboarding_started",
  "sync_onboarding_step_viewed",
  "sync_onboarding_auth_started",
  "sync_onboarding_auth_completed",
  "sync_onboarding_save_location_selected",
  "sync_onboarding_completed",
]);

const STRING_PROPERTY_KEYS = new Set([
  "appVersion",
  "buildNumber",
  "platform",
  "onboardingStep",
  "authMethod",
  "authOutcome",
  "saveLocationPreference",
  "errorCategory",
]);

const ALLOWED_PROPERTY_KEYS = new Set([
  ...STRING_PROPERTY_KEYS,
]);

const PLATFORMS = new Set(["ios", "macos"]);
const ONBOARDING_STEPS = new Set([
  "welcome",
  "edit_anywhere",
  "full_git",
  "background_sync",
  "account_choice",
  "github_sign_in",
  "personal_access_token",
  "save_location",
  "demo",
  "ready",
]);
const AUTH_METHODS = new Set(["github_oauth", "personal_access_token", "none", "demo"]);
const AUTH_OUTCOMES = new Set(["started", "succeeded", "failed", "skipped"]);
const SAVE_LOCATION_PREFERENCES = new Set(["default_app_folder", "custom_folder"]);
const ERROR_CATEGORIES = new Set([
  "network_unavailable",
  "configuration_unavailable",
  "auth_failed",
  "unknown",
]);

const APP_VERSION_RE = /^\d+(?:\.\d+){0,3}$/;
const BUILD_NUMBER_RE = /^\d{1,12}$/;
const INSTALL_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const pathname = normalizedPathname(url.pathname);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method === "GET" && pathname === "/health") {
      return json({ ok: true, service: "sync-md-onboarding-analytics" });
    }

    if (request.method === "POST" && pathname === "/v1/events") {
      return ingestEvents(request, env);
    }

    if (request.method === "DELETE" && pathname === "/v1/installations/current") {
      return deleteInstallationEvents(request, env);
    }

    return json({ ok: false, error: "not_found" }, 404);
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(cleanupExpiredEvents(env));
  },
};

async function deleteInstallationEvents(request: Request, env: Env): Promise<Response> {
  const authError = authorizeDeletion(request, env);
  if (authError) return authError;

  const installId = validateInstallIDHeader(request.headers.get("x-installation-id"));
  if (!installId) return json({ ok: false, error: "invalid_install_id" }, 400);

  await env.DB.prepare("DELETE FROM onboarding_events WHERE install_id = ?")
    .bind(installId)
    .run();
  return new Response(null, { status: 204, headers: corsHeaders() });
}

async function ingestEvents(request: Request, env: Env): Promise<Response> {
  const authError = authorize(request, env);
  if (authError) return authError;

  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return json({ ok: false, error: "body_too_large" }, 413);
  }

  const rawBody = await request.text();
  if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) {
    return json({ ok: false, error: "body_too_large" }, 413);
  }

  let body: unknown;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return json({ ok: false, error: "invalid_json" }, 400);
  }

  let rows: OnboardingEventRow[];
  try {
    rows = normalizeIngestBody(body, maxBatchSize(env));
  } catch (error) {
    return json({ ok: false, error: error instanceof Error ? error.message : "invalid_payload" }, 400);
  }

  if (rows.length === 0) {
    return json({ ok: false, error: "empty_batch" }, 400);
  }

  const insert = env.DB.prepare(`
    INSERT OR IGNORE INTO onboarding_events (
      id,
      install_id,
      event_name,
      app_version,
      build_number,
      platform,
      onboarding_step,
      auth_method,
      auth_outcome,
      save_location_preference,
      paywall_context,
      free_repo_slots_used,
      free_repo_slots_remaining,
      product_id,
      purchase_outcome,
      error_category,
      payload_json
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  await env.DB.batch(rows.map((row) => insert.bind(
    row.id,
    row.installId,
    row.eventName,
    stringProperty(row.properties, "appVersion"),
    stringProperty(row.properties, "buildNumber"),
    stringProperty(row.properties, "platform"),
    stringProperty(row.properties, "onboardingStep"),
    stringProperty(row.properties, "authMethod"),
    stringProperty(row.properties, "authOutcome"),
    stringProperty(row.properties, "saveLocationPreference"),
    null,
    null,
    null,
    null,
    null,
    stringProperty(row.properties, "errorCategory"),
    JSON.stringify({ eventName: row.eventName, properties: row.properties }),
  )));

  return json({ ok: true, accepted: rows.length });
}

function normalizeIngestBody(body: unknown, maxBatch: number): OnboardingEventRow[] {
  if (!isObject(body)) throw new Error("payload_must_be_object");

  const batchInstallId = optionalString(body.installId);
  const incomingEvents = Array.isArray(body.events) ? body.events : [body];

  if (incomingEvents.length > maxBatch) throw new Error("batch_too_large");

  return incomingEvents.map((event) => normalizeEvent(event, batchInstallId));
}

function normalizeEvent(event: unknown, batchInstallId: string | undefined): OnboardingEventRow {
  if (!isObject(event)) throw new Error("event_must_be_object");

  const eventName = requiredString(event.eventName, "eventName");
  if (!EVENT_NAMES.has(eventName)) throw new Error("unknown_event_name");

  const eventId = validateUUID(optionalString(event.eventId) ?? optionalString(event.id), "event_id");
  const installId = validateUUID(optionalString(event.installId) ?? batchInstallId, "install_id");
  const properties = normalizeProperties(isObject(event.properties) ? event.properties : {});

  return {
    id: eventId,
    installId,
    eventName,
    properties,
  };
}

function normalizeProperties(properties: Record<string, unknown>): AnalyticsProperties {
  const normalized: AnalyticsProperties = {};

  for (const [key, value] of Object.entries(properties)) {
    if (!ALLOWED_PROPERTY_KEYS.has(key)) throw new Error(`unknown_property:${key}`);

    if (STRING_PROPERTY_KEYS.has(key)) {
      normalized[key] = validateStringProperty(key, value);
    }
  }

  return normalized;
}

function validateStringProperty(key: string, value: unknown): string {
  if (typeof value !== "string") throw new Error(`invalid_property_type:${key}`);

  switch (key) {
    case "appVersion":
      if (!APP_VERSION_RE.test(value)) throw new Error(`invalid_property:${key}`);
      return value;
    case "buildNumber":
      if (!BUILD_NUMBER_RE.test(value)) throw new Error(`invalid_property:${key}`);
      return value;
    case "platform":
      return validateSetValue(key, value, PLATFORMS);
    case "onboardingStep":
      return validateSetValue(key, value, ONBOARDING_STEPS);
    case "authMethod":
      return validateSetValue(key, value, AUTH_METHODS);
    case "authOutcome":
      return validateSetValue(key, value, AUTH_OUTCOMES);
    case "saveLocationPreference":
      return validateSetValue(key, value, SAVE_LOCATION_PREFERENCES);
    case "errorCategory":
      return validateSetValue(key, value, ERROR_CATEGORIES);
    default:
      throw new Error(`unknown_property:${key}`);
  }
}

function validateSetValue(key: string, value: string, allowedValues: Set<string>): string {
  if (!allowedValues.has(value)) throw new Error(`unknown_property_value:${key}`);
  return value;
}

function validateUUID(value: string | undefined, label: string): string {
  if (!value || !INSTALL_ID_RE.test(value)) throw new Error(`invalid_${label}`);
  return value.toLowerCase();
}

function authorize(request: Request, env: Env): Response | undefined {
  if (!env.INGEST_TOKEN) return undefined;

  const expected = `Bearer ${env.INGEST_TOKEN}`;
  if (request.headers.get("authorization") === expected) return undefined;

  return json({ ok: false, error: "unauthorized" }, 401);
}

function authorizeDeletion(request: Request, env: Env): Response | undefined {
  if (!env.DELETION_TOKEN) return json({ ok: false, error: "deletion_unavailable" }, 503);

  const expected = `Bearer ${env.DELETION_TOKEN}`;
  if (request.headers.get("authorization") === expected) return undefined;

  return json({ ok: false, error: "unauthorized" }, 401);
}

function validateInstallIDHeader(value: string | null): string | undefined {
  return value && INSTALL_ID_RE.test(value) ? value.toLowerCase() : undefined;
}

function maxBatchSize(env: Env): number {
  const parsed = Number(env.MAX_BATCH_SIZE ?? DEFAULT_MAX_BATCH_SIZE);
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, DEFAULT_MAX_BATCH_SIZE) : DEFAULT_MAX_BATCH_SIZE;
}

function retentionDays(env: Env): number {
  const parsed = Number(env.RETENTION_DAYS ?? DEFAULT_RETENTION_DAYS);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 365 ? parsed : DEFAULT_RETENTION_DAYS;
}

async function cleanupExpiredEvents(env: Env): Promise<void> {
  await env.DB.prepare(
    "DELETE FROM onboarding_events WHERE datetime(received_at) < datetime('now', ?)",
  ).bind(`-${retentionDays(env)} days`).run();
}

function stringProperty(properties: AnalyticsProperties, key: string): string | null {
  const value = properties[key];
  return typeof value === "string" ? value : null;
}

function requiredString(value: unknown, key: string): string {
  if (typeof value !== "string" || value.length === 0) throw new Error(`missing_${key}`);
  return value;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: corsHeaders() });
}

function normalizedPathname(pathname: string): string {
  const normalized = pathname.replace(/\/+$/, "");
  return normalized.length > 0 ? normalized : "/";
}

function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "DELETE,GET,POST,OPTIONS",
    "access-control-allow-headers": "authorization,content-type,x-installation-id",
    "cache-control": "no-store",
  };
}
