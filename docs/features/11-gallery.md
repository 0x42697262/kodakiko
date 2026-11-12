# 11 — Gallery: in-memory index, grid by day, filters, search, multi-select, trash view

Status: draft 1 (2026-11-11).

Goal: after unlock the app decrypts every entry's metadata once into an in-memory index (D-37) and
shows the journal as a grid of entries under day headers, newest first, with an "On this day"
card, AND-combined tag filters, a date range, note search, multi-select actions, a trash view, the
sync banner and the capture button. Thumbnails come from local data only in this spec; spec 14
adds downloading and caching of missing thumbnails behind the same port.

Read first: `docs/design/client.md` §§2-3 (index), `docs/design/data-model.md` §6, specs 08
(`entriesChanged`, entry keys, sync banner), 10 (tags, bulk sheet, `formatEntryTime`);
decisions D-13, D-14, D-16, D-17, D-21, D-22, D-23, D-37, D-45, D-46, D-57. Depends on 10.

## 1. Core index (`packages/core/src/index/`)

### 1.1 Types (`types.ts`)

```ts
export type IndexedEntry = {
  readonly id: string;
  readonly isOwn: boolean;
  readonly collectionIds: readonly string[];
  readonly trashed: boolean;
  readonly unreadable: boolean;
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly localDate: string;
  readonly monthDay: string;
  readonly tags: readonly string[];
  readonly coverMediaId: string | null;
  readonly mediaCount: number;
  readonly hasVideo: boolean;
  readonly notePreview: string;
  readonly pending: boolean;
};
export type GalleryFilter = {
  readonly scope: { readonly kind: 'home' } | { readonly kind: 'collection'; readonly collectionId: string };
  readonly view: 'journal' | 'trash';
  readonly tags: readonly string[];
  readonly fromDate: string | null;
  readonly toDate: string | null;
  readonly text: string;
};
export type DaySection = { readonly localDate: string; readonly entryIds: readonly string[] };
```

