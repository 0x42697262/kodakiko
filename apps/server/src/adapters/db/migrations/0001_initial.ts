import { sql, type Kysely } from 'kysely';

export async function up(db: Kysely<unknown>): Promise<void> {
  await sql`
CREATE TABLE users (
  id                       text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  username                 text NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._-]{3,32}$'),
  role                     text NOT NULL CHECK (role IN ('admin', 'member')),
  status                   text NOT NULL CHECK (status IN ('active', 'pending_deletion')),
  login_key_hash           bytea NOT NULL CHECK (octet_length(login_key_hash) = 32),
  recovery_login_key_hash  bytea NOT NULL CHECK (octet_length(recovery_login_key_hash) = 32),
  kdf_salt                 bytea NOT NULL CHECK (octet_length(kdf_salt) = 16),
  kdf_ops_limit            integer NOT NULL CHECK (kdf_ops_limit >= 1),
  kdf_mem_limit_bytes      bigint NOT NULL CHECK (kdf_mem_limit_bytes >= 134217728),
  master_key_by_kek        bytea NOT NULL,
  master_key_by_recovery   bytea NOT NULL,
  recovery_key_by_master   bytea NOT NULL,
  public_key               bytea NOT NULL CHECK (octet_length(public_key) = 32),
  private_key_by_master    bytea NOT NULL,
  quota_bytes              bigint NULL CHECK (quota_bytes >= 0),
  used_bytes               bigint NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
  deletion_due_at          timestamptz NULL,
  created_at               timestamptz NOT NULL,
  updated_at               timestamptz NOT NULL,
  CHECK (kdf_mem_limit_bytes * kdf_ops_limit >= 4294967296),
  CHECK ((status = 'pending_deletion') = (deletion_due_at IS NOT NULL))
)`.execute(db);

  await sql`
CREATE TABLE sessions (
  id            text PRIMARY KEY,
  user_id       text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash    bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
  device_label  text NOT NULL CHECK (char_length(device_label) BETWEEN 1 AND 64),
  created_at    timestamptz NOT NULL,
  last_used_at  timestamptz NOT NULL,
  expires_at    timestamptz NOT NULL
)`.execute(db);
  await sql`CREATE INDEX sessions_user_id_idx ON sessions (user_id)`.execute(db);
  await sql`CREATE INDEX sessions_expires_at_idx ON sessions (expires_at)`.execute(db);

  await sql`
CREATE TABLE invites (
  id           text PRIMARY KEY,
  token_hash   bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
  created_by   text NULL REFERENCES users(id) ON DELETE SET NULL,
  role         text NOT NULL CHECK (role IN ('admin', 'member')),
  expires_at   timestamptz NOT NULL,
  redeemed_by  text NULL REFERENCES users(id) ON DELETE SET NULL,
  redeemed_at  timestamptz NULL,
  revoked_at   timestamptz NULL,
  created_at   timestamptz NOT NULL
)`.execute(db);

  await sql`
CREATE TABLE collections (
  id             text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  owner_id       text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('home', 'shared', 'link')),
  key_by_owner   bytea NOT NULL,
  metadata_blob  bytea NOT NULL,
  last_seq       bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  tombstones_pruned_through_seq bigint NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL,
  meta_updated_at timestamptz NOT NULL
)`.execute(db);
  await sql`CREATE UNIQUE INDEX collections_one_home_per_owner ON collections (owner_id) WHERE kind = 'home'`.execute(
    db,
  );
  await sql`CREATE INDEX collections_owner_id_idx ON collections (owner_id)`.execute(db);

  await sql`
CREATE TABLE collection_shares (
  collection_id          text NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  recipient_id           text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sealed_collection_key  bytea NOT NULL,
  created_at             timestamptz NOT NULL,
  PRIMARY KEY (collection_id, recipient_id)
)`.execute(db);
  await sql`CREATE INDEX collection_shares_recipient_id_idx ON collection_shares (recipient_id)`.execute(
    db,
  );

  await sql`
CREATE TABLE entries (
  id             text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  owner_id       text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose        text NOT NULL CHECK (purpose IN ('journal', 'link_copy')),
  revision       integer NOT NULL CHECK (revision >= 1),
  metadata_blob  bytea NOT NULL,
  trashed_at     timestamptz NULL,
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL
)`.execute(db);
  await sql`CREATE INDEX entries_trash_purge_idx ON entries (trashed_at) WHERE trashed_at IS NOT NULL`.execute(
    db,
  );
  await sql`CREATE INDEX entries_owner_id_idx ON entries (owner_id)`.execute(db);

  await sql`
CREATE TABLE entry_memberships (
  collection_id            text NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  entry_id                 text NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
  entry_key_by_collection  bytea NOT NULL,
  seq                      bigint NOT NULL,
  added_at                 timestamptz NOT NULL,
  PRIMARY KEY (collection_id, entry_id)
)`.execute(db);
  await sql`CREATE INDEX entry_memberships_feed_idx ON entry_memberships (collection_id, seq)`.execute(
    db,
  );
  await sql`CREATE INDEX entry_memberships_entry_id_idx ON entry_memberships (entry_id)`.execute(
    db,
  );

  await sql`
CREATE TABLE membership_tombstones (
  collection_id  text NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  entry_id       text NOT NULL,
  seq            bigint NOT NULL,
  removed_at     timestamptz NOT NULL,
  PRIMARY KEY (collection_id, seq)
)`.execute(db);

  await sql`
CREATE TABLE media (
  id                  text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  entry_id            text NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
  media_key_by_entry  bytea NOT NULL,
  created_at          timestamptz NOT NULL
)`.execute(db);
  await sql`CREATE INDEX media_entry_id_idx ON media (entry_id)`.execute(db);

  await sql`
CREATE TABLE objects (
  id                   text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  owner_id             text NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  media_id             text NULL REFERENCES media(id) ON DELETE SET NULL,
  variant              text NOT NULL CHECK (variant IN ('original', 'thumbnail')),
  object_key           text NOT NULL UNIQUE,
  size_bytes           bigint NOT NULL CHECK (size_bytes >= 40),
  state                text NOT NULL CHECK (state IN ('pending', 'uploaded', 'deleting')),
  multipart_upload_id  text NULL,
  created_at           timestamptz NOT NULL,
  uploaded_at          timestamptz NULL,
  delete_after         timestamptz NULL,
  CHECK ((state = 'deleting') = (delete_after IS NOT NULL))
)`.execute(db);
  await sql`CREATE UNIQUE INDEX objects_one_pending_per_variant ON objects (media_id, variant)
  WHERE state = 'pending'`.execute(db);
  await sql`CREATE UNIQUE INDEX objects_one_uploaded_per_variant ON objects (media_id, variant)
  WHERE state = 'uploaded'`.execute(db);
  await sql`CREATE INDEX objects_gc_idx ON objects (delete_after) WHERE state = 'deleting'`.execute(
    db,
  );
  await sql`CREATE INDEX objects_pending_idx ON objects (created_at) WHERE state = 'pending'`.execute(
    db,
  );
  await sql`CREATE INDEX objects_owner_id_idx ON objects (owner_id)`.execute(db);

  await sql`
CREATE TABLE public_links (
  id                 text PRIMARY KEY,
  collection_id      text NOT NULL UNIQUE REFERENCES collections(id) ON DELETE CASCADE,
  access_token_hash  bytea NOT NULL CHECK (octet_length(access_token_hash) = 32),
  expires_at         timestamptz NULL,
  max_views          integer NULL CHECK (max_views >= 1),
  view_count         integer NOT NULL DEFAULT 0 CHECK (view_count >= 0),
  created_at         timestamptz NOT NULL
)`.execute(db);
}

export async function down(db: Kysely<unknown>): Promise<void> {
  await sql`DROP TABLE public_links`.execute(db);
  await sql`DROP TABLE objects`.execute(db);
  await sql`DROP TABLE media`.execute(db);
  await sql`DROP TABLE membership_tombstones`.execute(db);
  await sql`DROP TABLE entry_memberships`.execute(db);
  await sql`DROP TABLE entries`.execute(db);
  await sql`DROP TABLE collection_shares`.execute(db);
  await sql`DROP TABLE collections`.execute(db);
  await sql`DROP TABLE invites`.execute(db);
  await sql`DROP TABLE sessions`.execute(db);
  await sql`DROP TABLE users`.execute(db);
}
