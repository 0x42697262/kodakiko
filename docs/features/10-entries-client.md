# 10 — Entries client: save, edit, trash, restore, delete; tags; compose screen

Status: draft 1 (2026-11-11).

Goal: the capture tray becomes a saved, encrypted entry in one local transaction and syncs; any
entry can be edited (note, tags, time, location, media order, add or remove media), trashed,
restored and permanently deleted, alone or several at once, fully offline. Includes the compose
screen `/entry/new`, the reusable tag input, and the time and location editors (spec 13's edit
screen reuses them). The gallery is spec 11; the markdown preview and entry viewer are spec 13.

Read first: `docs/design/data-model.md` §§4, 6; `docs/design/crypto.md` §§3-4, 6; `docs/design/sync.md`
§§2-3, 5; `docs/design/client.md` §§2-3; specs 08 (executors, entry keys, `normalizeTag`) and 09
(tray, `ImportedMedia`); decisions D-14, D-16, D-18, D-44, D-45, D-55, D-57, D-58. Depends on 08
and 09.

## 1. Core additions

`packages/core/src/domain/tags.ts` (exists from spec 08 with `normalizeTag`) gains:

```ts
export function addTag(tags: readonly string[], raw: string): { readonly tags: readonly string[]; readonly rejected: 'empty' | 'tooLong' | 'tooMany' | 'duplicate' | null };
export function removeTag(tags: readonly string[], tag: string): readonly string[];
export function sortTags(tags: readonly string[]): readonly string[];
```

`addTag` normalizes, rejects empty, longer than `TAG_MAX_CHARS`, already present, or when the
list has `TAGS_PER_ENTRY_MAX` items; returns the sorted list. `sortTags` uses plain code-unit order
(`a < b`), the same order the merge uses.

`packages/core/src/domain/entry-time.ts`:

```ts
export function localDateOf(takenAtUtc: string, utcOffsetMinutes: number): string;
export function localTimeOf(takenAtUtc: string, utcOffsetMinutes: number): string;
export function localPartsToUtc(date: string, time: string, utcOffsetMinutes: number): string | null;
export function currentTimeZone(): string | null;
```

- `localDateOf` → `YYYY-MM-DD` of `takenAtUtc + offset` (D-45); `localTimeOf` → `HH:MM`.
- `localPartsToUtc`: `date` must match `^\d{4}-\d{2}-\d{2}$` and be a real calendar date, `time`
  `^\d{2}:\d{2}$` with hours 00-23 and minutes 00-59; else `null`. Result = that local time minus
  the offset, as ISO UTC with milliseconds `.000Z`.
- `currentTimeZone` = `Intl.DateTimeFormat().resolvedOptions().timeZone` or `null` if missing.

`packages/core/src/domain/entry-metadata.ts`:

```ts
export type EntryDraft = { readonly media: readonly MediaDescriptorV1[]; readonly times: readonly ResolvedTime[]; readonly note: string; readonly tags: readonly string[]; readonly mediaLocations: readonly (GeoPoint | null)[]; readonly deviceLocation: { readonly point: GeoPoint; readonly accuracyMeters: number | null } | null; readonly timeZone: string | null; readonly nowUtc: string };
export function buildNewEntryMetadata(draft: EntryDraft): EntryMetadataV1;
export type EntryEdit = { readonly note?: string; readonly tags?: readonly string[]; readonly time?: { readonly takenAtUtc: string; readonly utcOffsetMinutes: number }; readonly location?: EntryMetadataV1['location']; readonly mediaOrder?: readonly string[]; readonly addMedia?: readonly MediaDescriptorV1[]; readonly removeMediaId?: string };
export function applyEntryEdit(current: EntryMetadataV1, edit: EntryEdit, nowUtc: string): EntryMetadataV1;
```

- `buildNewEntryMetadata`: `takenAtUtc`/`utcOffsetMinutes` from `earliestTime(times)`; location =
  the location of the media with the earliest time among those that have one (source `exif`), else
  `deviceLocation` (source `device`), else `null` (D-55); `createdAtUtc = editedAtUtc = nowUtc`;
  tags sorted; result validated with `EntryMetadataV1`.
- `applyEntryEdit`: applies each present field; `mediaOrder` must be a permutation of the current
  media ids (else throws `InvalidEditError`); `removeMediaId` must not remove the last media
  (D-44, `InvalidEditError`); `addMedia` appends; `editedAtUtc = nowUtc`; validated.

