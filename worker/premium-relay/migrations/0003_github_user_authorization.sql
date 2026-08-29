ALTER TABLE github_link_states ADD COLUMN github_installation_id INTEGER;
ALTER TABLE github_link_states ADD COLUMN authorization_state_hash TEXT;
ALTER TABLE github_link_states ADD COLUMN authorization_expires_at INTEGER;
ALTER TABLE github_link_states ADD COLUMN authorized_at INTEGER;
ALTER TABLE github_link_states ADD COLUMN authorized_nonce TEXT;

CREATE UNIQUE INDEX github_link_states_authorization_state
  ON github_link_states(authorization_state_hash)
  WHERE authorization_state_hash IS NOT NULL;
