import {
  Environment,
  SignedDataVerifier,
  VerificationException,
  VerificationStatus,
} from "@apple/app-store-server-library";
import { APPLE_ROOTS } from "./roots";
import type {
  Env,
  VerifiedNotificationResponse,
  VerifiedTransactionResponse,
} from "./types";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};
const PATH_TRANSACTION = "/v1/transactions/verify";
const PATH_NOTIFICATION = "/v1/notifications/verify";

type AllowedEnvironment = "Sandbox" | "Production";
type JsonRecord = Record<string, unknown>;

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function response(status: number, body: unknown): Response {
  return Response.json(body, { status, headers: JSON_HEADERS });
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(record: JsonRecord, expected: readonly string[]): void {
  const keys = Object.keys(record);
  if (keys.length !== expected.length || keys.some((key) => !expected.includes(key))) {
    throw new HttpError(400, "invalid request");
  }
}

function requiredString(value: unknown, label: string, max: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > max) {
    throw new HttpError(400, `invalid ${label}`);
  }
  return value;
}

function safeInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new HttpError(422, `verified ${label} is missing`);
  }
  return value;
}

function nullableSafeInteger(value: unknown): number | null {
  return value === undefined || value === null ? null : safeInteger(value, "date");
}

function parsePositiveInteger(value: string, label: string): number {
  if (!/^[1-9][0-9]*$/.test(value)) throw new Error(`invalid ${label}`);
  const number = Number(value);
  if (!Number.isSafeInteger(number)) throw new Error(`invalid ${label}`);
  return number;
}

function parseBoolean(value: string, label: string): boolean {
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`invalid ${label}`);
}

function allowedEnvironments(env: Env): AllowedEnvironment[] {
  const parsed = env.ALLOWED_ENVIRONMENTS.split(",").map((value) => value.trim());
  if (
    parsed.length === 0 ||
    parsed.some((value) => value !== Environment.SANDBOX && value !== Environment.PRODUCTION)
  ) {
    throw new Error("invalid ALLOWED_ENVIRONMENTS");
  }
  return [...new Set(parsed)] as AllowedEnvironment[];
}

function verifier(
  env: Env,
  environment: AllowedEnvironment,
  roots: Buffer[] = APPLE_ROOTS,
): SignedDataVerifier {
  return new SignedDataVerifier(
    roots,
    parseBoolean(env.ENABLE_ONLINE_CHECKS, "ENABLE_ONLINE_CHECKS"),
    environment === "Production" ? Environment.PRODUCTION : Environment.SANDBOX,
    requiredString(env.BUNDLE_ID, "BUNDLE_ID", 256),
    parsePositiveInteger(env.APP_APPLE_ID, "APP_APPLE_ID"),
  );
}

async function parseBody(request: Request, env: Env): Promise<JsonRecord> {
  const maximum = parsePositiveInteger(env.MAX_JWS_BYTES, "MAX_JWS_BYTES");
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim();
  if (contentType !== "application/json") throw new HttpError(415, "application/json required");
  const length = request.headers.get("content-length");
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > maximum)) {
    throw new HttpError(413, "request too large");
  }
  const body = await request.text();
  if (new TextEncoder().encode(body).byteLength > maximum) {
    throw new HttpError(413, "request too large");
  }
  try {
    const decoded: unknown = JSON.parse(body);
    if (!isRecord(decoded)) throw new HttpError(400, "invalid request");
    return decoded;
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(400, "invalid JSON");
  }
}

function safeVerificationError(error: unknown): { status?: VerificationStatus; category: "verification" | "unexpected" } {
  return error instanceof VerificationException
    ? { status: error.status, category: "verification" }
    : { category: "unexpected" };
}

export async function verifyAcrossEnvironments<T>(
  env: Env,
  operation: (verifier: SignedDataVerifier) => Promise<T>,
  roots: Buffer[] = APPLE_ROOTS,
): Promise<T> {
  let lastVerificationError: unknown;
  let unavailableError: unknown;
  for (const environment of allowedEnvironments(env)) {
    try {
      return await operation(verifier(env, environment, roots));
    } catch (error) {
      if (error instanceof VerificationException) {
        lastVerificationError = error;
        if (error.status === VerificationStatus.RETRYABLE_VERIFICATION_FAILURE) {
          unavailableError = error;
        }
        continue;
      }
      unavailableError = error;
    }
  }
  if (unavailableError) {
    // Never log verifier errors directly: their message, stack, or cause may
    // contain signed payload material supplied by a library or caller.
    console.error("StoreKit verification unavailable", safeVerificationError(unavailableError));
    throw new HttpError(503, "verification unavailable");
  }
  console.warn("StoreKit JWS verification rejected", safeVerificationError(lastVerificationError));
  throw new HttpError(401, "verification failed");
}

