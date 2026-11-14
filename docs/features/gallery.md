# Gallery and the local index

The home tab lists every entry of the journal from an in-memory index that is built from the
encrypted local database at unlock. Code: `packages/core/src/index/`,
`apps/client/src/services/index/`, `apps/client/src/features/gallery/`.

## The index

At unlock, before the gallery shows its list, `build()` reads every entry, membership, media and
object row in one read-only transaction, then decrypts outside it (yielding to the UI every 200
entries) and fills `GalleryIndex`. Each `IndexedEntry` holds: id, own or shared, collection ids,
trashed, unreadable, time and offset, local date (`YYYY-MM-DD` in the entry's own offset, so an
entry at 23:30 UTC with offset +60 belongs to the next day), month-day, tags, cover media id
(item 1), item count, video count and length, original bytes, the first 140 characters of the
note, favorite and pending (not synced yet, or an upload still running).

- **Updates:** `entriesChanged(ids)` events (from edits, sync and finished uploads) re-read those
  entries after 100 ms and replace them in the index. Every change increments `version()`, which
  the screens use to re-query.
- **Search text** is the note plus every caption, tokenized (NFKC, lower case, split on anything
  that is not a letter or digit, tokens of at least 2 characters, at most 500 per entry). A query
  matches when every query word is a prefix of some word of the entry ("bea sun" finds "beach
  sunset").
- **Unreadable entries** (metadata that fails to decrypt or validate) show as an "unreadable" tile
  and never match tag or text filters.
- The index is cleared on lock and sign-out. Settings › Advanced › **Rebuild index** rebuilds it and
  reports how many entries it read and how long it took.
- 20,000 synthetic entries with 30 words each query in well under 50 ms in Node.

## The gallery screen

**App bar:** title "Journal" with an item-count pill (when **Show number of items** is on), Search,
Filter (a dot when filters are active) and ⋮ (Random entry, Lock Now, Trash, Sign Out). Scrolling down hides the
app bar, tag tabs and bottom bar (**Hide bars on scroll**).

**Tag tabs** (**Show tag tabs**): All, Favorites, then the tags in the order set in Settings › Tags,
each with a count pill when counts are on. Favorites drives the favorite filter.

**Filter chips** under the bar show each active filter with a remove ✕, and **Clear All**.

**The list** (FlashList): an On This Day card, then group headers and rows of tiles. Grouping
(sheet › Group): **Day** (default: "Today", "Yesterday", "Monday, Nov 9", "Nov 9, 2024"),
Month, Year, Tag (one group per tag, untagged last) or None.

**Tiles** show the cover thumbnail and, depending on the display mode, the time and note preview.
Badges (each can be turned off): note, video, item count ("+n"), favorite, waiting to upload, and
kept offline (off by default). Tap opens the entry; long-press (Android) or Ctrl/Cmd+click (web) starts selection; on the
web a checkbox shows on hover and Shift+click selects a range.

**Selection mode:** the bar shows "(n) selected" with select all, select inverse and actions
(the bulk sheet, [edit-page.md](edit-page.md#bulk-actions)); entering it gives a haptic tick.
Leaving the screen or locking clears the selection.

**Pull down** to sync now (server accounts with sync running).

**The round button** opens the camera ([capture.md](capture.md)).

**Empty states:** "No entries yet. Tap + to capture your first moment."; with filters, "No entries
match these filters." and **Clear Filters**. While the index builds: "Opening your journal…".

**Snackbars** at the bottom: "Saved." after leaving a new entry, "Moved to trash." with **Undo**,
"That entry no longer exists." when an opened entry was gone.

## Filter, Sort, Display, Group sheet

A bottom sheet with underlined tabs, closed by dragging its handle down or tapping outside.

| Tab | Options |
|---|---|
| Filter | Tri-state rows (off → include ✓ → exclude ✗): Has video, Has note, Several photos, Shared, Waiting to upload, Favorite. Tags (entries that have all the checked tags, picked from the tag list) and a date range From / To (`YYYY-MM-DD`; "The start date must be before the end date."), with Apply and Reset |
| Sort | Date taken (default, newest first), Note length, Number of photos, Random (reshuffles when chosen); each with a direction |
| Display | Display mode chips (Comfortable grid, Compact grid, Photo-only grid, List), items per row (a dot slider, Auto or 1–10, separately for portrait and landscape), badge checkboxes, Show tag tabs, Show number of items |
| Group | Day, Month, Year, Tag, None |

Everything except the text search, tags and dates is a device setting and survives restarts; the
filter itself lives in memory until lock.

## On This Day

A horizontal card of groups "(n) year(s) ago", each with up to 10 entries from today's month and
day in earlier years, newest first. Hidden when any filter is active, or with **On This Day** off.

## Trash

More › Trash (or the gallery ⋮) shows trashed entries in the same grid with "Entries in the trash
are deleted after (n) days." (the server's `trashDays`). Selection offers Restore and Delete
Permanently…. No round button and no On This Day.

## Shared journals in the gallery

Entries shared with you appear in the gallery unless **Hide shared journals** (More tab) is on;
the Shared filter and the Shared tab show only them ([sharing.md](sharing.md)).
