# Entries and the edit page

An entry is one moment: 1–20 items (photos, videos, voice notes), a markdown note, tags, a time
with its UTC offset, an optional place, a favorite flag and per-item captions. The edit page
creates and changes entries. Code: `:ui:feature:edit` (`EditViewModel`, `EditContent`,
`EditPager`, `EditSheets`, `EditPickers`, `NoteEditorDialog`, crop), `:service:entries`
(entries repository, import pipeline), `:shared:domain` (entry metadata and edits), `:app`
(`edit/EditAdapters.kt`, `EditFlushingLockService`, `capture/AppDeviceOriginals.kt`).

## Creating an entry

**Next** on the capture screen opens the new-entry screen, which creates the entry from the tray at
once ("Creating the entry…") and replaces itself with the edit page for the new entry:

1. A random entry id and entry key; the home collection's key wraps the entry key, and the entry
   key wraps each media key.
2. Metadata: time = the earliest item's capture time, place = the earliest item with a location
   (else the device position), empty note and tags. It is sealed with the entry key.
3. One local transaction writes the entry row (`pending_create`, revision 1), its membership, the
   media rows, two object rows per item (original and thumbnail, `present` locally, `local` on the
   server) and a `createEntry` outbox item. Sync uploads it later ([sync.md](sync.md)).

## Saving: Done, Back and Discard (changed in v0.4.1)

v0.3.1 saved every change as it happened (the note 600 ms after typing stopped). Since v0.4.1 the
edit page keeps changes in a draft and writes nothing while you type:

- **Done** (✓ in the top bar) writes the draft and leaves; the gallery shows "Saved.".
- **Back** (the arrow or the system Back) on a changed entry opens a sheet: **Save** (write, then
  leave), **Discard** and **Keep editing**. A new entry always asks ("Save this entry?"), even
  unchanged. An unchanged existing entry just leaves.
- **Discard** on an existing entry puts it back the way it was and removes the photos, videos and
  voice notes added since the page opened. On a new entry it deletes the entry and everything in
  it.
- **View Entry** (overflow menu) asks the same way before it switches to the viewer.
- **Locking the app still saves**: an open edit page is written before the lock (5 s limit), so
  nothing typed is lost when the phone auto-locks.

A write reads the current metadata, diffs the draft against it and writes only what changed:
removed items first, then one metadata edit, then the favorite. A failed write shows "Couldn't
save. Try again." and stays on the page.

What happens at once instead of on Save: media you add (camera, photo picker, paste, voice notes,
crop results) is imported and attached to the entry right away, so the importing work is not lost;
Discard removes it again (but never the last item of an existing entry). Move to Trash also acts at
once.

## Deleting the originals from the phone (new in v0.4.1)

When an entry is saved (Done, Save, or the first save of a new entry) and it contains photos or
videos that were picked from the phone's gallery (the capture screen's Gallery button or the edit
page's Add from device), the page asks **"Delete N from this device?"**: "Saved. The originals are
still in your phone gallery. Android asks you to confirm before deleting them." **Keep** is the
default (also when the sheet is dismissed); **Delete** opens Android's own delete confirmation,
and the files are deleted only if you allow it there.

- Android 11 (API 30) and newer only (`MediaStore.createDeleteRequest`, F-AND-03); on older phones
  the question never appears.
- Picked URIs are mapped to MediaStore items when they are picked (Photo Picker and document URIs
  to `MediaStore.Images`/`Video` ids by MIME type); items that cannot be mapped are left out of the
  count. Camera captures are never offered (they were never in the gallery).
- The list of pending originals lives in memory only. Discard, discarding the capture tray and
  locking the app drop it without deleting anything. A cropped photo keeps its pick (the copy
  replaces the original in the entry).
- The debug log records only counts and the outcome (`kept`, `deleted`, `cancelled`, `failed`).

## The edit page

Opened for a new entry (title "New Entry") or from the viewer's Edit button (title "Edit Entry").

**Top bar:** back, title, **Done** (✓) and one ⋮ overflow menu with icons: **View Entry** and
**Move to Trash** (in the error color). (v0.4.1: the second ⋮ that sat on each media page is gone.)

**Layout, top to bottom:**

1. **Media pager:** photos and videos, swipe between them; page dots below jump to a page (hidden
   for one item). A video page shows its poster with a Play button that opens the full player (no
   inline playback on the edit page). A caption is drawn as a bar at the bottom of its page. Each
   page has a **Tune** button in its corner, and tapping a photo also opens the item sheet (below).