`unreadable` entries (metadata failed to decrypt or validate, `security.md` §7 drill 5) carry
placeholder values (`takenAtUtc` = the row's `createdAt`, empty tags and note) and are shown as an
"unreadable" tile; they are never matched by tag or text filters.
`notePreview` = the first `NOTE_PREVIEW_CHARS = 140` characters of the note with markdown syntax
characters left as typed (rendering is spec 13).

### 1.2 Index (`gallery-index.ts`)

```ts
export type GalleryIndex = {
  readonly upsert: (entry: IndexedEntry, noteText: string) => void;
  readonly remove: (entryId: string) => void;
  readonly clear: () => void;
  readonly get: (entryId: string) => IndexedEntry | null;
  readonly size: () => number;
  readonly query: (filter: GalleryFilter) => readonly DaySection[];
  readonly tagSuggestions: (prefix: string, limit: number) => readonly string[];
  readonly onThisDay: (todayLocalDate: string, scope: GalleryFilter['scope']) => readonly OnThisDayGroup[];
  readonly daysWithEntries: (yearMonth: string, scope: GalleryFilter['scope']) => ReadonlyMap<string, string>;
  readonly version: () => number;
};
export type OnThisDayGroup = { readonly yearsAgo: number; readonly entryIds: readonly string[] };
export function createGalleryIndex(options: { readonly homeCollectionId: string }): GalleryIndex;
```

Internal structures (`client.md` §3.1): `entries: Map<id, IndexedEntry>`, `byTag: Map<tag,
Set<id>>`, `tagCounts: Map<tag, number>` (non-trashed own entries only), `words: Map<token,
Set<id>>`, `byMonthDay: Map<MM-DD, Set<id>>`, and a sorted array `orderedIds` (by `takenAtUtc`
descending, then `id` ascending) rebuilt lazily when `dirty` is set by `upsert`/`remove`.
`version()` increments on every change (hooks use it to re-query).

Rules:
- `upsert` replaces any previous version of the entry in every structure.
- Tokenization (`tokenize.ts`, also exported): `text.normalize('NFKC').toLowerCase()`, split on
  `/[^\p{L}\p{N}]+/u`, drop tokens shorter than `TOKEN_MIN_CHARS = 2`, deduplicate, keep at most
  `MAX_INDEX_TOKENS_PER_ENTRY = 500` (the first ones in note order).
- `query`: start from `orderedIds`; keep entries in scope (`home` = entries whose
  `collectionIds` contains `options.homeCollectionId`; `collection` = contains that id); `view` journal → not
  trashed, trash → trashed; every filter tag must be present (AND, D-17); `fromDate ≤ localDate ≤
  toDate` when set (string comparison of `YYYY-MM-DD`); text: tokenize the query, every query token
  must prefix-match at least one indexed token of the entry (AND across query tokens; a token
  matches entries via the union of `words` keys starting with it). Then group consecutive ids by
  `localDate` into `DaySection`s (already in descending order).
- `tagSuggestions(prefix, limit)`: normalize the prefix; tags starting with it, sorted by count
  descending then alphabetically; empty prefix → the most used tags.
- `onThisDay(today, scope)`: entries in scope, not trashed, with `monthDay` = today's `MM-DD` and a
  year **before** today's year, grouped by `yearsAgo` ascending (1 year ago first), each group in
  time order (newest first).
- `daysWithEntries('YYYY-MM', scope)`: map `localDate → cover entry id` (the newest non-trashed
  entry of that day in scope); used by spec 12.

### 1.3 Tests (`packages/core/test/index/`)

Table-driven, with 50 synthetic entries: ordering and ties; day grouping across UTC midnight with
offsets (an entry at 2026-11-10T23:30Z with offset +60 belongs to 2026-11-11); tag AND; date range
inclusive; text prefix AND (`"bea sun"` matches "beach sunset"); trash view; scope home vs
collection; suggestions ranking; on-this-day (excludes this year, groups by years); upsert of a
changed entry removes old tags/words; unreadable entries excluded from tag/text queries but present
in unfiltered queries; `version` increments. Performance test: 20,000 synthetic entries with 30
words each → `query` with one tag and a 3-letter text prefix runs under 50 ms in Node (report the
measured time; not a hard CI threshold).

## 2. Client index service (`src/services/index/index-service.ts`)

```ts
export type IndexService = {
  readonly build: () => Promise<IndexBuildReport>;
  readonly index: () => GalleryIndex | null;
  readonly status: () => 'idle' | 'building' | 'ready';
  readonly subscribe: (listener: () => void) => () => void;
  readonly clear: () => void;
};
export type IndexBuildReport = { readonly entries: number; readonly unreadable: number; readonly durationMs: number };
```

- `build()` (called on the transition to `unlocked`, before the gallery renders its list):
  1. Read all `entries`, `memberships`, `media` and `objects` rows in **one** RowStore transaction
     (read only; nothing else awaited inside it, 07a IndexedDB rule); decrypt afterwards, outside
     the transaction.
  2. For each entry: resolve the entry key (`entryKeys.entryKey`, spec 08), `openJson` the
     metadata; success → build `IndexedEntry` (`coverMediaId = media[0].mediaId`, `hasVideo`,
     `pending` = `localState !== 'synced'` or any object `uploadState !== 'uploaded'`); failure →
     `unreadable`.
  3. Every `INDEX_BUILD_YIELD_EVERY = 200` entries yield to the event loop (keeps the UI and the
     spinner alive).
  4. Log `index.built` (dev console only in the client) with the report; keep the report for the
     diagnostics screen.
- Incremental: subscribe to `entriesChanged(ids)`; debounce `INDEX_UPDATE_DEBOUNCE_MS = 100`; for
  each id re-read its rows and `upsert` (or `remove` when the entry row is gone).
- Also re-derive `pending` when uploads finish: the sync service emits `entriesChanged` for the
  entry when an object's `uploadState` changes (add this emit to spec 08's upload coordination:
  one line, listed in §6 as a change to spec 08).
