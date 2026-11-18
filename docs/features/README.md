# Features

How each part of Kodakiko works today (v0.4.1: the Kotlin Android app, the server and the public
link page), in detail: behavior, rules, limits, settings and edge cases. Where v0.4.1 changed a
behavior, the doc says what it was before. Each doc ends with what can only be checked on a phone. For a short tour of the screens,
read [../guide.md](../guide.md) first.

## Using the journal

| Doc | What it covers |
|---|---|
| [capture.md](capture.md) | Camera, photo picker, phone camera hand-off; the import pipeline that encrypts media |
| [edit-page.md](edit-page.md) | Creating and editing entries: Done / Save / Discard, deleting picked originals from the phone, media pager, item sheet, captions, crop, voice notes, the full-screen note editor, trash, bulk actions |
| [gallery.md](gallery.md) | The local index, the gallery, tag tabs, Filter/Sort/Display/Group sheet, On This Day, trash |
| [viewer.md](viewer.md) | The entry viewer, captions on photos, zoom, full-screen photos, inline video, the full-screen player streaming from the encrypted file, markdown |
| [calendar.md](calendar.md) | The month grid, changing months, the Jump to picker, day pages |
| [tags.md](tags.md) | Tag rules, the tag editor, picking tags |
| [sharing.md](sharing.md) | The Share sheet, shared journals between accounts, security codes, share to apps |
| [public-links.md](public-links.md) | Links that anyone can open, metadata stripping, the link viewer page |
| [backup.md](backup.md) | Create backup (zip or encrypted `.kdkx`), `decrypt-export`, restore |

## Accounts and privacy

| Doc | What it covers |
|---|---|
| [accounts.md](accounts.md) | Sign-up, sign-in, recovery key, password change, deletion |
| [local-mode.md](local-mode.md) | Using the app without a server, connecting one later |
| [lock.md](lock.md) | Auto-lock, what locking wipes, biometrics, updating from v0.3.1, secure screen |
| [../encryption.md](../encryption.md) | What is encrypted and how |

## Under the hood

| Doc | What it covers |
|---|---|
| [sync.md](sync.md) | Outbox, uploads, pull, conflict merge |
| [media-cache.md](media-cache.md) | Thumbnails, originals cache, downloads, network rules |
| [settings.md](settings.md) | How settings work, every settings screen, themes, the debug log, More tab, statistics |
| [app-shell.md](app-shell.md) | Startup, routing, navigation, theme, touch feedback, sheets, snackbars, haptics |
| [admin.md](admin.md) | Admin screens and the server CLI |
| [server.md](server.md) | API, configuration, entries and sync on the server, objects and the bucket, jobs |
