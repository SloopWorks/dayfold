CREATE TABLE device_authorizations (
  device_code   text PRIMARY KEY,
  user_code     text NOT NULL,
  client        text,
  status        text NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','approved','denied','expired','consumed')),
  user_id       text REFERENCES users(id),
  family_id     text REFERENCES families(id),
  credential_id text REFERENCES credentials(id),
  origin_ip     text, origin_ua text,
  interval_s    int NOT NULL DEFAULT 5,
  last_polled_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  approved_at   timestamptz
);
CREATE UNIQUE INDEX ON device_authorizations (user_code) WHERE status='pending';

CREATE TABLE rate_limits (
  key          text NOT NULL,
  window_start timestamptz NOT NULL,
  count        int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  PRIMARY KEY (key, window_start)
);

CREATE TABLE audit_log (
  id        bigserial PRIMARY KEY,
  at        timestamptz NOT NULL DEFAULT now(),
  event     text NOT NULL,
  actor_user_id text, family_id text,
  detail    jsonb NOT NULL DEFAULT '{}'
);
