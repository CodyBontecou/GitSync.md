-- Sync.md privacy-safe onboarding analytics event store.
-- Deliberately stores only validated, coarse onboarding/unlock fields.
-- Do not add repository URLs, file paths, branch names, author names/emails,
-- tokens, SSH keys, file contents, raw request IPs, user agents, or device IDs.

CREATE TABLE IF NOT EXISTS onboarding_events (
  id TEXT PRIMARY KEY,
  received_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  install_id TEXT NOT NULL,
  event_name TEXT NOT NULL,
  app_version TEXT,
  build_number TEXT,
  platform TEXT,
  onboarding_step TEXT,
  auth_method TEXT,
  auth_outcome TEXT,
  save_location_preference TEXT,
  paywall_context TEXT,
  free_repo_slots_used INTEGER,
  free_repo_slots_remaining INTEGER,
  product_id TEXT,
  purchase_outcome TEXT,
  error_category TEXT,
  payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_received_at
  ON onboarding_events(received_at);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_event_received
  ON onboarding_events(event_name, received_at);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_install_event_received
  ON onboarding_events(install_id, event_name, received_at);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_step_received
  ON onboarding_events(onboarding_step, received_at);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_paywall_context_received
  ON onboarding_events(paywall_context, received_at);
