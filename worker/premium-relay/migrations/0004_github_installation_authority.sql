ALTER TABLE github_installations ADD COLUMN authorizing_user_id INTEGER;

CREATE TABLE github_installation_tombstones (
  github_installation_id INTEGER PRIMARY KEY,
  deleted_at INTEGER NOT NULL
) STRICT;
