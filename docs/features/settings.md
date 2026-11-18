# Settings, More tab and statistics

Every behavior that can reasonably differ between people is a setting. Settings are device
settings (per device and account, not synced) unless a row says otherwise. Code:
`:ui:feature:settings` (screens and ViewModels), `:ui:feature:more` (More tab, statistics),
`:ui:core` (Mihon-based settings widgets, search, theme), `:data:db` (`DeviceSettingsStore`),
`:data:log` and `:shared:log` (debug log), `:app` (`settings/*Adapters.kt`, `AppDebugLogPort.kt`).

## How settings work

- **Storage:** one JSON value in the local database (`settings.device`, `schemaVersion: 1`, the
  same value v0.3.1 wrote). A missing or invalid field takes its default, so new settings need no
  migration (v0.4.1 added `themePreset`; older settings load as the default theme). While settings
  are loading, safety settings fail closed (for example thumbnails count as encrypted, the secure
  screen is on).
- **Screens are data:** each screen is a list of Mihon-style preference items (group header, text
  row with an optional icon, switch, choice dialog, slider, info). Switch taps give a haptic tick.
- **Search:** the field at the top of Settings lists every matching row across all screens (every
  word, any order, title and section) with a breadcrumb, and opens its screen with the row
  highlighted.
- **Reset:** Advanced › Reset settings asks first, then restores every default.

## The Settings root (icons and groups since v0.4.1)

The root list has an icon on every row and a divider between groups, like Mihon's More screen
(v0.4.0 showed plain text rows):

| Group | Rows (icon) |
|---|---|
| 1 | Appearance (palette), Gallery (photo library), Tags (label), Capture (camera) |
| 2 | Sync (sync), Data and storage (storage), Security and privacy (shield) |
| 3 | Account (account circle), Links (link), Admin (admin panel) |
| 4 | Advanced (code), About (info) |
| 5 | Lock Now (lock), Sign Out (logout, in the error color; asks first when changes are waiting to upload) |

Hidden rows never leave a double divider. Links shows only when the server allows links, Admin
only for admins; in local-only mode Links, Admin and Sign Out are hidden (Sync stays and offers
Connect a server). Sub-screens show icons only on rows that
open something or destroy something: Data and storage (Create backup, Restore backup), Account
(Connect a server, Server and keys, Change password, Show recovery key, Delete account in the error
color), Advanced (Rebuild, Reset, View debug log, Debug info, Diagnostics).

## Settings screens

