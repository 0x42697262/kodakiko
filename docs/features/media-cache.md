# Thumbnails, originals and downloads

Which encrypted files live on the device, when they are downloaded and when they are removed.
Code: `apps/client/src/services/thumbnails/`, `services/originals/`, `services/downloads/`,
`adapters/filestore/`, `adapters/network/`.

## Thumbnails

Every thumbnail of every readable entry ends up on the device, so the gallery scrolls without the
network.

- A visible tile without a local thumbnail downloads it at once; after each sync pass every
  missing thumbnail of a readable, not trashed entry is downloaded in the background, on any
  network.
- **Plain mode** (Android default): after a download the thumbnail is decrypted into app-private
  storage (`thumbs-plain/<mediaId>.webp`) and the encrypted copy is deleted, so only one copy
  exists. **Encrypt thumbnails** (Settings › Security) keeps the server's encrypted copy instead and
  decrypts in memory; the web always works this way. Switching mode converts every thumbnail in the
  background and shows "Updating thumbnails… (done)/(total)".
- Thumbnails are never evicted.

## Originals

- Opening a photo, playing a video, sharing or exporting downloads the original if it is not local;
  the viewer shows a progress ring and swaps to the original when it arrives, or "Couldn't download
  the original. Showing preview." with Retry.
- **Original cache size** (Data and storage; Android default 2 GiB, web 1 GiB, or no limit) caps
  the space used by downloaded originals. When it is exceeded the least recently opened originals
  are deleted first. Originals of kept-offline entries and originals not uploaded yet are never
  deleted.
- **Keep Offline** (viewer ⋮) pins an entry: its originals download in the background, on Wi-Fi
  only, and stay. **Clear cached originals** removes every downloaded original that can be fetched
  again.
- Statistics and Data and storage show the space used by originals, thumbnails, kept-offline
  entries and uploads waiting.

## Downloads

- One queue per priority (opened > visible > background); up to 6 thumbnails and 2 originals at a
  time; the same object requested twice downloads once.
- Each download asks the API for a fresh presigned URL (`GET /objects/{id}/download`), writes into
  a `.partial` file (Android `File.createDownloadTask`, so large files never pass through JS memory;
  web streams `fetch` into OPFS), checks the size and renames it. Timeout 10 minutes; network
  errors and 5xx are retried 3 times (2 s, 8 s, 30 s) with a new URL each time.
- A 404 (object replaced or entry deleted) marks the object unavailable; a later pull that brings
  a replacement resets it.
- Background downloads pause while offline and stop on lock and sign-out.

## Network rules

| Transfer | Rule |
|---|---|
| Thumbnails | Any network |
| Opening an original | Any network (the user asked for it) |
| Kept-offline originals | Wi-Fi only |
| Uploads larger than **Large means** (default 100 MiB) | Wi-Fi only while **Large uploads on Wi-Fi only** is on |
| Inline video that is not on the device | Wi-Fi only unless **Autoplay on mobile data** is on |

Wi-Fi and Ethernet count as Wi-Fi (`expo-network`); the web always counts as Wi-Fi.

## Local storage layout

| Platform | Encrypted objects | Plain thumbnails | Database |
|---|---|---|---|
| Android | `Paths.document/kodakiko/<account>/objects/<objectId>.kdks` | `…/thumbs-plain/` | `expo-sqlite` (rows are ciphertext) |
| Web | OPFS, per account | — | IndexedDB |

Object files not known to the database (from an import killed halfway) are swept at unlock.

## Checked only on a phone

Background downloads on Android, Wi-Fi detection, and eviction with real cache sizes.
