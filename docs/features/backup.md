# Backup and restore

Export writes the whole journal as plain or passphrase-encrypted files that other tools can read;
import brings such files back into any account. Code: `packages/core/src/export/`,
`packages/core/scripts/decrypt-export.ts`, `apps/client/src/services/export/`,
`apps/client/src/services/import/journal-import.ts`, `apps/client/src/adapters/export-destination/`,
`apps/client/src/adapters/archive/`.

## Export (Data and storage › Export)

**What is written.** Own, not trashed, readable entries in time order:

```
kodakiko-export-2026-11-14-part01.zip
  README.txt
  2026/11/2026-11-14_0815_AbCdEfGh/
    entry.md
    001.jpg
    002.mp4
    003.m4a
```

- One folder per entry: `YYYY/MM/YYYY-MM-DD_HHMM_<first 8 characters of the id>/`, from the
  entry's local time. Files are named by position plus an extension from the MIME type; the
  original file name is only recorded, never used as a path.
- `entry.md` is YAML front matter, then the note:

```
---
id: <entryId>
date: 2026-11-14T08:15:00+01:00
tags: [beach, family]
favorite: true
location: { latitude: 46.55860, longitude: 7.83550, source: exif }
media: [{ file: 001.jpg, original_name: IMG_1234.JPG, kind: photo, caption: "Low tide" }, { file: 003.m4a, kind: audio }]
---
<note>
```

  Values with `:#[]{},"'` or edge spaces are double-quoted with `"` and `\` escaped.
- Media are stored uncompressed with the entry's time as their date (clamped to the ZIP range
  1980–2099); `entry.md` is deflated. `README.txt` (part 1) explains the layout and that plain
  exports are not encrypted; `MISSING.txt` (last part) lists items that could not be read.
- **Parts:** files are packed in order into ZIP parts of at most 3.75 GiB and 65,000 files (no
  Zip64 needed); an entry may span two parts.

**How.** Originals that are not on the device are downloaded first; each is decrypted chunk by
chunk straight into the ZIP stream. No plaintext is ever written to the app's storage. The cache
limit is enforced after each entry. Auto-lock pauses while an export runs; locking or Cancel aborts
the current part and keeps finished parts.

**Where it goes:**

| Platform | Destination |
|---|---|
| Android | A folder chosen with the system picker (Storage Access Framework) |
| Web | A folder (`showDirectoryPicker`, Chrome) or, without it, one download per part (parts limited to 1 GiB; larger items are skipped and listed) |

**Encrypt with a passphrase** (at least 12 characters, typed twice) writes `<part>.zip.kdkx`
instead:

| Bytes | Content |
|---|---|
| 4 | Magic `KDKX` |
| 1 | Version |
| 4 + 4 | Argon2id passes and memory (big-endian), the account's sign-up cost |
| 16 | Salt |
| 24 | STREAM header |
| … | The ZIP in 1 MiB XChaCha20-Poly1305 chunks; the last chunk is marked final, so a cut file fails to open |

The key is Argon2id(passphrase). Without the app:

```sh
KODAKIKO_EXPORT_PASSPHRASE='…' pnpm --filter @kodakiko/core decrypt-export part01.zip.kdkx part02.zip.kdkx
```

writes the plain ZIP files next to them (it prompts for the passphrase when the variable is not set).

## Import (Data and storage › Restore backup)

1. Choose the files (Android: several files with the system picker; web: a file input), and the
   passphrase when any part ends in `.kdkx`. A wrong or missing passphrase stops with an error.
2. Each part is opened (decrypted when needed) and unzipped in memory.
3. Every `entry.md` is parsed (a strict reader of exactly the format above) and its files imported
   through the normal media pipeline ([capture.md](capture.md#the-import-pipeline)), then an entry
   is created with the time and offset, tags, place, note, items in order, favorite and captions.
4. An entry is skipped as a duplicate when one with the same time and number of items already
   exists. Problems are reported per entry: unreadable note, missing files, refused files.

The summary reads "Imported (n) entries" with the duplicates and failures. Importing into a new
account restores a journal; importing into the same account adds only what is missing.

Limits: parts are unzipped in memory, which is fine for normal journals; automatic scheduled
backups do not exist yet.

## Checked only on a phone

The Android folder picker and writing large parts through it.
