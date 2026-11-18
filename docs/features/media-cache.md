# Thumbnails, originals and downloads

Which encrypted files live on the device, when they are downloaded and when they are removed.
Code: `:data:media` (`ThumbnailCache`, `OriginalCache`, `DownloadQueue`, `StreamFileReader`, Coil
fetchers), `:data:files` (paths, atomic writes, orphan sweep, temp files), `:data:net`
(`NetworkMonitor`), `:app` (`gallery/AccountMedia.kt`, `CoilGalleryThumbnails.kt`).

## Thumbnails

Every thumbnail of every readable entry ends up on the device, so the gallery scrolls without the
network.

- A visible tile without a local thumbnail downloads it at once; after each sync pass every
  missing thumbnail of a readable, not trashed entry is downloaded in the background, on any
  network.
- **Plain mode** (default): after a download the thumbnail is decrypted into app-private
  storage (`thumbs-plain/<mediaId>.webp`) and the encrypted copy is deleted, so only one copy
  exists. **Encrypt thumbnails** (Settings › Security) keeps the server's encrypted copy instead and
  decrypts in memory (the 300 most recent stay decoded in memory until lock). Switching mode converts every thumbnail in the
  background and shows "Updating thumbnails… (done)/(total)".
- Thumbnails are never evicted. Images reach the screen through Coil with no disk cache and no
  network fetcher of its own; decrypted thumbnails live in memory only and are dropped at lock.

## Originals

- Opening a photo, playing a video, sharing to apps or exporting downloads the original if it is
  not local;
  the viewer shows a progress ring and swaps to the original when it arrives, or "Couldn't download
  the original. Showing preview." with Retry.
- Photos are decoded straight from the encrypted file and videos play through a decrypting reader
  ([viewer.md](viewer.md#full-screen-player)); no decrypted copy of an original is written.
- **Original cache size** (Data and storage; default 2 GiB, or no limit) caps
  the space used by downloaded originals. When it is exceeded the least recently opened originals
  are deleted first. Originals of kept-offline entries and originals not uploaded yet are never
  deleted. The cap is enforced at unlock, after each download that opens an original, and two
  minutes after share to apps fetched originals (v0.4.1, [sharing.md](sharing.md#share-to-apps)).
  Local-only journals never evict (the phone holds the only copy).
- **Keep Offline** (viewer ⋮) pins an entry: its originals download in the background, on Wi-Fi
  only, and stay. **Clear cached originals** removes every downloaded original that can be fetched
  again.
- Statistics and Data and storage show the space used by originals, thumbnails, kept-offline
  entries and uploads waiting.

## Downloads

- One queue per priority (opened > visible > background); up to 6 thumbnails and 2 originals at a
  time; the same object requested twice downloads once.
- Each download asks the API for a fresh presigned URL (`GET /objects/{id}/download`), streams the
  response (OkHttp) into a `.partial` file, checks the size and renames it. Timeout 10 minutes;
  network errors and 5xx are retried 3 times (2 s, 8 s, 30 s) with a new URL each time.
- A 404 (object replaced or entry deleted) marks the object unavailable; a later pull that brings
  a replacement resets it.
- Background downloads pause while offline and stop on lock and sign-out.
- With the [debug log](settings.md#debug-log) on, each download (object id, bytes, duration,
  outcome) and each abort is recorded.

## Network rules

| Transfer | Rule |
|---|---|
| Thumbnails | Any network |
| Opening an original | Any network (the user asked for it) |
| Kept-offline originals | Wi-Fi only |
| Uploads larger than **Large means** (default 100 MiB) | Wi-Fi only while **Large uploads on Wi-Fi only** is on |
| Inline video that is not on the device | Wi-Fi only unless **Autoplay on mobile data** is on |

Wi-Fi and Ethernet count as Wi-Fi; "online" means Android reports the network as validated
(`NetworkMonitor`).

## Local storage layout

All in app-private storage, at the same places v0.3.1 used, so an update keeps everything:

| What | Where |
|---|---|
| Encrypted objects | `files/kodakiko/<account hash>/objects/<objectId>.kdks` |
| Plain thumbnails | `files/kodakiko/<account hash>/thumbs-plain/<mediaId>.webp` |
| Database (rows are ciphertext) | `files/SQLite/kodakiko-<account hash>.db` (SQLite, WAL) |
| Debug log | `files/logs/debug.log` ([settings.md](settings.md#debug-log)) |
| Plaintext temp files | the app cache (`cache/`, incl. `cache/share/`), wiped at start, unlock and lock |

Object files not known to the database (from an import killed halfway) are swept at unlock.

## Checked only on a phone

Background downloads on Android, Wi-Fi detection, and eviction with real cache sizes.
