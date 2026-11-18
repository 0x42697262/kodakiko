# Viewer, video and markdown

The read page of an entry (`/entry/<id>`), the full-screen video player (`/play/<entryId>/<mediaId>`)
and the markdown renderer. Code: `:ui:feature:viewer` (`EntryViewerContent`, `ViewerPager`,
`ZoomMath`, `VideoPlayerContent`, `EntryDetailsContent`, `ViewerModel`), `:ui:core` (`MarkdownView`),
`:shared:markdown`, `:data:media` (`DecryptingDataSource`, `StreamFileReader`, originals cache),
`:platform:audio` (voice player), `:app` (`viewer/AppVideoSource.kt`, `ViewerAdapters.kt`).

## The viewer

**Pager.** One page per photo and video in entry order; voice notes are listed below instead. Each
page shows the thumbnail at once and swaps to the original when it is available (downloading it
when needed, with progress; see [media-cache.md](media-cache.md)). Originals are opened for the
visible page and its neighbors and released when a page leaves that window. A page indicator
("2 / 5") sits under the pager. When the item order changes (for example Set as Cover), the pager
starts over at the first page.

**Captions** (changed in v0.4.1): a caption is drawn on its photo or video, as a white line on a
dark bar at the bottom of the page (up to two lines), the same way the edit page shows it. v0.4.0
showed it as a text line under the pager, which read like a note.

**Zoom.** Pinch (1×–5×, around the fingers), double tap (2.5× around the point, again to reset),
pan while zoomed (never past the image edge); swipes change page only when not zoomed, and a page
change resets zoom. Labels "Photo 1" / "Photo 1, zoomed".

**Full-screen photo** (new in v0.4.1): a single tap on a photo opens the pager full screen: black,
edge to edge, with the same zoom, swipe and captions. A single tap closes it again (so does Back).
Double tap still zooms.

**Below the pager:** the entry time, the place (with Open in Maps), voice notes (play and length),
tag chips (a tap opens the gallery filtered to that tag) and the rendered note.

**Action row:** favorite (heart), details (`/entry/<id>/details`: sizes, dimensions, dates, sync
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

## Inline video

Video pages in the viewer play inline: muted, looping, only on the current page, with a speaker
button to unmute. (The edit page shows the poster with a Play button instead.) Rules
(`shouldAutoplay`):

- **Autoplay videos** (Data and storage, default on) must be on, and the page must have been shown
  for 0.8 s (quick swipes download nothing).
- A video whose original is on the device always plays; one that must be downloaded plays only
  on Wi-Fi, unless **Autoplay on mobile data** (default off) is on.
- While a video plays inline there is no Play button over it (v0.4.1; v0.4.0 kept showing it). When
  it does not autoplay, the page shows its poster with a Play button.
- The inline video stops and releases its player when its screen loses focus, so it never
  competes with the full player. Tapping the video opens the full player.

## Full-screen player

`/play/<entryId>/<mediaId>`, black. Media3 ExoPlayer with its controls; the close button and
Back release the player and go back.

The video plays straight from the encrypted file: if the original is not on the phone it is
downloaded into the encrypted originals cache first ("Preparing video… N%"), then a decrypting
Media3 `DataSource` reads only the STREAM chunks around the play position (`StreamFileReader`,
which checks the final chunk once so a cut file fails). No decrypted copy is ever written (v0.3.1
decrypted the whole video into a temp file first). Seeking in a long video is fast because only
the chunks around the new position are decrypted (F-KT-18).

Errors are shown by cause: the format can't be played here, the video is damaged, the download
failed (with Retry), playback stopped (tap to retry).

## Markdown

Notes are parsed by `:shared:markdown`, a Kotlin parser that reproduces the token tree of the
`marked` lexer v0.3.1 used (GFM, line breaks kept; golden-tested against it, F-KT-22); no HTML is
ever produced and Compose text renders the result (`MarkdownView` in `:ui:core`, shared by the
viewer and the note editor's Preview):

- Supported: headings (deeper than 3 shown as 3), paragraphs, bold, italic, strikethrough, inline
  and block code, quotes, ordered and unordered lists, task lists (checked boxes shown, not
  tappable), horizontal rules, line breaks.
- Links work only for `http:`, `https:` and `mailto:`; a tap opens a sheet that shows the full
  address before leaving the app. Other schemes render as text.
- Images never load: they show their alt text (or "[image]").
- Raw HTML, tables and anything unknown appear as their literal text. Nesting deeper than 6 levels
  becomes plain text.
- `markdownPlainText` (v0.4.1) turns a note into plain text for the note editor's Copy in Preview
  mode: the same parse, without markup; images become their alt text.

## Checked only on a phone

Pinch on real hardware, inline and full-screen playback of real phone videos, voice-note playback,
and the full-screen photo with the phone's system bars.
