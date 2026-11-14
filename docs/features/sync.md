# Sync

Kodakiko works offline first: every change is written to the local encrypted database together
with an outbox item, and a sync pass pushes the outbox, uploads objects and pulls other devices'
changes. Code: `packages/core/src/sync/`, `apps/client/src/services/sync/`,
`packages/native-upload/`, `packages/contract/src/outbox.ts`. Detailed design:
[../design/sync.md](../design/sync.md).

## When sync runs

A pass starts at unlock, when the app comes to the foreground, when the browser comes back online,
every 60 s while active, 2 s after a local change, on uploader events, on **Sync now** (Settings ›
Sync) and on a pull-to-refresh. Only one pass runs at a time; triggers during a pass run one more
afterwards. A pass is: reconcile uploads → push the outbox → pull.

**Pause sync** (More tab) stops passes until it is turned off; the cache cleanup keeps running.
Local-only journals never sync ([local-mode.md](local-mode.md)).

## The outbox

Item kinds: `createEntry`, `updateEntryMeta`, `addMedia`, `removeMedia`, `trashEntry`,
`restoreEntry`, `deleteEntry`. Items carry ids only; the executor reads the current rows when it
runs, so an item always sends the latest state and two edits collapse into one push. Sharing and
public links are not outbox work: they need the network anyway and run as direct calls.

- **Order:** items run in id order, up to 4 at a time, never two for the same entry, and never past
  an earlier item of the same entry (even a failed one).
- **Executors** read rows in one transaction, make network calls outside it, and write results in
  a new transaction:

| Kind | Work |
|---|---|
| `createEntry` | `POST /entries` with the current metadata, memberships and media; record the posted metadata as the base (revision 1); then upload its objects |
| `updateEntryMeta` | Skipped when the metadata equals the base byte for byte; otherwise `PATCH` with the base revision |
| `addMedia` | `POST /entries/{id}/media`, then upload the objects |
| `removeMedia` | `DELETE /media/{id}` (404 counts as done) |
| `trashEntry`, `restoreEntry` | The POST |
| `deleteEntry` | `DELETE /entries/{id}`, then delete the local rows and files |

- **Failures:**

| Failure | Result |
|---|---|
| Network, 5xx, storage unavailable | Retry with full-jitter backoff (2 s base, 15 min cap); after 8 attempts the item is dead |
| 429 | Retry after `Retry-After` (counts as an attempt) |
| Revision conflict | Merge (below) |
| 404 for update, trash, restore, remove media or delete | Drop the item: the entry is gone on the server and is not brought back |
| 404 for create or add media, quota exceeded, feature disabled, 413, 422, other 4xx | Dead at once |
| 401 | Stop and sign out |

Dead items show in a banner, "Couldn't sync (n) items.", with **Retry** (also Settings › Sync ›
Retry failed). Other banners: "Offline. Changes will sync when you're connected." and "Waiting for
Wi-Fi to upload (n) large items."

## Uploads

Objects upload straight to the bucket through presigned URLs: `POST /objects/presign` (idempotent
per object id) returns one URL or multipart part URLs, the uploader PUTs the encrypted bytes, and
`POST /objects/{id}/confirm` marks the object uploaded. Incomplete multipart uploads resume from
`GET /objects/{id}/parts`; expired URLs are presigned again; a missing local file marks the object
and its outbox item dead. Each pass also reconciles objects that were uploading when the app
stopped.

| Platform | Uploader |
|---|---|
| Android | `packages/native-upload`: a Kotlin foreground service with a progress notification and the app's OkHttp; uploads continue while the app is in the background. Notification permission is asked for on Android 13+ |
| Web | An in-page queue, 3 parts at a time, each part read with `Blob.slice` from OPFS; 5-minute timeout per part |

**Large uploads on Wi-Fi only** (default on; "large" = **Large means**, default 100 MiB) holds
large objects back while on mobile data.

Every change of an object's upload state re-indexes its entry, which drives the gallery's "waiting
to upload" badge.

## Pull

1. `GET /sync/state` lists every readable collection (link collections are ignored) with its
   sequence number.
2. Collections gone on the server are dropped locally, with entries left in no collection.
   New or renamed collections are fetched.
3. Changed collections are pulled from their cursor in pages of 500
   (`GET /collections/{id}/diff?since=…`); each page is applied in one transaction with the new
   cursor. A cursor older than the server's pruned tombstones restarts that collection from 0.
4. Per pulled entry: not known → insert; known and synced → overwrite when the pulled revision is
   not older; local changes pending → keep the local metadata and only take the trash state (the
   server version waits for the merge); an older revision than the local base → ignored.
5. Removed memberships, replaced objects and deleted entries delete their rows; their files are
   deleted after the transaction. Then `entriesChanged` updates the index.

## Conflicts

When a `PATCH` meets a newer server revision, three versions are merged: the ancestor (the last
version both sides saw), local and server.

| Field | Rule |
|---|---|
| Note, time (with offset and time zone), place | Changed on one side → that side; on both → the later `editedAtUtc`; equal → local |
| Tags | Ancestor minus tags removed on either side, plus tags added on either side |
| Media | Removed on either side stays removed; local order, then server-only additions; descriptors from the server when both have them |
| Captions | A caption changed on one side is kept; changed on both → local |
| Favorite | As note |

If the merge equals the server version, the client adopts it; otherwise it seals the merge on top
of the server revision and retries once.

## Keys

Entry keys are resolved from the rows: collection key (wrapped by the master key for own
collections, sealed to the user's public key for shared ones) → entry key → media key. Resolved
keys are cached in memory and wiped on lock.

## Checked only on a phone

The background upload service and its notification, resuming after the app was killed, and
Wi-Fi detection.
