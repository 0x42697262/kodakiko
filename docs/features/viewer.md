# Viewer, video and markdown

The read page of an entry (`/entry/[id]`), the full-screen video player (`/play/[entryId]/[mediaId]`)
and the markdown renderer. Code: `apps/client/src/features/viewer/`, `features/video/`,
`features/voice/`, `features/markdown/`, `features/media/`, `adapters/video-source/`,
`web/sw/media-sw.ts`.

## The viewer

**Pager.** One page per photo and video in entry order; voice notes are listed below instead. On
Android the pager is `react-native-zoom-toolkit`'s `Gallery`; on the web a paging `FlatList`. Each
page shows the thumbnail at once and swaps to the original when it is available (downloading it
when needed, with progress; see [media-cache.md](media-cache.md)). Originals are opened for the
visible page and its neighbors and released when a page leaves that window. A page indicator
("2 / 5") and the current item's caption sit under the pager. Web keys: ← and → change page, Esc
goes back. When the item order changes (for example Set as Cover), the pager starts over at the
first page.

**Zoom.** Pinch (1×–5×, around the fingers), double tap (2.5× around the point, again to reset),
pan while zoomed (never past the image edge); swipes change page only when not zoomed, and a page
change resets zoom. On the web: trackpad pinch, double-click, drag while zoomed, Ctrl/Cmd + wheel
around the cursor. Labels "Photo 1" / "Photo 1, zoomed".

**Below the pager:** the entry time, the place (with Open in Maps), voice notes (play and length),
tag chips (a tap opens the gallery filtered to that tag) and the rendered note.

**Action row:** favorite (heart), details (`/entry/[id]/details`: sizes, dimensions, dates, sync
and cache state of every item, technical details when that setting is on), map and share (the
Share sheet, [sharing.md](sharing.md)).

**App bar:** back, Edit (owner, not trashed) and ⋮: Keep Offline / Stop Keeping Offline, Add Photos
or Videos, Move to Trash ("Moved to trash." with Undo, back to where the viewer was opened from) and
Share….

**Other states:**

| Entry | What the viewer shows |
|---|---|
| In the trash (owner) | "This entry is in the trash." with Restore and Delete Permanently…; no Edit |
| Shared with you | "Shared by (username)"; no edit, trash or delete |
| Unreadable | "This entry can't be opened. It may be damaged." and, for the owner, Delete Permanently… |
| Deleted meanwhile | Back to the gallery with "That entry no longer exists." |

On Android, tapping a video page opens the player (the pager's own tap gesture would otherwise
swallow the Play button).

## Inline video

Video pages in the viewer and the edit page play inline: muted, looping, only on the current page,
with a speaker button to unmute. Rules (`shouldAutoplay`):

- **Autoplay videos** (Data and storage, default on) must be on, and the page must have been shown
  for 0.8 s (quick swipes download nothing).
- A video whose original is on the device always plays; one that must be downloaded plays only
  on Wi-Fi, unless **Autoplay on mobile data** (Android, default off) is on.
- The inline video stops and releases its stream when its screen loses focus, so it never competes
  with the full player.

Android plays through `expo-video`; the web uses a plain `<video>` element. Tapping the video opens
the full player.

## Full-screen player

`/play/[entryId]/[mediaId]`, black, fades in on Android. `expo-video` with native controls and a
fullscreen button; Esc, the close button and hardware back release the source and go back. While a
video plays the app counts as active, so it does not auto-lock.

How the source is prepared (`VideoSource.prepare`):

| Platform | How |
|---|---|
| Android | Download the original if it is not local (first half of the progress), check there is room for the plaintext plus 100 MB, decrypt it chunk by chunk into a temp `play-*` file (second half), play `file://…`. The file is deleted when the player closes, and every `play-*` file on lock, sign-out and app start |
| Web | The media service worker streams it: the page registers a playback (random id, the original's key, a presigned download URL, sizes, MIME type) and the `<video>` plays `/__kdks_media/<id>`. Without a service worker, videos up to 200 MB are decrypted into a `Blob`; larger ones cannot play |

**The media service worker** (`public/media-sw.js`, built from `web/sw/media-sw.ts`) answers
range requests: it reads the 24-byte STREAM header and checks the final chunk once (truncation
check), maps the requested plaintext range to whole ciphertext chunks (at most 4 per response),
fetches exactly those bytes from the bucket and decrypts them. Keys live only in the worker's
memory, one per playback; `forgetAll` on lock and sign-out wipes them. If the worker restarted
and lost a key, or the presigned URL expired, it asks the page for a new one. Seeking in a long
video downloads only the chunks around the new position.

Errors are shown by cause: the format is not supported on this device, not enough free space,
the browser cannot stream (and the file is too large for the fallback), the stream stopped (with
Retry).

## Markdown

Notes are parsed with `marked`'s lexer only (GFM, line breaks kept); no HTML is ever produced and
React Native components render the result:

- Supported: headings (deeper than 3 shown as 3), paragraphs, bold, italic, strikethrough, inline
  and block code, quotes, ordered and unordered lists, task lists (checked boxes shown, not
  tappable), horizontal rules, line breaks.
- Links work only for `http:`, `https:` and `mailto:`; a tap opens a sheet that shows the full
  address before leaving the app. Other schemes render as text.
- Images never load: they show their alt text (or "[image]").
- Raw HTML, tables and anything unknown appear as their literal text. Nesting deeper than 6 levels
  becomes plain text.

## Checked only on a phone

The zoom-toolkit pager and pinch, decrypted-video cleanup, inline playback with `expo-video`, and
voice-note playback with `expo-audio`.
