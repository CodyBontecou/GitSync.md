export interface Env {
  BUNDLE_ID: string;
  APP_APPLE_ID: string;
  ENABLE_ONLINE_CHECKS: string;
  ALLOWED_ENVIRONMENTS: string;
  MAX_JWS_BYTES: string;
}

export interface VerifiedTransactionResponse {
  transactionId: string;
  originalTransactionId: string;
  productId: string;
  bundleId: string;
  environment: "Sandbox" | "Production";
  expiresAt: number;
  revokedAt: number | null;
  appAccountToken: string | null;
  eventTime: number;
}

export interface VerifiedNotificationResponse {
  notificationUUID: string;
  notificationType: string;
  subtype: string | null;
  originalTransactionId: string | null;
  expiresAt: number | null;
  revokedAt: number | null;
  gracePeriodExpiresAt: number | null;
  eventTime: number;
}
