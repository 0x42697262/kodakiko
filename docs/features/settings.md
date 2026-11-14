# Settings, More tab and statistics

Every behavior that can reasonably differ between people is a setting. Settings are device
settings (per device and account, not synced) unless a row says otherwise. Code:
`apps/client/src/features/settings/`, `features/more/`, `ui/preferences/`,
`services/settings/device-settings.ts`, `constants/ui-settings-constants.ts`.

## How settings work

- **Storage:** one JSON value in the local database (`settings.device`, `schemaVersion: 1`),
  validated with a Zod schema. A missing or invalid field takes its default (defaults per platform
  in `constants/`), so new settings need no migration. While settings are loading, safety settings
  fail closed (for example thumbnails count as encrypted, the secure screen is on).
- **Screens are data:** each screen is a list of preference items (header, text row, switch,
  choice dialog, slider, info) rendered by one `PreferenceList`, the way Mihon declares its
  settings. Switch taps give a haptic tick; on Android the switch itself takes the tap.
- **Search:** the field at the top of Settings lists every matching row across all screens and
  opens its screen.
- **Reset:** Advanced › Reset settings restores every default.

## Settings screens

| Screen | Settings (default) |
|---|---|
| Appearance | Theme (System, Light, Dark), Pure black dark mode (off), Dynamic color (Android 12+, off), Date format (System, YYYY-MM-DD, DD/MM/YYYY, MM/DD/YYYY), Relative dates (on), Time format (24 h, 12 h), Start screen (Gallery, Calendar), Full screen (Android, on: hides the status and navigation bars, swipe from an edge to show them), Hide bars on scroll (on), Haptic feedback (on) |
| Gallery | Display mode, items per row (portrait and landscape), grouping, badges, Show tag tabs (on), Show number of items (off), On This Day (on), Confirm before moving to trash, Hide shared journals |
| Tags | The tag editor ([tags.md](tags.md)) |
| Capture | Add location to new photos (off), Start with camera (back), Keep the screen on while capturing (on), Camera app (Kodakiko, phone's camera app), Hold to record (on), Maximum hold-to-record length (15 s) |
| Sync | Last sync, queued and failed items, Sync now, Retry failed |
| Data and storage | Storage location, Backup and restore (Export, Restore backup), Autoplay videos (on), Autoplay on mobile data (Android, off), Original cache size (Android 2 GiB, web 1 GiB), Clear cached originals, Large uploads on Wi-Fi only (on), Large means (100 MiB), storage usage |
| Security and privacy | Auto-lock (1, 2, 5, 10, 15, 30, 60 min; Android 5, web 15), Biometric unlock (Android), Keep me unlocked (web), Encrypt thumbnails (Android, off), Secure screen (Android, on), Hide note previews (off) |
| Account | [accounts.md](accounts.md#settings--account) |
| Links | Your public links ([public-links.md](public-links.md#managing-links)), when the server allows links |
| Admin | Admins only ([admin.md](admin.md)) |
| Advanced | Show technical details (off: ids and sizes in the list view and the viewer), Rebuild index, Reset settings, Debug info (app and server versions, platform, account mode, index and sync state) |
| About | App version, open-source licenses |

Rows that need a server (sync, links, admin, cache size, Wi-Fi rule) are hidden in local-only mode
([local-mode.md](local-mode.md)).

## Server and keys

Settings › Account › **Server and keys** shows the facts a power user might check: server URL and
version, API version, sign-up mode, feature flags (sharing, public links) and limits (items per
entry, upload size, trash days); the account id, the security code (public-key fingerprint), the
Argon2id parameters, the encryption formats (XChaCha20-Poly1305, STREAM chunk size, sealed boxes),
whether the recovery key was confirmed, and how this device stores the key (Keystore with or
without biometrics, or memory).

## More tab

A logo header, then:

| Row | What it does |
|---|---|
| Hide shared journals (switch) | Leaves entries shared with you out of the gallery |
| Pause sync (switch) | Stops sync passes until turned off; nothing uploads or downloads in the background |
| Lock Now | Locks at once |
| Tags | The tag editor |
| Statistics | See below |
| Trash | Trashed entries |
| Data and storage | The settings screen of that name |
| Settings, About, Help | — |

## Statistics

More › **Statistics**, as cards: **Overview** (entries, journal size), **Photos and videos** (photos,
videos, total video length, entries with a video), notes (entries with a note), **Tags** (count and
the top tags), favorites, **Storage** (originals and thumbnails on this device, waiting to upload,
server usage with used / quota / free and a bar), **Activity** (first and last day, entries per
month and per year) and **Sync** (last synced, queued and failed items).

Entry details (viewer › Details) show the same kind of facts for one entry and each of its items.
