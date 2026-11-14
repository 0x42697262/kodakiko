# Entries and the edit page

An entry is one moment: 1–20 items (photos, videos, voice notes), a markdown note, tags, a time
with its UTC offset, an optional place, a favorite flag and per-item captions. The edit page
creates and changes entries; it saves every change as it happens. Code:
`apps/client/src/features/entries/`, `apps/client/src/services/entries/entries-service.ts`,
`packages/core/src/domain/entry-metadata.ts`.

## Creating an entry

**Next** on the capture screen opens `/entry/new`, which creates the entry from the tray at once
and replaces itself with `/entry/<id>/edit?new=1`:

1. A random entry id and entry key; the home collection's key wraps the entry key, and the entry
   key wraps each media key.
2. Metadata: time = the earliest item's capture time, place = the earliest item with a location
   (else the device position), empty note and tags. It is sealed with the entry key.
3. One local transaction writes the entry row (`pending_create`, revision 1), its membership, the
   media rows, two object rows per item (original and thumbnail, `present` locally, `local` on the
   server) and a `createEntry` outbox item. Sync uploads it later ([sync.md](sync.md)).

## The edit page

Opened for a new entry (title "New Entry") or from the viewer's pencil (title "Edit Entry").

**Autosave.** There is no Save or Discard. Each change goes through an autosaver: time, place,
tags, order, captions and removals are written at once, the note 600 ms after typing stops. Every
write reads the current metadata, diffs the page's draft against it and writes only what changed.
The page flushes pending writes before it leaves, opens the camera, toggles the favorite or moves
the entry to trash, and reloads from storage when it comes back into focus. The check mark leaves
and shows the "Saved." Snackbar; Back does the same. A failed write shows "Couldn't save. Try
again." and the next change retries.

**Layout, top to bottom:**

1. App bar: back, title, check mark (leave), ⋮ with View Entry and Move to Trash.
2. **Media pager:** photos and videos, swipe between them; page dots below jump to a page (hidden
   for one item). Videos autoplay (see [viewer.md](viewer.md#inline-video)); tapping a video opens
   the full player. A caption shows as a one-line bar at the bottom of its page.
3. **Action bar:** take a photo, record a video, add from the device (all three open the capture
   screen for this entry, in the matching mode), favorite, voice note, paste (Android, when the
   clipboard holds an image) and share.
4. **Voice notes:** a recording tile with the timer and a stop button while recording, then one
   row per note with play, its length and remove.
5. **When:** the entry's local time ("Today, 14:05", "Mon, Nov 9, 18:00", "Nov 9, 2024, 18:00")
   with an edit icon that opens two fields, date `YYYY-MM-DD` and time `HH:MM`, plus "Use original
   time". The UTC offset is never changed by the editor. Invalid input: "Use the format 2026-11-11
   and 14:05."
6. **Where:** coordinates with their source ("from photo" or "from device"), an icon to open
   the map and one to remove the place.
7. **Tags:** chips and **Choose tags**, which opens a checkbox dialog of the tag list (up to 50
   per entry) with an **Edit tags** button ([tags.md](tags.md)).
8. **Note:** see below.

## Per-item menu (⋮ on each page)

| Item | What it does |
|---|---|
| Caption | One line, 1–64 characters, trimmed; an empty caption removes it. Shown on the page and in the viewer, found by search, written to exports |
| Set as Cover | Moves the item to position 1 (the gallery and calendar show position 1). Hidden on the first item |
| Share | Sends this item's decrypted file to another app |
| Save to Device | Web: downloads the decrypted original. Android: opens the share sheet, where "Save to Files" lives |
| Crop (photos) | A free rectangle with corner handles and a draggable middle. Apply crops the decrypted original (Android `expo-image-manipulator`, web canvas, JPEG 0.92), imports the result as a new item in the same position with the same caption, and removes the original |
| Remove | Asks "Remove this photo from the entry?" first. The last item cannot be removed ("An entry needs at least one item.") |

Video trim is not available yet.

## Note

Notes are markdown (GFM: headings, lists, checklists, quotes, links, strikethrough), up to
`NOTE_MAX_CHARS` characters.

- A new entry shows an **Add a note** row; one tap opens the editor.
- An existing note is shown rendered, with a pencil to edit. Touching the text never starts
  editing, so links stay tappable.
- In the editor a toolbar inserts markdown at the cursor: bold `**…**`, italic `*…*`,
  strikethrough `~~…~~`, heading (cycles `# `, `## `, none), bullet `- `, checklist `- [ ] `,
  quote `> `, link `[text](url)` with the address selected, and the current time. With nothing
  selected a pair is inserted with the cursor inside. The check icon leaves the editor.

## Voice notes

The microphone in the action bar records: Android with `expo-audio` (AAC in `.m4a`, high quality
preset), the web with `MediaRecorder` (Opus in `.webm`); both play on both platforms. A recording
stops by the stop button or after 10 minutes. The file is imported as an item of kind `audio`: it
skips probing and gets a bundled 256 px waveform picture as its thumbnail, so the gallery,
calendar, sync, links and exports treat it like any item. An entry may hold voice notes alongside
photos; voice notes are not shown in the media pager.

## Paste

Android: the paste button appears when the clipboard holds an image (`expo-clipboard`); the
image is read only on tap, written to an import temp file as JPEG and imported. Android 12+ shows
its own "pasted from clipboard" notice. Web: Ctrl+V on the edit page adds pasted files while the
page has focus.

## Trash and delete

- **Move to Trash** (edit page ⋮, viewer ⋮, bulk actions) sets `trashedAt` and queues
  `trashEntry`; the gallery shows "Moved to trash." with **Undo**. Restoring queues
  `restoreEntry`; a trash and a restore both still queued cancel each other.
- **Delete Permanently** exists only in Trash and asks first: "Delete (n) entries permanently?
  This can't be undone." It removes the local rows, files and queued work of the entry, and queues
  `deleteEntry` only if the server ever received the entry.

## Bulk actions

Long-press a tile to select. The journal sheet offers Add Tag…, Remove Tag…, Favorite, Unfavorite, Move to
Trash and Share…; Trash offers Restore and Delete Permanently…; a shared journal's owner gets
Remove from This Journal. Results show as "(n) entries updated" (and "(k) skipped").

## How edits are stored

- Edits go through `applyEntryEdit`; a result equal to the current metadata writes nothing.
- An edit re-seals the metadata, marks the row `pending_update` (or keeps `pending_create`) and
  queues one `updateEntryMeta`; a queued one is reused, since the executor always sends the latest
  metadata.
- Adding media queues `addMedia` per item before the metadata update; removing media queues the
  metadata update before `removeMedia`, so the server never sees metadata that refers to a missing
  item.
- Only the owner can edit; entries shared with you are read-only.
- Captions and favorites are optional fields, so older metadata stays valid. Sync merges a caption
  changed on one device with other changes made elsewhere ([sync.md](sync.md#conflicts)).

## Checked only on a phone

Crop, paste, voice recording and playback with `expo-audio`, and the share sheet.
