# Backup and restore

Export writes the whole journal as plain or passphrase-encrypted files that other tools can read;
import brings such files back into any account. Code: `:shared:export` (layout, `entry.md`, ZIP
writer and reader, KDKX, streaming sources), `:service:backup` (export with cancel and lock abort,
SAF destination, restore), `:ui:feature:settings` (`BackupScreens`, `BackupViewModels`), `:app`
(`settings/BackupServices.kt`, `RestoreSources.kt`, `log/LoggingExportService.kt`), and
`packages/core/scripts/decrypt-export.ts` (the command-line decrypter).

## Export (Data and storage › Backup and restore › Create backup)

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
the current part and keeps finished parts ("Export canceled. Finished files were kept.").

**Where it goes:** **Choose Folder and Export** opens the Android folder picker (Storage Access
Framework); the parts are written into the chosen folder (a phone folder, an SD card or a cloud
app's folder). The screen shows the phase (Planning, Downloading, Writing), entries and parts done,
and asks "Keep Kodakiko open until the export finishes." With the
[debug log](settings.md#debug-log) on, the start, each phase, the end (parts, bytes, entries,
duration), a cancel or an error class are recorded.

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

## Import (Data and storage › Backup and restore › Restore backup)

1. **Choose Files and Import**: pick every part of one export at once with the Android file picker,
   with the passphrase when any part ends in `.kdkx` ("Passphrase (for encrypted exports)"). A wrong
   or missing passphrase stops with "The passphrase does not open these files."
2. Each part is read in place, never loaded whole: the picked file is
   opened for random access (a temp copy only when the provider cannot seek), a `.kdkx` part is
   decrypted one 1 MiB chunk at a time, the ZIP's directory is read from its end, and each file is
   streamed in slices into an import temp file. The first v0.4.0 preview build read the picked file
   whole and crashed on a large encrypted backup right after picking it (fixed before v0.4.0
   shipped); a 117 MB backup made by v0.3.1 now
   restores with a heap of about 19 MB.
3. Every `entry.md` is parsed (a strict reader of exactly the format above) and its files imported
   through the normal media pipeline ([capture.md](capture.md#the-import-pipeline)), then an entry
   is created with the time and offset, tags, place, note, items in order, favorite and captions.
4. An entry is skipped as a duplicate when one with the same time and number of items already
   exists. Problems are reported per entry: unreadable note, missing files, refused files.

Progress shows "Importing (i) of (n) entries…"; the summary reads "Imported (n) entries with (m)
photos and videos. Skipped (k) already here." and "(f) entries could not be imported." Files that
are not an export, or are damaged, answer "These files are not a Kodakiko export, or they are
damaged." With the debug log on, the pick (file count and bytes, recorded before anything is
read), start, progress, end and failure reasons are recorded, never file names. Importing into a new
account restores a journal; importing into the same account adds only what is missing.

Automatic scheduled backups do not exist yet.

## Checked only on a phone

The Android folder picker and writing large parts through it, and restoring a large backup from
cloud storage providers.
