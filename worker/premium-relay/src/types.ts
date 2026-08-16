export interface VerifierBinding extends Fetcher {}

export interface Env {
  DB: D1Database;
  OUTBOX_QUEUE: Queue<OutboxMessage>;
  STOREKIT_VERIFIER: VerifierBinding;

  BUNDLE_ID: string;
  PRODUCT_IDS: string;
  APP_STORE_ENVIRONMENTS: string;
  GITHUB_APP_ID: string;
  GITHUB_APP_SLUG: string;
  GITHUB_CALLBACK_URL: string;
  APNS_TEAM_ID: string;
  APNS_KEY_ID: string;
  APNS_TOPIC: string;
  SESSION_TTL_SECONDS: string;
  LINK_STATE_TTL_SECONDS: string;
  WEBHOOK_RETENTION_DAYS: string;
  NOTIFICATION_RETENTION_DAYS: string;
  KILL_SWITCH: string;

  GITHUB_APP_PRIVATE_KEY: string;
  GITHUB_WEBHOOK_SECRET: string;
  APNS_PRIVATE_KEY: string;
}

export interface OutboxMessage {
  outboxId: string;
}
