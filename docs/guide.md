# User guide

What each screen does. Settings that change a behavior are named in **bold**; all of them can be
found with the search field at the top of Settings.

## First start

| Choice | What happens |
|---|---|
| Create an account | Needs an invite link from an admin (or open sign-up, if the server allows it). You choose a password; the app shows a **recovery key** of 24 words and asks you to confirm some of them. The recovery key is the only way back if you forget the password |
| Sign in | Server address, username and password. The key derivation takes a few seconds on purpose |
| Use without a server (Android) | Local-only mode: everything stays on the phone, nothing is uploaded. Settings › Account › **Connect a Server…** moves the journal to a server later; **Erase This Journal…** deletes it. Local-only journals are the only copy, so make backups (see below) |

## Gallery

The home tab. Entries are tiles grouped by day (or month, year, tag, or no grouping). Each tile
shows the cover photo, the time, the start of the note and small badges (note, video, number of
items, favorite, waiting to upload).

- **Tag tabs** across the top: All, Favorites, then your tags in the order of Settings › Tags.
- **Search** (magnifier) finds words in notes and captions.
- **Filter** opens a sheet with four tabs: Filter (has video, has note, several items, shared,
  waiting to upload, favorite, tags, dates), Sort, Display (grid, comfortable grid, list; items per
  row; badges) and Group. Drag the sheet's handle down to close it.
- Long-press a tile to select; then trash, add or remove a tag, favorite or share several entries.
- Pull down to sync now.
- **On This Day** shows entries from the same date in earlier years.
- The round button opens the camera.

## Capture

A full-screen camera with a Photo / Video switch. Tap the shutter for a photo (or to start and
stop a video in video mode). Hold the shutter to record a short clip in either mode
(**Hold to record**, up to **Maximum hold length**). The gallery button imports photos and videos;
on the web you can also paste or drop files. **Camera app** can hand capture to the phone's own
camera instead.

Taken items collect in a tray at the bottom. **Next** creates the entry and opens the edit page.
Time and place come from the photos' EXIF data (or the device location, if **Location for new
photos** is on).

## Edit page

Every change is saved as you make it; there is no Save button. The check mark at the top leaves.

- **Media pager:** swipe through the photos and videos; the dots below jump to a page. Videos play
  muted and loop (tap the speaker for sound, tap the video for the full player). The ⋮ on a page
  offers Caption (one line, up to 64 characters, shown on the photo and found by search), Set as
  Cover, Share, Save to Device, Crop (photos; the cropped copy replaces the original) and Remove.
- **Action bar:** take a photo, record a video, add from the device, favorite, voice note,
  paste a picture (Android, when the clipboard holds one) and share.
- **Voice notes:** the microphone records; the list shows each note with play and its length.
- **When / Where / Tags:** change the time, remove the place, pick tags from your tag list
  (tags are created in Settings › Tags).
- **Note:** markdown. A new entry shows "Add a note"; an existing note is shown formatted with a
  pencil to edit it. While editing, a toolbar inserts bold, italic, strikethrough, heading, lists,
  checklist, quote, link and the current time.

## Viewer

Opened from any tile. Swipe between items, pinch or double-tap to zoom, play videos, listen to
voice notes. The action row has favorite, details (sizes, dates, sync state), map and share. Edit
opens the edit page; the ⋮ menu has keep offline, add photos or videos, move to trash and share.

## Calendar

A month grid with the cover photo on each day that has entries. Swipe or use ‹ › to change months;
it stops at the last month that has entries. Tap a day to see its entries. **Today** and
**Jump to…** move quickly.

## Sharing

The Share sheet has two parts:

| Part | What it does |
|---|---|
| In Kodakiko | Adds entries to a shared journal that other accounts on the server can read (read-only), or creates a public link. Compare the security code with the other person before sharing. Public links copy the chosen photos and videos with their metadata removed, and can expire or allow one view |
| To apps | Decrypts the chosen items and hands them to another app (messages, files). Those copies are no longer encrypted. On the web this needs a browser that can share files |

The Shared tab lists journals shared with you; Settings › Links lists your public links.

## More

Switches for **Hide shared journals** and **Pause sync**, and rows for Lock Now, Tags (create,
rename, reorder, delete), Statistics, Trash, Data and storage, Settings, About and Help.

## Settings

| Screen | What is there |
|---|---|
| Appearance | Theme, pure black, dynamic color (Android 12+), date and time format, relative dates, start screen, full screen, hide bars on scroll, haptic feedback |
| Gallery | Display mode, items per row, grouping, badges, tag tabs and counts, On This Day, confirm before trash, hide shared |
| Tags | The tag list: add, rename (renames it on every entry), reorder, delete |
| Capture | Location for new photos, start in the camera, keep the screen on, camera app, hold to record and its length |
| Sync | Sync now, retry failed uploads |
| Data and storage | Original cache size, clear cached originals, large uploads on Wi-Fi only, autoplay videos, autoplay on mobile data, export (backup) and import (restore) |
| Security | Auto-lock time, biometric unlock, keep me unlocked (web), encrypt thumbnails, secure screen, hide note previews |
| Account | Username, server, storage used, security code, Server and keys, change password, show recovery key, delete account |
| Links, Admin | Your public links; for admins: invites, users, quotas, roles |
| Advanced | Technical details, rebuild the local index, reset settings, debug info |

## Backup and restore

Data and storage › **Export** writes the whole journal as zip files: one folder per entry with the
original files and an `entry.md` (front matter with time, tags, place, favorite and captions, then
the note). Turn on **Encrypt with a passphrase** to write `.kdkx` files instead; they open only
with that passphrase. Without the app:

```sh
KODAKIKO_EXPORT_PASSPHRASE='…' pnpm --filter @kodakiko/core decrypt-export part-1.zip.kdkx
```

**Import** (Restore backup) reads plain or encrypted exports into any account. Entries that
already exist (same time and number of items) are skipped.

## Lock and trash

The app locks after the **auto-lock** time in the background (Android) or idle (web); unlock with
the password or biometrics. Lock Now is in the More tab and the gallery's ⋮ menu. Trashed entries stay in Trash
until you delete them for good; moving an entry to trash shows an Undo.
