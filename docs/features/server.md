# Server

A Fastify API on Postgres that stores ciphertext rows, wrapped keys and accounting, hands out
presigned URLs for the S3 bucket, and runs maintenance jobs. It has no libsodium: it hashes tokens
and login keys with `node:crypto` SHA-256 and never sees plaintext. Code: `apps/server/src/`,
contract in `packages/contract/src/`. Every endpoint: [../design/api.md](../design/api.md); schema:
[../design/data-model.md](../design/data-model.md); running it: [../operations.md](../operations.md).

## Boot and request handling

- `main.ts`: load and validate settings from the environment (a bad value exits with a message
  naming the variable), connect to Postgres, apply migrations, build the app, start the job
  scheduler, listen. SIGTERM/SIGINT stop jobs, drain requests and close the pool, with a forced exit
  after a timeout.
- Every route is defined once in the contract (Zod) and bound with `fastify-type-provider-zod`
  under `/api/v1`. Controllers are a few lines: validated input → one service call → response.
- Requests must arrive over HTTPS (`REQUIRE_HTTPS`; health checks exempt). Security headers via
  helmet; rate limits per route group (keyed by user or IP).
- Logs are JSON (pino) with request id, user id and event name. Authorization headers, link tokens,
  every key, wrap, password, login key and token field are redacted; request bodies are never
  logged.
- Errors are one shape with an `ErrorCode` (for example `REVISION_CONFLICT`, `QUOTA_EXCEEDED`,
  `FEATURE_DISABLED`, `STORAGE_UNAVAILABLE`), never internal messages.
- `GET /api/v1/config` publishes what clients need: limits, flags, sign-up mode, trash days,
  deletion grace days.
- The server is unchanged by v0.4.0 and v0.4.1: the Kotlin app speaks the same API as v0.3.1, so a
  v0.3.1 server needs no update. Caddy, not the API, serves the link viewer page and the "Get the
  Android app" page ([../operations.md](../operations.md)).

## Configuration (selected)

| Variable | Default | Meaning |
|---|---|---|
| `SIGNUP_MODE` | `invite` | `invite` or `open` |
| `FLAG_SHARING`, `FLAG_PUBLIC_LINKS` | off | Shared journals, public links |
| `MAX_MEDIA_PER_ENTRY` | 20 | Items per entry (hard maximum 100) |
| `MAX_UPLOAD_BYTES` | 3.5 GiB | Largest encrypted object |
| `DEFAULT_QUOTA_BYTES` | 100 GiB | Per-user quota unless set by an admin |
| `TRASH_DAYS` | 30 | Days before trashed entries are purged |
| `SESSION_IDLE_DAYS` | 30 | Session lifetime without use |
| `ACCOUNT_DELETION_GRACE_DAYS` | 7 | Grace period before an account is erased |
| `STORAGE_PROVIDER`, `S3_*` | — | Bucket (Garage, B2, R2 or S3), internal and public endpoints |
| `SINGLE_PART_MAX_BYTES`, `MULTIPART_PART_BYTES` | 8 MiB | Upload sizing |
| `UPLOAD_URL_TTL_SECONDS`, `DOWNLOAD_URL_TTL_SECONDS` | 1 day, 15 min | Presigned URL lifetimes |
| `PUBLIC_LINK_PROXY` | off | Stream link downloads through the API (buckets reachable only over a VPN) |

Cross-field rules are checked at boot (for example the upload limit must fit 10,000 parts).

## Entries, collections and sync

- **Entries** carry an opaque metadata blob, a revision, a trash time and memberships in
  collections. Create is idempotent: the same id with identical content returns the existing entry;
  a different one is `ID_CONFLICT`. A journal entry belongs to exactly one home collection (plus
  shared ones when sharing is on); a link copy to exactly one link collection.
- **PATCH** must name the current revision; otherwise `REVISION_CONFLICT` returns the server's
  revision and blob so the client can merge ([sync.md](sync.md#conflicts)).
- **Sequence numbers:** every change to an entry bumps a sequence number in each of its collections
  (collections locked in sorted id order to avoid deadlocks) and stamps the memberships. Removals
  leave tombstones.
- **Diff:** `GET /collections/{id}/diff?since=&limit=` reads memberships and tombstones after
  `since` in one repeatable-read snapshot, at most 500 rows (clamped, never refused), and returns
  the next cursor. Viewers never receive trashed entries. A cursor older than the pruned tombstones
  must resync from 0. `GET /sync/state` lists every readable collection with its sequence.
- Only owners write; readers are owners and share recipients.

## Objects and the bucket

- Object keys are `<ownerId>/<objectId>` (opaque random ids); objects are immutable.
- `POST /objects/presign` (idempotent per id) checks the size limit, ownership, the presign rate
  (1000 per hour) and the quota, reserves the bytes, and returns a single PUT URL (up to 8 MiB) or a
  multipart upload with part URLs. `GET /objects/{id}/parts` resumes a multipart upload;
  `POST /objects/{id}/confirm` verifies the bytes (HEAD, or listing parts and completing) and marks
  the object uploaded; a size mismatch deletes it. `GET /objects/{id}/download` presigns a GET for
  readers.
- Two S3 clients: one on the internal endpoint for server calls, one on the public endpoint for
  presigning. Idempotent calls are retried (3 attempts, jittered); create and complete never are.
- `apply-bucket-cors` sets the bucket's CORS rule for the server's public origin (`PUBLIC_BASE_URL`),
  so the public link page ([public-links.md](public-links.md)) can download from presigned URLs.
  The Android app needs no CORS.

## Jobs

An in-process scheduler runs each job under a Postgres advisory lock, so only one instance runs it
at a time; each processes a batch and reports whether more remains.

| Job | Work |
|---|---|
| `abort-stale-uploads` | Abort uploads still pending after 48 h and mark them for deletion |
| `collect-objects` | Delete objects marked for deletion after the grace period (7 days) from the bucket, then their rows, and release their bytes |
| `reconcile-usage` | Recompute each user's used bytes from their objects (daily) |
| `purge-trash` | Delete entries trashed longer than `TRASH_DAYS` |
| `prune-tombstones` | Remove tombstones older than 90 days and record how far they were pruned |
| `expire-sessions` | Delete expired sessions |
| `erase-accounts` | Erase accounts whose deletion grace period ended |
| `expire-public-links` | Delete expired links and one-view links a day after their view |

## Checks

Integration tests run every route against real Postgres and Garage (`pnpm --filter
@kodakiko/server test:integration`); a provider conformance suite (`test:provider`) checks a real S3
provider's behavior.