Tests: table-driven for every function above (incl. leap day, 23:59, offset +14:00/−12:00, a
removal of the last media, a non-permutation order).

## 2. Client entries service (`src/services/entries/entries-service.ts`)

```ts
export type EntriesService = {
  readonly createFromTray: (input: { readonly note: string; readonly tags: readonly string[]; readonly time: { readonly takenAtUtc: string; readonly utcOffsetMinutes: number } | null }) => Promise<string>;
  readonly edit: (entryId: string, edit: EntryEdit) => Promise<void>;
  readonly addMedia: (entryId: string, items: readonly ImportedMedia[]) => Promise<void>;
  readonly removeMedia: (entryId: string, mediaId: string) => Promise<void>;
  readonly trash: (entryIds: readonly string[]) => Promise<void>;
  readonly restore: (entryIds: readonly string[]) => Promise<void>;
  readonly deletePermanently: (entryIds: readonly string[]) => Promise<void>;
  readonly addTagToMany: (entryIds: readonly string[], tag: string) => Promise<BulkResult>;
  readonly removeTagFromMany: (entryIds: readonly string[], tag: string) => Promise<BulkResult>;
  readonly readMetadata: (entryId: string) => Promise<EntryMetadataV1>;
};
export type BulkResult = { readonly changed: number; readonly skipped: number };
```

Dependencies: `sodium`, `rowStore`, `fileStore`, `entryKeys` (spec 08), `captureTray` (spec 09),
`sync` (spec 08, `notifyLocalWrite`), `events` (`entriesChanged`), `clock`, `randomId`, `account`.

Every method follows the 07a IndexedDB rule: read and decrypt first, compute and seal, then write
all rows in **one** RowStore transaction, then `notifyLocalWrite()` and `entriesChanged(ids)`.

### 2.1 `createFromTray`

1. Tray must have ≥ 1 item (else `EmptyTrayError`); account unlocked.
2. `homeCollectionId` from kv; `collectionKey = entryKeys.collectionKey(homeCollectionId)`.
3. `entryId = randomId()`, `entryKey = randomBytes(32)`.
4. Metadata: `buildNewEntryMetadata` from the tray (`time` argument, when the user edited it,
   overrides the computed time); `metadataBlob = sealJson(sodium, entryKey, metadata,
   AD_CONTEXTS.entryMetadata, entryId)`.
5. Wraps: `entryKeyByCollection = wrapKey(sodium, collectionKey, entryKey,
   AD_CONTEXTS.entryKeyByCollection, `${homeCollectionId}/${entryId}`)`; per media `wrapKey(sodium,
   entryKey, mediaKey, AD_CONTEXTS.mediaKeyByEntry,
   `${entryId}/${mediaId}`)`.
6. One transaction: `entries` row (`ownerId` = user, `purpose 'journal'`, `revision 1`,
   `metadataBlob`, `baseMetadataBlob null`, `baseRevision 0`, `localState 'pending_create'`,
   `trashedAt null`, `createdAt`/`updatedAt` now); `memberships` row (seq 0); `media` rows; two
   `objects` rows per media (`variant`, `sizeBytes` = ciphertext size, `plaintextSize`,
   `localState 'present'`, `uploadState 'local'`, `lastAccessAt` now); outbox `createEntry {
   entryId }`.
7. `captureTray.clear(false)`; wipe `entryKey` and the media keys held by the tray items.
8. Return `entryId`.

### 2.2 `edit`

1. Load the entry row; owner check (viewers cannot edit, D-39: `ReadOnlyEntryError`).
2. `current = readMetadata(entryId)` (opens the local blob with the entry key).
3. `next = applyEntryEdit(current, edit, now)`; equal by `metadataEquivalent` → return (no write).
4. Seal with the entry key; transaction: update `metadataBlob`, `updatedAt`; `localState` stays
   `pending_create` if it was, else `pending_update`; enqueue `updateEntryMeta { entryId }` **only
   if** no queued `updateEntryMeta` item for this entry exists (`outbox.all()` filtered; the
   executor always pushes the latest blob, spec 08 §1). This also applies to `pending_create`
   entries: the update item waits behind `createEntry` and becomes a no-op if the create already
   posted the latest blob (spec 08 §3.5).

### 2.3 `addMedia` / `removeMedia`

