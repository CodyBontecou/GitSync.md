PRAGMA foreign_keys = ON;

CREATE TABLE installations (
  id TEXT PRIMARY KEY,
  bundle_id TEXT NOT NULL,
  app_version TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  deletion_generation INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE TABLE entitlements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  original_transaction_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  environment TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  event_time INTEGER NOT NULL,
  verified_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(installation_id, original_transaction_id)
) STRICT;
CREATE INDEX entitlements_active ON entitlements(installation_id, expires_at, revoked_at);
CREATE INDEX entitlements_transaction ON entitlements(original_transaction_id);
CREATE UNIQUE INDEX entitlements_original_owner ON entitlements(original_transaction_id);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
) STRICT;
CREATE INDEX sessions_installation ON sessions(installation_id, expires_at);

CREATE TABLE installation_deletions (
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  token_hash TEXT PRIMARY KEY,
  requested_at INTEGER NOT NULL
) STRICT;
CREATE INDEX installation_deletions_installation ON installation_deletions(installation_id, requested_at);

CREATE TABLE installation_deletion_keys (
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  key_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
) STRICT;
CREATE INDEX installation_deletion_keys_installation ON installation_deletion_keys(installation_id, created_at);

CREATE TABLE github_link_states (
  state_hash TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  deletion_generation INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  created_at INTEGER NOT NULL
) STRICT;
CREATE INDEX github_link_states_expiry ON github_link_states(expires_at);

CREATE TABLE github_installations (
  github_installation_id INTEGER NOT NULL,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  linked_at INTEGER NOT NULL,
  deleted_at INTEGER,
  PRIMARY KEY(github_installation_id, installation_id)
) STRICT, WITHOUT ROWID;
CREATE INDEX github_installations_owner ON github_installations(installation_id, deleted_at);

CREATE TABLE repo_enrollments (
  channel TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  github_installation_id INTEGER NOT NULL,
  repository_id INTEGER NOT NULL,
  branch TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  deleted_at INTEGER,
  UNIQUE(installation_id, repository_id, branch),
  FOREIGN KEY(github_installation_id, installation_id)
    REFERENCES github_installations(github_installation_id, installation_id) ON DELETE CASCADE
) STRICT;
CREATE INDEX repo_match ON repo_enrollments(repository_id, branch, deleted_at);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  apns_token TEXT NOT NULL,
  apns_environment TEXT NOT NULL CHECK(apns_environment IN ('sandbox','production')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  registration_generation INTEGER NOT NULL DEFAULT 0,
  UNIQUE(installation_id, apns_token, apns_environment)
) STRICT;
CREATE INDEX devices_owner ON devices(installation_id, deleted_at);

CREATE TABLE device_channels (
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  channel TEXT NOT NULL REFERENCES repo_enrollments(channel) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(device_id, channel)
) STRICT, WITHOUT ROWID;
CREATE INDEX device_channels_channel ON device_channels(channel, device_id);

CREATE TABLE webhook_deliveries (
  delivery_id TEXT PRIMARY KEY,
  event TEXT NOT NULL,
  repository_id INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  completed_at INTEGER,
  retention_until INTEGER NOT NULL
) STRICT;
CREATE INDEX webhook_retention ON webhook_deliveries(retention_until);

CREATE TABLE outbox (
  id TEXT PRIMARY KEY,
  delivery_id TEXT NOT NULL REFERENCES webhook_deliveries(delivery_id) ON DELETE CASCADE,
  channel TEXT NOT NULL REFERENCES repo_enrollments(channel) ON DELETE CASCADE,
  hint TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  enqueued_at INTEGER,
  completed_at INTEGER,
  retention_until INTEGER NOT NULL,
  UNIQUE(delivery_id, channel)
) STRICT;
CREATE INDEX outbox_pending ON outbox(enqueued_at, completed_at, created_at);

CREATE TABLE apns_attempts (
  outbox_id TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK(status IN ('success','invalidToken','permanent','transient')),
  http_status INTEGER,
  reason TEXT,
  attempted_at INTEGER NOT NULL,
  completed_at INTEGER,
  PRIMARY KEY(outbox_id, device_id)
) STRICT, WITHOUT ROWID;
CREATE INDEX apns_attempt_status ON apns_attempts(status, attempted_at);

CREATE TABLE outbox_claims (
  outbox_id TEXT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  lease_token TEXT NOT NULL,
  lease_until INTEGER NOT NULL,
  PRIMARY KEY(outbox_id, device_id)
) STRICT, WITHOUT ROWID;
CREATE INDEX outbox_claims_expiry ON outbox_claims(lease_until);

CREATE TABLE app_store_notifications (
  notification_uuid TEXT PRIMARY KEY,
  notification_type TEXT NOT NULL,
  original_transaction_id TEXT,
  received_at INTEGER NOT NULL,
  event_time INTEGER NOT NULL,
  processed_at INTEGER,
  retention_until INTEGER NOT NULL
) STRICT;
CREATE INDEX app_store_notification_retention ON app_store_notifications(retention_until);