2. **Action bar:** take a photo and record a video (both open the capture screen for this entry in
   that mode), **Add from device** (opens the Android Photo Picker directly, limited to the slots
   left; v0.4.0 wrongly opened the camera), favorite, voice note, paste (when the clipboard holds
   an image) and share.
3. **Voice notes:** a recording tile with the timer and a stop button while recording, then one
   row per note with play, its length and remove.
4. **When:** the entry's local time ("Today, 14:05", "Mon, Nov 9, 18:00", "Nov 9, 2024, 18:00")
   with an edit icon. It opens a **Date** field and a **Time** field; tapping either opens a
   Material date picker or time picker (12- or 24-hour as the phone is set), replacing v0.3.1's
   typed `YYYY-MM-DD` / `HH:MM` fields. **Use original time** puts back the saved time; **Done**
   applies. The UTC offset is never changed by the editor.
5. **Where:** coordinates with their source ("from photo" or "from device"), a button to open the
   map and one to remove the place.
6. **Tags:** the entry's tags as chips (each with a remove ×) and a **+** button at the end of the
   row (v0.4.1, replacing the "Choose tags" button). **+** opens the choose-tags sheet: your tags
   as filter chips in a wrapping row, a check mark on the chosen ones, up to 50 per entry, and an
   **Edit tags** button ([tags.md](tags.md)).
7. **Note:** see below.

## Item sheet (Tune button or a tap on a photo)

| Item | What it does |
|---|---|
| Caption | One line, 1–64 characters, trimmed; an empty caption removes it. Shown on the photo here and in the viewer, found by search, written to exports |
| Set as Cover | Moves the item to position 1 (the gallery and calendar show position 1). Hidden on the first item |
| Share | Sends this item's decrypted file to another app through the Android share chooser |
| Save to Device | Opens the Android share chooser for this item (save it with Files or another app) |
| Crop (photos) | A free rectangle with corner handles and a draggable middle. Apply crops the decrypted original (JPEG 0.92), imports the result as a new item in the same position with the same caption, and removes the original |
| Remove | Asks "Remove this photo from the entry?" first. The last item cannot be removed ("An entry needs at least one item.") |

Caption, cover and remove change the draft (saved with Done or Save); crop replaces the item at
once. Video trim is not available.

## Note

Notes are markdown (GFM: headings, lists, checklists, quotes, links, strikethrough), up to
`NOTE_MAX_CHARS` characters.

- A new entry shows an **Add a note** row; an existing note is shown rendered, with a pencil to
  edit. Touching the rendered text never starts editing, so links stay tappable.
- Editing opens a **full-screen note editor** (v0.4.1): a top bar with Close, "Note", **Copy** and
  Done; a **Code / Preview** switch like a pull request on GitHub; Code is a text field that fills
  the screen, with the markdown toolbar pinned just above the keyboard (v0.4.0 let the keyboard
  cover it); Preview renders the note ("Nothing to preview." when empty).
- The toolbar inserts at the cursor: bold `**…**`, italic `*…*`, strikethrough `~~…~~`, heading
  (cycles `# `, `## `, none), bullet `- `, checklist `- [ ] `, quote `> `, link `[text](url)` with
  the address selected, and the current time. With nothing selected a pair is inserted with the
  cursor inside.
- **Copy** copies the raw markdown in Code mode and the rendered plain text in Preview mode
  (images become their alt text). The clip is marked sensitive, so Android's clipboard preview
  hides it.
- Close and Done both keep the text in the draft; it is saved with the entry (Done or Save).

## Voice notes

The microphone in the action bar records AAC in `.m4a` (`MediaRecorder`, high-quality preset);
voice notes recorded as Opus in `.webm` by the old web app still play. A recording stops by the
stop button or after 10 minutes. The file is imported as an item of kind `audio`: it skips probing
and gets a bundled 256 px waveform picture as its thumbnail, so the gallery, calendar, sync, links
and exports treat it like any item. An entry may hold voice notes alongside photos; voice notes are
not shown in the media pager.

## Paste

The paste button appears when the clipboard holds an image; the image is read only on tap,
re-encoded as JPEG into an import temp file and imported. Android 12+ shows its own "pasted from
clipboard" notice.

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

Crop, paste, voice recording and playback, the date and time pickers, the share chooser, and
Android's delete confirmation for device originals.