| Screen | Settings (default) |
|---|---|
| Appearance | **Theme** section (v0.4.1): a Light / System / Dark row (default System), then the color-theme tiles right below it, each a small preview of that theme: **Dynamic** first (Android 12+, uses the wallpaper colors), then Default, Catppuccin, Green Apple, Lavender, Midnight Dusk, Nord, Strawberry, Tako, Teal & Turquoise, Tidal Wave, Tokyo Night, Yin & Yang, Yotsuba and Monochrome (default: Default); **Pure black dark mode** (off). Then Full screen (on: hides the status and navigation bars, swipe from an edge to show them), Hide bars on scroll (on), Haptic feedback (on), Time format (24 h, 12 h), Date format (System, YYYY-MM-DD, DD/MM/YYYY, MM/DD/YYYY), Relative dates (on), Start screen (Gallery, Calendar) |
| Gallery | Display mode, items per row (portrait and landscape), grouping, badges, Show tag tabs (on), Show number of items (off), On This Day (on), Confirm before moving to trash, Hide shared journals |
| Tags | The tag editor ([tags.md](tags.md)) |
| Capture | Add location to new photos (off), Start with camera (back), Keep the screen on while capturing (on), Camera app (Kodakiko, phone's camera app), Hold to record (on), Maximum hold-to-record length (15 s) |
| Sync | Last sync, queued and failed items, Sync now, Retry failed; in local-only mode, Connect a server |
| Data and storage | Storage location, Backup and restore (Create backup, Restore backup), Autoplay videos (on), Autoplay on mobile data (off), Original cache size (2 GiB), Clear cached originals, Large uploads on Wi-Fi only (on), Large means (100 MiB), storage usage |
| Security and privacy | Auto-lock (1, 2, 5, 10, 15, 30, 60 min; default 5), Biometric unlock (an info row: on when the phone has biometrics and the key is stored with them, [lock.md](lock.md)), Encrypt thumbnails (off), Secure screen (on), Hide note previews (off) |
| Account | [accounts.md](accounts.md#settings--account) |
| Links | Your public links ([public-links.md](public-links.md#managing-links)), when the server allows links |
| Admin | Admins only ([admin.md](admin.md)) |
| Advanced | Three groups (v0.4.1): **General**: Show technical details (off: ids and sizes in the list view and the viewer), Rebuild local index, Reset settings to defaults; **Debug log**: Debug logging (switch), View debug log ([below](#debug-log)); **Debug info**: Debug info (app and server versions, platform, account mode, index and sync state; no journal content) and, in debug builds only, Diagnostics (runs the 38 crypto test vectors) |
| About | App version, server version, the Mihon attribution, open-source licenses (generated from `docs/dependencies.md`) |

Rows that need a server (links, admin, cache size, Pause sync, Autoplay on mobile data, the Wi-Fi
rule and Large means) are hidden in local-only mode ([local-mode.md](local-mode.md)).

## Theme

- The stored theme, pure black and dynamic color are applied at the root of the app, so every
  screen, sheet and dialog follows them (v0.4.0 drew the app in the default theme whatever was
  stored).
- Choosing a preset tile turns Dynamic off; choosing Dynamic keeps the preset for later. Dynamic is
  offered only on Android 12+.
- Dark mode (v0.4.1) fills every Material color role (containers, surfaces, outlines), lightens
  muted text, and draws every screen on the theme's background with its text color, so settings
  pages, dates and tags stay readable in the dark (v0.4.0 showed near-black text there).
- **Pure black** turns the dark background and surfaces black; with Dynamic, only the background
  and surface turn black.
- The thirteen extra palettes come from Mihon; Catppuccin's colors are from the Catppuccin
  project (MIT). See [dependencies.md](../dependencies.md#adapted-code-palettes-and-icons-not-libraries).

## Debug log

New in v0.4.1: a local log for finding problems on a phone without sending anything anywhere.

- **Where:** Settings › Advanced › Debug log. **Debug logging** turns it on or off ("Saves app
  activity (ids, counts, timings, errors) to a file on this device. Never notes, captions, tags,
  file names, passwords or keys. Crashes are always saved."). **View debug log** shows "N entries ·
  size".
- **Default:** on in debug builds and the preview (`.next`) build, off in release builds. The
  choice is kept in a flag file next to the log.
- **File:** `files/logs/debug.log` in app-private storage, outside the cache, so it survives the
  temp wipes, lock and sign-out; only **Clear** deletes it. Past 1 MiB it keeps the last 512 KiB
  (from a line break).
- **Line format:** `yyyy-MM-dd HH:mm:ss.SSS  LEVEL  service  event key=value …` with services
  `app`, `nav`, `auth`, `lock`, `sync`, `upload`, `download`, `import`, `export`, `restore`,
  `crash`, `settings`, `log`.
- **What is recorded:** app start (version, SDK, build type), the temp wipe, the one-time v0.3.1
  adoption and startup timings; account state changes; unlock start and end (method, outcome,
  duration); every lock and unlock step with its duration (warnings on failure); screen changes
  (screen name, action, stack depth and entry/media/collection ids; never dates or paths); sync
  passes (pushed, pulled, failed, duration) and sync warnings; uploads (completed, failed, stopped,
  with object id and kind sync or link); downloads and aborts; export start, phases, end, cancel and
  error; restore pick (file count and bytes), start, end, failure reasons and error class;
  delete-from-device outcomes and counts; changes to the debug-log switch.
- **Privacy rules:** a field whose name contains a forbidden word (note, caption, tag, name, file,
  path, uri, url, link, location, lat, lon, text, title, password, passphrase, secret, token,
  recovery, words, username, email, query, search, date, message, body, content, value, …) is
  dropped; a string value that is not a short plain token (`[A-Za-z0-9_.:-]{1,64}`) is written as
  `redacted`; numbers and booleans are written as they are. Errors are recorded by class name only,
  never by message (a message can contain a file name).
- **Crashes:** recorded even when logging is off: the thread (main or background), the exception
  class, up to 40 stack frames and up to 5 causes (class names and frames only), then Android's
  normal crash handling continues. Uncaught coroutine errors are recorded by class name.
- **Viewer:** the log in a monospace list that scrolls to the newest line (errors in the error
  color, warnings in the tertiary color), the switch on top, and in the top bar **Copy** (a debug-info
  header plus up to the last 256 KiB; "Copied N lines."), **Share** (writes `kodakiko-debug.txt` with
  the debug-info header to the share cache and opens the Android share chooser) and **Clear** (asks
  "Delete every line of the debug log?", disabled when empty).

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
| Pause sync (switch) | Stops sync passes until turned off; nothing uploads or downloads in the background. Hidden in local-only mode |
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

## Checked only on a phone

Dynamic color on a real wallpaper, the share chooser for the debug log, and crash recording of a
real crash.