- `clear()` on lock and sign-out (07a account state transitions); keys are cleared by
  `entryKeys.clear()` at the same time.
- Tag suggestions: `createIndexTagSuggestionSource(indexService)` implements spec 10's
  `TagSuggestionSource` with `tagSuggestions(prefix, TAG_SUGGESTIONS_MAX)` and replaces the empty
  source in the app context.

Hooks (`src/features/gallery/hooks.ts`): `useIndexVersion()` (re-renders on `version` change),
`useGallerySections(filter)` (memoized by `version` + filter), `useIndexedEntry(id)`,
`useOnThisDay()`, `useIndexStatus()`.

## 3. Thumbnail port (local-only implementation in this spec)

`src/services/thumbnails/thumbnail-source.ts`:

```ts
export type ThumbnailRef = { readonly uri: string; readonly release: () => void };
export interface ThumbnailSource {
  readonly get: (entryId: string, mediaId: string) => Promise<ThumbnailRef | null>;
  readonly clear: () => void;
}
```

`createLocalThumbnailSource({ fileStore, rowStore, entryKeys, sodium })`:
1. Android plain mode: `fileStore.plainThumbnailUri(mediaId)` exists → `{ uri, release: noop }`.
2. Else the media's thumbnail object present locally (`objects.localState 'present'`) → read the
   whole object (`readRange(0, size)`), decrypt with the thumbnail variant key
   (`createStreamDecryptor` with `plaintextSize` from the object row), then Android: data URI
   `data:image/webp;base64,…`; web: `URL.createObjectURL(new Blob([bytes], { type: 'image/webp' }))`.
   Kept in an LRU of `THUMBNAIL_MEMORY_CACHE_ITEMS = 300` (`storage.md` §6); eviction and `clear()`
   call `URL.revokeObjectURL` on web.
3. Else `null` → the tile shows a neutral placeholder. Spec 14 wraps this source with one that
   downloads missing thumbnails.

`src/features/gallery/thumbnail.tsx`: `<Thumbnail entryId mediaId size />` → `useThumbnail` hook
(effect fetches the ref, releases on unmount) → `expo-image` `<Image source={{ uri }}
contentFit="cover" cachePolicy="memory" transition={0} recyclingKey={mediaId} />`; placeholder =
`surface` color box; unreadable entries show a "lock" glyph text "Can't open" in `textMuted`.

## 4. Screens

### 4.1 Gallery `/(tabs)/index`

Layout top to bottom:
1. Header: title "Journal"; buttons "Search" (toggles a search field in the header), "Filter"
   (opens the filter sheet, shows a dot when filters are active), overflow menu with "Trash".
2. `SyncStatusBanner` (spec 08).
3. Active filter chips (each tag, the date range, the search text) with a remove ✕ each and "Clear
   All".
4. List (FlashList v2): items are a flattened sequence of `{ type: 'onThisDay' }` (only when no
   filter is active and `onThisDay` is non-empty), `{ type: 'header', localDate }`, and
   `{ type: 'row', entryIds }` with up to `columns` ids; `getItemType` by `type`. `columns =
   max(GRID_MIN_COLUMNS = 3, floor(width / GRID_TILE_MIN_PX = 110))`, recomputed on resize.
   Day header text: `formatDayHeader(localDate, today)` → "Today", "Yesterday", "Monday, Nov 9"
   (this year), "Nov 9, 2024" (pure, tested).
5. Tile: square cover thumbnail; a "+(n−1)" badge when `mediaCount > 1`; a small play glyph when
   `hasVideo`; a small dot when `pending` (accessibility label "Not synced yet"). Tap → `/entry/[id]`
   (route placeholder created here showing the title "Entry" and a back button until spec 13).
   Long-press (Android) or Ctrl/Cmd+click (web) → selection mode with this tile selected.