- `addMedia`: items come from a tray session opened for this entry (spec 13). Wrap each media key
  with the entry key; transaction: media rows, object rows (as 2.1 step 6), metadata updated with
  `addMedia` (sealed), outbox `addMedia { entryId, mediaId }` per media **then** one
  `updateEntryMeta` (same-entry order guarantees the server knows the media before the metadata
  references it).
- `removeMedia`: `applyEntryEdit` with `removeMediaId` (refuses the last media); transaction:
  updated metadata, outbox `updateEntryMeta` **then** `removeMedia { entryId, mediaId }` (the
  metadata stops referencing the media before the server deletes it), delete the local media row
  and its object rows; after commit delete the local files and the plain thumbnail.

### 2.4 `trash` / `restore` / `deletePermanently`

For each id (owner only; non-owned ids are skipped and counted): transaction per call covering all
ids:
- `trash`: `trashedAt = now`, outbox `trashEntry`; `restore`: `trashedAt = null`, outbox
  `restoreEntry`. If an opposite item for the same entry is still queued, **delete** that item
  instead of adding a new one (trash then restore offline = no network call).
- `deletePermanently`: delete local entry, memberships, media, object rows and **all** outbox items
  of the entry, then: if the server never received the entry (`baseRevision === 0`: the
  `createEntry` POST has not succeeded yet) enqueue nothing; otherwise enqueue `deleteEntry`. Note
  that `localState 'pending_create'` is **not** the test: the entry stays `pending_create` while its
  uploads run after a successful POST (spec 08 §3.5). The server's `abort-stale-uploads` and object
  GC clean any presigned-but-unfinished uploads. After commit delete files and plain thumbnails.

Permanent delete from the UI is only offered for trashed entries and in multi-select (with a
confirmation sheet, §4.3).

### 2.5 `addTagToMany` / `removeTagFromMany`

For each owned id: `edit(id, { tags })` computed with `addTag`/`removeTag`; entries where the tag
is already present/absent are `skipped`. Runs sequentially (each `edit` is its own transaction);
the outbox carries one `updateEntryMeta` per changed entry (D-57).

## 3. Tag input (`src/features/tags/tag-input.tsx`)

