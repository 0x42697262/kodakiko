# Public links

A public link shows chosen photos and videos to anyone with the link, without an account, while
the server still sees only ciphertext (server flag `public_links`, modeled on 1Password's share
links). Code: `:service:links` (link copies, stripping, in-memory photo re-encode, upload),
`:shared:media` (strip plans), `:ui:feature:links` (create a link, the links list), `:app`
(`sharing/SharingAdapters.kt`: copy and share of the URL), `apps/link-viewer` (the page that opens a
link) and on the server `apps/server/src/services/public-links-service.ts`.

## Creating a link

From the Share sheet › In Kodakiko › **Create Public Link…** (`/links/new?entries=…`):

- A title, and a picker: every chosen entry as a section with checkboxes on its photos and videos
  (all checked; Select All / Select None per entry and overall).
- **Include notes** (on), and the expiry: 1 hour, 1 day, 7 days (default), 14 days, 30 days, Never
  or One view.
- The estimated upload size and a standing warning: "Anyone with the link can see what you
  selected. Location and photo/video details are removed. You can revoke the link later, but
  people may keep copies."
- **Create Link** shows progress ("Preparing 2 of 5…", "Uploading 2 of 5…", "Creating the link…"),
  then "Your link is ready." with the URL, **Copy** and **Share…**, and any items it skipped.
  Copy puts the URL on the clipboard marked as sensitive, so Android's clipboard preview hides it
  ("Copied."; v0.3.1 on Android had Share… only).
- Offline: "You're offline. Creating a link needs a connection." A failure: "Couldn't create the
  link. Nothing was shared."

Entries that are still uploading can be linked: the link carries its own copies.

**What creation does:**

1. A random 32-byte link secret; the link's collection key and an access token are derived from
   it. The secret is put in the URL's `#fragment` and never reaches the server; the owner keeps a
   copy wrapped by their master key so the link list can rebuild the URL.
2. A `link` collection on the server, then, per entry, a **copy**: every chosen item is decrypted
   from the local original, stripped and re-encrypted under a new media key; a `link_copy` entry
   (new id, notes only if included, no tags, no place, no time zone) is posted into the link
   collection, and the copies are uploaded.
3. `POST /public-links` registers the access token's hash and the expiry. Any failure deletes the
   collection and the copies.

**Stripping (mandatory, checked afterwards):**

| Kind | How |
|---|---|
| Photos | Decoded and re-encoded to JPEG in memory (longest edge 1600, quality 85); the result must have no EXIF segment, date or GPS. Photos the phone cannot decode are skipped |
| MP4 / MOV | The `moov` box is rewritten without `udta`, `meta`, `uuid` or `©xyz` and with creation times zeroed; no re-encoding. Fragmented MP4 is skipped |
| WebM | Copied, with its `DateUTC` set to 0 |

If any check fails, creation stops.

Limits (server settings): 100 links per user, 100 entries per link.

## Managing links

Settings › **Links** ("Public Links"): title, expiry ("Expires in 3 days", "Never expires", "One view — not opened
yet", "Opened"), view count, Copy Link (sensitive clip, as above) / Share… and Revoke… ("Revoke this link? People who open it
will see nothing. Copies already saved stay with them."). Revoking deletes the link, its
collection and the copies. A server job deletes expired links every hour (one-view links one day
after their view).

## Opening a link (`/s/<linkId>`, a web page, no account)

Since v0.4.0 the link page is a small standalone web page, `apps/link-viewer` (plain TypeScript
and DOM, built with esbuild), served by Caddy at `/s/*`; the site root `/` shows "Get the Android
app" ([../operations.md](../operations.md)). It is the only part of Kodakiko that runs in a
browser. It loads only libsodium, the link session logic and an HTTP client, with no local
database, keys or lock.

1. The secret is read from the fragment and removed from the address bar; the collection key and
   access token are derived. A broken fragment: "This link is incomplete. Ask for the full link."
2. `POST …/open` with the access token returns the collection metadata and a **view token** (an
   HMAC-signed expiry, 60 minutes by default) that the follow-up requests must carry. Wrong token or
   revoked: "This link doesn't work. It may have been revoked or mistyped."; expired or already
   viewed: "This link has expired." A one-view link counts its view here.
3. The entries are listed, keys unwrapped with the collection key, and thumbnails and photos
   downloaded and decrypted in memory (presigned bucket URLs, or through the API when
   `PUBLIC_LINK_PROXY` is on for buckets only reachable over a VPN). API requests refuse
   redirects, and decrypted files are only handed to the browser as JPEG, WebP, MP4 or QuickTime.
4. A grid grouped by day ("Today", "Yesterday" by the visitor's clock, then dates); a tap opens a
   full-screen view with the date and the note, swipe or ←/→ between items ("2 of 5"), pinch zoom
   on photos. Notes are shown as plain text (v0.3.1 rendered their markdown). Videos stream
   through the page's media service worker, which decrypts chunk by chunk, or play from memory up
   to 200 MiB without it ("This browser can't play this video. Try Chrome."). **Download** saves
   decrypted photos, and videos up to 200 MiB ("This video is too large to download here.").
   QuickTime videos download as `.mov`.
5. Offline: "You're offline. Connect to the internet and open the link again."
6. Footer: "Shared with Kodakiko. End-to-end encrypted: the server never saw these photos."

Leaving the page (or closing it) makes the media service worker forget the video keys.
5. Footer: "Shared with Kodakiko. End-to-end encrypted: the server never saw these photos."

When the view token runs out: "This viewing session ended. Open the original link again." (one-view
links: "This link could only be viewed once.").

## Checked only on a phone

Photo stripping of real phone photos (HEIC, rotated JPEGs), and the link page in a phone browser.