6. Floating "+" button (accessibility label "Capture") → `/capture`. Hidden in selection mode.

Empty states: no entries → "No entries yet. Tap + to capture your first moment."; filters with no
result → "No entries match these filters." with "Clear Filters".

While `useIndexStatus()` is `building`: a centered `Spinner` with "Opening your journal…".

### 4.2 Selection mode

- Header becomes "(n) selected" with "Cancel" and "Actions" (opens spec 10's bulk sheet with the
  selected ids). Tap toggles a tile; the FAB hides.
- Web: a checkbox appears on tile hover; Shift+click selects the range between the last clicked
  tile and this one in list order (D-23).
- Leaving the screen or locking clears the selection.

D-21's jump-to-date is met by the filter's date range here and by "Jump to…" in the calendar
(spec 12).

### 4.3 Filter sheet

Tags: `TagInput` with index suggestions (chips = filter tags, AND). Dates: "From" and "To" text
fields (`YYYY-MM-DD`, validated like D-58, either may be empty; From > To → inline "The start date
must be before the end date."). Buttons "Apply" and "Reset". The filter lives in a gallery store
in memory (survives navigation, cleared on lock); not persisted.

### 4.4 "On this day" card

Horizontal list of groups: label "(n) year(s) ago" and up to `ON_THIS_DAY_MAX_TILES = 10` tiles per
group; tap → entry. Hidden when empty or when any filter is active.

### 4.5 Trash `/trash`

Same grid with `view: 'trash'`; a `Banner` "Entries in the trash are deleted after (trashDays)
days." (`trashDays` from `PublicConfigDto`); selection actions "Restore" and "Delete
Permanently…" (spec 10 services and confirmation). No FAB, no On this day.

## 5. Diagnostics additions (dev only)

"Index benchmark": generates 20,000 synthetic entries **in memory only** (random metadata sealed
with fresh keys through the same code path, not written to the RowStore), then times the decrypt
+ index build exactly as `build()` does, and one representative `query`. Shows entries/s, total ms
and the D-46 verdict against `UNLOCK_BUDGET_MS = 2000`. Report Android and web numbers; if the
build exceeds the budget on the mid-range phone, stop and report to Opus (the encrypted index
snapshot of D-46 becomes a new spec).

## 6. Changes to earlier specs (already applied there)

- Spec 08 §3.6: after every `uploadState` change of an object, emit `entriesChanged([entryId])`.
- Spec 03 / api §11: `PublicConfigDto.trashDays`.

## 7. Tests

1. Core index tests (§1.3).
2. `formatDayHeader` table.
3. Index service (Node, memory adapters, wrappers Sodium): build over 3 entries (one tampered blob
   → `unreadable: 1`); `entriesChanged` for an edited entry updates tags within the debounce;
   deleted entry removed; `clear()` empties it.
4. Thumbnail source: plain file → uri; encrypted local object → decrypts to the original bytes
   (compare the blob/data URI payload); LRU evicts the 301st and releases it.

## 8. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Android with 30 real entries: gallery groups by day, "Today"/"Yesterday" headers, tiles show
      covers; scroll is smooth (no blank tiles after the first second; report subjective result).
- [ ] Tag filter with two tags shows only entries with both; date range and search narrow further;
      chips remove each filter.
- [ ] Multi-select: long-press, select 3, Add Tag "beach" → all 3 tagged, filter by it shows 3.
- [ ] Web: Shift+click range selection; Ctrl/Cmd+click starts selection.
- [ ] On this day: create an entry with its time set to the same date last year → the card shows it.
- [ ] Trash: trash 2 entries → they leave the gallery and appear in `/trash`; restore one.
- [ ] Diagnostics index benchmark on Android and web: paste numbers and the D-46 verdict.
- [ ] Lock → unlock rebuilds the index (spinner, then the grid).