Props: `tags`, `onChange(tags)`, `suggestions: (prefix: string) => readonly string[]`,
`maxTags`. A text field; Enter, comma or the "Add" button adds the typed text via `addTag`
(rejection → inline hint: `tags.empty` "Type a tag first.", `tags.tooLong` "Tags can be up to 64
characters.", `tags.tooMany` "An entry can have up to 50 tags.", `tags.duplicate` "Already
added."); chips with a remove button (`accessibilityLabel` "Remove tag (name)"); a suggestion row
under the field showing up to `TAG_SUGGESTIONS_MAX = 8` results of `suggestions(prefix)` for the
current text (tap → add). Backspace on an empty field removes the last chip (web and Android
hardware keyboards).

`src/services/tags/tag-suggestions.ts`: `TagSuggestionSource = { suggest(prefix: string):
readonly string[] }`. This spec registers an **empty** source in the app context; spec 11 replaces
it with the index-backed one (D-16: own past tags, ranked by use count).

## 4. Screens and components

### 4.1 `/entry/new` (compose)

Reached from `/capture` "Next". Content top to bottom:
- Media strip from the tray (thumbnails, tap selects; "Move Left"/"Move Right" buttons for the
  selected item reorder the tray; "Remove").
- **When**: computed date and time shown as text ("Today, 14:05" style via `formatEntryTime`, §4.4)
  with "Change" → time editor (§4.4).
- **Where**: location row (§4.5) when a location exists.
- **Note**: multi-line plain text field, placeholder "What happened?", `NOTE_MAX_CHARS` limit with a
  counter shown after 90%.
- **Tags**: tag input.
- Primary button "Save" → `createFromTray` → navigate to `/` (gallery) and show a toast-style
  `Banner` "Saved." for `SAVED_BANNER_MS = 2000`. Back/close returns to `/capture` with the tray
  intact.

### 4.2 Multi-select actions sheet (component for spec 11)

`src/features/entries/bulk-actions-sheet.tsx`: props `{ ids, mode: 'journal' | 'trash' |
'collectionOwner', collectionId?: string }`. `collectionOwner` (spec 17) offers only "Remove from This
Journal".
`journal` offers "Add Tag…", "Remove Tag…" (each opens a sheet with a single tag field and
suggestions), "Move to Trash" and, once spec 17 is built and either sharing flag is on, "Share…"
(opens `ShareEntriesSheet`); `trash` (used by spec 11's trash view) offers "Restore" and
"Delete Permanently…". Results: `Banner` "(n) entries updated."
plus "(k) skipped" when `skipped > 0`.

### 4.3 Confirmations

Only permanent deletion asks: sheet "Delete (n) entries permanently? This can't be undone." with
"Delete" (danger) and "Cancel". Trash never asks (it is the undo path, guidelines 7.1).

### 4.4 Time editor (`src/features/entries/time-editor.tsx`) and formatting

- Two text fields: "Date" (`YYYY-MM-DD`) and "Time" (`HH:MM`), prefilled with `localDateOf` /
  `localTimeOf` in the entry's offset; "Use original time" button resets to the computed time;
  "Done" validates with `localPartsToUtc` (invalid → inline "Use the format 2026-11-11 and 14:05.").
  The offset never changes in the editor (D-58).
- `src/features/entries/format-entry-time.ts`: `formatEntryTime(takenAtUtc, offset, nowUtc,
  deviceOffset)` → "Today, 14:05" / "Yesterday, 09:30" / "Mon, Nov 9, 18:00" (this year) /
  "Nov 9, 2024, 18:00"; uses `Intl.DateTimeFormat('en-US', …)` with `timeZone: 'UTC'` on the
  offset-shifted time (so the entry's own local time is shown). Pure, tested.

### 4.5 Location row (`src/features/entries/location-row.tsx`)

Shows `lat, lon` with 5 decimals and the source ("from photo" / "from device"), an "Open in Maps"
button (Android: `Linking.openURL(\`geo:${lat},${lon}?q=${lat},${lon}\`)`; web:
`https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=16/${lat}/${lon}` in a new tab with
`rel="noopener noreferrer"`), and "Remove Location" (edit with `location: null`). URL templates in
`keys/`. No in-app map (D-25).

## 5. Tests

1. Core tests (§1).
2. Entries service (Node, memory adapters, wrappers Sodium, fake sync, fixed clock):
   - `createFromTray` writes exactly: 1 entry (`pending_create`, `baseRevision 0`), 1 membership,
     N media, 2N objects, 1 outbox item; the blob opens with the entry key from the wrap; tray
     cleared without deleting files.
   - edit twice offline → one queued `updateEntryMeta`; editing a `pending_create` entry keeps
     `pending_create` and queues one `updateEntryMeta`; an equivalent edit writes nothing.
   - edit made between the `createEntry` POST and its upload confirmation (fake executor run in
     two steps) → after both items run, the server mock received POST(blob A) then PATCH(blob B),
     and the entry ends `synced` with base = B.
   - trash then restore offline → no outbox items left; trash after sync → one `trashEntry`.
   - delete a never-synced entry → no outbox items, files deleted; delete a synced entry → one
     `deleteEntry`, local rows and files gone.
   - removeMedia of the last media → `InvalidEditError`; of another → outbox order
     `updateEntryMeta` then `removeMedia`.
   - addMedia → outbox order `addMedia…` then `updateEntryMeta`.
   - addTagToMany on 3 entries where 1 already has the tag → `changed 2, skipped 1`.
   - viewer entry (membership only in a `shared` collection with role viewer) → `ReadOnlyEntryError`.
3. e2e (`tests/e2e`, extends spec 08): replace the helper `createEntryLocally` with the real
   `entriesService.createFromTray` (tray filled through `importOne` with a fake toolkit); scenario
   1 of spec 08 passes unchanged; new scenario: device A edits tags of 3 entries via
   `addTagToMany` offline → sync → device B sees the tag on all 3.
4. `formatEntryTime` table (today, yesterday, this year, last year, offsets ±).

## 6. Verification checklist

- [ ] `pnpm verify` and e2e green (paste summaries).
- [ ] Android: capture 2 photos → Next → note + 2 tags → Save → gallery placeholder shows "Saved.";
      with network on, the server shows the entry and 4 uploaded objects within a minute (admin SQL
      count or server log `object.confirmed` ×4).
- [ ] Airplane mode: save an entry, edit its note, trash it, restore it → all local; network on →
      server ends with the latest note, not trashed, revision 1 (the create carries the edited note,
      spec 08 §3.5).
- [ ] Web: same flow with a pasted screenshot.
- [ ] Time editor: invalid "2026-02-30" refused; a changed time moves the entry's local date.
- [ ] Location row: "Open in Maps" opens the system maps app (Android) / OpenStreetMap (web).
