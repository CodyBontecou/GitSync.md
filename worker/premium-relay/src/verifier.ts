import { exactKeys, HttpError, isRecord, text } from "./core";
import type { VerifierBinding } from "./types";

export interface VerifiedTransaction {
  transactionId: string; originalTransactionId: string; productId: string; bundleId: string;
  environment: string; expiresAt: number; revokedAt: number | null; appAccountToken: string | null; eventTime: number;
}
export interface VerifiedNotification {
  notificationUUID: string; notificationType: string; subtype: string | null; originalTransactionId: string | null;
  expiresAt: number | null; revokedAt: number | null; gracePeriodExpiresAt: number | null; eventTime: number;
}
async function call(binding: VerifierBinding, path: string, input: unknown): Promise<unknown> {
  let response: Response;
  try { response = await binding.fetch(`https://verifier.internal${path}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(input) }); }
  catch { throw new HttpError(503, "verification unavailable"); }
  if (!response.ok) throw new HttpError(response.status === 400 ? 400 : 503, "verification failed");
  try { return await response.json(); } catch { throw new HttpError(503, "invalid verifier response"); }
}
export async function verifyTransaction(binding: VerifierBinding, signedTransaction: string): Promise<VerifiedTransaction> {
  const v = await call(binding, "/v1/transactions/verify", { signedTransaction });
  if (!isRecord(v)) throw new HttpError(503, "invalid verifier response");
  exactKeys(v, ["transactionId","originalTransactionId","productId","bundleId","environment","expiresAt","revokedAt","appAccountToken","eventTime"]);
  if (typeof v.expiresAt !== "number" || !Number.isSafeInteger(v.expiresAt) ||
      typeof v.eventTime !== "number" || !Number.isSafeInteger(v.eventTime) ||
      !(v.revokedAt === null || (typeof v.revokedAt === "number" && Number.isSafeInteger(v.revokedAt))) ||
      !(v.appAccountToken === null || typeof v.appAccountToken === "string")) {
    throw new HttpError(503, "invalid verifier response");
  }
  return {
    transactionId: text(v.transactionId,"transaction",128),
    originalTransactionId: text(v.originalTransactionId,"original transaction",128),
    productId: text(v.productId,"product",128), bundleId: text(v.bundleId,"bundle",256),
    environment: text(v.environment,"environment",32), expiresAt: v.expiresAt,
    revokedAt: v.revokedAt,
    appAccountToken: v.appAccountToken === null ? null : text(v.appAccountToken,"app account token",36),
    eventTime: v.eventTime,
  };
}
export async function verifyNotification(binding: VerifierBinding, signedPayload: string): Promise<VerifiedNotification> {
  const v = await call(binding, "/v1/notifications/verify", { signedPayload });
  if (!isRecord(v)) throw new HttpError(503, "invalid verifier response");
  exactKeys(v, ["notificationUUID","notificationType","subtype","originalTransactionId","expiresAt","revokedAt","gracePeriodExpiresAt","eventTime"]);
  const nullableText = (x: unknown) => x === null ? null : text(x,"transaction",128);
  const nullableInt = (x: unknown) => x === null ? null : (typeof x === "number" && Number.isSafeInteger(x) ? x : (() => { throw new HttpError(503,"invalid verifier response"); })());
  const eventTime = nullableInt(v.eventTime);
  if (eventTime === null) throw new HttpError(503, "invalid verifier response");
  return { notificationUUID: text(v.notificationUUID,"notification UUID",128), notificationType: text(v.notificationType,"notification type",128), subtype: v.subtype === null ? null : text(v.subtype,"notification subtype",128), originalTransactionId: nullableText(v.originalTransactionId), expiresAt: nullableInt(v.expiresAt), revokedAt: nullableInt(v.revokedAt), gracePeriodExpiresAt: nullableInt(v.gracePeriodExpiresAt), eventTime };
}
