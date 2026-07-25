create table auth_sessions (
  token_hash text primary key,
  principal text not null,
  csrf_secret_hash text not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  invalidated_at timestamptz,
  constraint auth_sessions_expiry_after_creation
    check (expires_at > created_at)
);

create index auth_sessions_expires_at_idx
  on auth_sessions (expires_at);