async function verifyTransaction(request: Request, env: Env): Promise<Response> {
  const body = await parseBody(request, env);
  exactKeys(body, ["signedTransaction"]);
  const signedTransaction = requiredString(
    body.signedTransaction,
    "signedTransaction",
    parsePositiveInteger(env.MAX_JWS_BYTES, "MAX_JWS_BYTES"),
  );
  const decoded = await verifyAcrossEnvironments(env, (candidate) =>
    candidate.verifyAndDecodeTransaction(signedTransaction));

  const environment = decoded.environment;
  if (environment !== Environment.SANDBOX && environment !== Environment.PRODUCTION) {
    throw new HttpError(422, "verified environment is invalid");
  }
  const result: VerifiedTransactionResponse = {
    transactionId: requiredString(decoded.transactionId, "transaction ID", 128),
    originalTransactionId: requiredString(decoded.originalTransactionId, "original transaction ID", 128),
    productId: requiredString(decoded.productId, "product ID", 256),
    bundleId: requiredString(decoded.bundleId, "bundle ID", 256),
    environment,
    expiresAt: safeInteger(decoded.expiresDate, "expiration date"),
    revokedAt: nullableSafeInteger(decoded.revocationDate),
    appAccountToken: decoded.appAccountToken === undefined
      ? null
      : requiredString(decoded.appAccountToken, "app account token", 36),
    eventTime: safeInteger(decoded.signedDate, "signed date"),
  };
  return response(200, result);
}

async function verifyNotification(request: Request, env: Env): Promise<Response> {
  const body = await parseBody(request, env);
  exactKeys(body, ["signedPayload"]);
  const signedPayload = requiredString(
    body.signedPayload,
    "signedPayload",
    parsePositiveInteger(env.MAX_JWS_BYTES, "MAX_JWS_BYTES"),
  );
  const decoded = await verifyAcrossEnvironments(env, (candidate) =>
    candidate.verifyAndDecodeNotification(signedPayload));
  const data = decoded.data;
  let transaction:
    | Awaited<ReturnType<SignedDataVerifier["verifyAndDecodeTransaction"]>>
    | undefined;
  let renewalInfo:
    | Awaited<ReturnType<SignedDataVerifier["verifyAndDecodeRenewalInfo"]>>
    | undefined;
  if (data?.signedTransactionInfo) {
    transaction = await verifyAcrossEnvironments(env, (candidate) =>
      candidate.verifyAndDecodeTransaction(data.signedTransactionInfo!));
  }
  if (data?.signedRenewalInfo) {
    renewalInfo = await verifyAcrossEnvironments(env, (candidate) =>
      candidate.verifyAndDecodeRenewalInfo(data.signedRenewalInfo!));
  }
  if (transaction?.originalTransactionId && renewalInfo?.originalTransactionId &&
      transaction.originalTransactionId !== renewalInfo.originalTransactionId) {
    throw new HttpError(422, "verified renewal transaction mismatch");
  }
  const result: VerifiedNotificationResponse = {
    notificationUUID: requiredString(decoded.notificationUUID, "notification UUID", 128),
    notificationType: requiredString(decoded.notificationType, "notification type", 128),
    subtype: decoded.subtype === undefined || decoded.subtype === null
      ? null
      : requiredString(decoded.subtype, "notification subtype", 128),
    originalTransactionId: transaction?.originalTransactionId ?? renewalInfo?.originalTransactionId ?? null,
    expiresAt: nullableSafeInteger(transaction?.expiresDate),
    revokedAt: nullableSafeInteger(transaction?.revocationDate),
    gracePeriodExpiresAt: nullableSafeInteger(renewalInfo?.gracePeriodExpiresDate),
    eventTime: safeInteger(decoded.signedDate, "signed date"),
  };
  if (result.originalTransactionId !== null) {
    result.originalTransactionId = requiredString(
      result.originalTransactionId,
      "original transaction ID",
      128,
    );
  }
  return response(200, result);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method !== "POST") {
        return response(405, { error: "method not allowed" });
      }
      if (url.pathname === PATH_TRANSACTION) return await verifyTransaction(request, env);
      if (url.pathname === PATH_NOTIFICATION) return await verifyNotification(request, env);
      return response(404, { error: "not found" });
    } catch (error) {
      if (error instanceof HttpError) return response(error.status, { error: error.message });
      console.error("StoreKit verifier unavailable", safeVerificationError(error));
      return response(503, { error: "verification unavailable" });
    }
  },
} satisfies ExportedHandler<Env>;
