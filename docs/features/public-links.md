# Public links

A public link shows chosen photos and videos to anyone with the link, without an account, while
the server still sees only ciphertext (server flag `public_links`, modeled on 1Password's share
links). Code: `apps/server/src/services/public-links-service.ts`,
`apps/client/src/services/links/`, `apps/client/src/features/links/`,
`apps/client/src/features/link-viewer/`, `packages/core/src/media/strip.ts`.

## Creating a link

From the Share sheet › In Kodakiko › **Create Public Link…** (`/links/new?entries=…`):

- A title, and a picker: every chosen entry as a section with checkboxes on its photos and videos
  (all checked; Select All / Select None per entry and overall).
- **Include notes** (on), and the expiry: 1 hour, 1 day, 7 days (default), 14 days, 30 days, Never
  or One view.
- The estimated upload size and a standing warning: "Anyone with the link can see what you
  selected. Location and photo/video details are removed. You can revoke the link later, but
  people may keep copies."
- **Create Link** shows progress (preparing, uploading, registering), then the URL with Copy and
  Share…, and any items it skipped.

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
| Photos | Re-encoded to JPEG (longest edge 1600, quality 0.85) through Android's image manipulator or a web canvas; the result must have no EXIF segment, date or GPS. Photos the device cannot decode (HEIC on most browsers) are skipped |
| MP4 / MOV | The `moov` box is rewritten without `udta`, `meta`, `uuid` or `©xyz` and with creation times zeroed; no re-encoding. Fragmented MP4 is skipped |
| WebM | Copied, with its `DateUTC` set to 0 |

If any check fails, creation stops.

Limits (server settings): 100 links per user, 100 entries per link.

## Managing links

Settings › **Links**: title, expiry ("Expires in 3 days", "Never expires", "One view — not opened
yet", "Opened"), view count, Copy Link / Share… and Revoke… ("Revoke this link? People who open it
will see nothing. Copies already saved stay with them."). Revoking deletes the link, its
collection and the copies. A server job deletes expired links every hour (one-view links one day
after their view).

## Opening a link (`/s/[linkId]`, web, no account)

The viewer page runs outside the app: it loads only libsodium and an HTTP client, with no local
database, keys or lock.

1. The secret is read from the fragment and removed from the address bar; the collection key and
   access token are derived. A broken fragment: "This link is incomplete. Ask for the full link."
2. `POST …/open` with the access token returns the collection metadata and a **view token** (an
   HMAC-signed expiry, 60 minutes by default) that the follow-up requests must carry. Wrong token or
   revoked: "This link doesn't work. It may have been revoked or mistyped."; expired or already
   viewed: "This link has expired." A one-view link counts its view here.
3. The entries are listed, keys unwrapped with the collection key, and thumbnails and photos
   downloaded and decrypted in memory (presigned bucket URLs, or through the API when
   `PUBLIC_LINK_PROXY` is on for buckets only reachable over a VPN).
4. A grid grouped by day; a tap opens a full-screen view with the date and the note, swipe or ←/→
   between items, pinch zoom on photos. Videos stream through the media service worker
   ([viewer.md](viewer.md#full-screen-player)), or up to 200 MB from memory without it. **Download**
   saves decrypted photos, and videos up to 200 MB.
5. Footer: "Shared with Kodakiko. End-to-end encrypted: the server never saw these photos."

When the view token runs out: "This viewing session ended. Open the original link again." (one-view
links: "This link could only be viewed once.").

## Checked only on a phone

Photo stripping through Android's image manipulator.
