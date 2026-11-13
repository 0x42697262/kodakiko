# Kodakiko planning context

Last updated: 2026-11-11. Phase: planning complete — architecture, design docs and 23 feature specs
(01-21 with 07a/07b/07c) written and reviewed four times (review logs are kept locally, outside the
repository). Implementation phase 1 (D-73) starts with spec 01. No open questions; RISK-1 open.

## Interview progress

| Round | Topic | State |
| --- | --- | --- |
| 1 | Architecture: storage, users, recovery, offline, stack | Done |
| 1b | Follow-ups: local copies policy, sharing scope, thumbnail encryption | Done |
| 2 | Capture and gallery | Done |
| 3 | Security and operations | Done |
| 3b | Fact verification into `docs/facts.md` | Done |
| 4 | Decisions forced by verified facts | Done |
| 5 | Questions found while writing the architecture | Done |
| 6 | Architecture docs, ADRs, crypto spec, data model, API, dependencies | Done (draft 2 after review 1) |
| 7 | Plan review 1 | Done: 32 findings fixed |
| 8 | Per-feature build specs for Sonnet (`docs/features/`) | Written: 22 of 22 files |
| 10 | Plan review 3 and 4, spec 07c review | Done |
| 9 | Plan review 2 | Done: specs 01-08 vs design, two independent reviewers |

## Vision (programmer's words, condensed)

- Android app now (iOS maybe later) and a web app that is also usable on a phone.
- Capture behaves like Instagram's QuickSnap: fast photo/video capture; more than one photo/video per
  entry.
- An entry also holds a text note that supports markdown. It is a real-life journal.
- Saved to the user's own cloud storage, encrypted, so it does not matter whether the storage is
  Apple, Google, a home server or a paid host.
- Browsing past entries feels like a photo gallery. Filter by tags. Everything is tags, no
  categories.
- Multiple users.
- Performant and secure; that is the point of the app. Reuse an approach from audited open-source
  E2EE storage projects rather than inventing crypto.
- The plan is broken down feature by feature so the coding model does not decide how code is
  written. Performance and security choices (file reading, streaming, caching, etc.) are decided in
  the plan, early.
- The programmer said their user stories are incomplete; the interview fills them in.

## Decisions

| ID | Decision | Source |
| --- | --- | --- |
| D-01 | Own server holds the encrypted metadata index and auth; encrypted file blobs go to any S3-compatible bucket (MinIO at home, Backblaze B2, Cloudflare R2, Wasabi, AWS). Storage providers see only ciphertext | Round 1, answer 1A |
| D-02 | Devices also keep local copies, encrypted at rest. Policy details: D-09, D-12 | Round 1, answer 1A addition |
| D-03 | Multi-user means separate private accounts on one self-hosted server (family/friends) | Round 1, answer 2A |
| D-04 | Sharing entries/journals with other users exists behind feature flag `sharing`, off by default. Permission is read-only (D-39 replaced the original "read/write") | Round 1, answer 2A addition |
| D-05 | Account recovery only through a 24-word recovery key shown at sign-up. No admin recovery. Password and recovery key both lost means data lost | Round 1, answer 3A |
| D-06 | Offline-first: capture and write with no network; sync later | Round 1, answer 4A |
| D-07 | Stack: TypeScript everywhere. One client codebase with Expo + Expo Router for Android and web (React Native Web), platform differences in `*.native.ts` / `*.web.ts` behind interfaces. Server: Node + Fastify, Postgres. Shared package for crypto, domain rules, Zod schemas, sync engine | Q-07 A, Q-08 A, Q-09 A |
| D-08 | Server runs in Docker on a home server or a cheap VPS; any S3-compatible bucket. MinIO replaced by Garage in D-34 | Q-10 A |
| D-09 | Device storage: thumbnails always on device; originals cached up to a user-set size cap; "keep offline" pin per entry. ASSUMED from the programmer's thumbnail answer; confirm in passing | Q-06 (programmer answered about thumbnails only) |
| D-10 | Public share links for people without an account exist behind their own flag `public_links`, off by default, modeled on 1Password item sharing: decryption key only in the URL fragment (never sent to the server), revocable at any time by the owner (server stops serving the ciphertext). Public links are read-only; write access needs an account (D-04) | Q-11 B |
| D-11 | Dependency security policy (programmer: "libraries and deps have to be audited"): see section "Dependency policy" | Round 1 stack answer |
| D-12 | Thumbnails are always encrypted on the server, in the bucket, and in the web app's local storage. On Android only, the local thumbnail cache is plain files in app-private storage by default (lets the image component load file URIs directly, no decrypt-to-memory per scroll), with a setting "Encrypt thumbnails on this device". Consequence: Android auto backup must be disabled (`allowBackup=false`, no backup rules for app data) so plain thumbnails never reach Google Drive device backups | Q-12, programmer: "always encrypted aside from the user's device (local); if it's web, encrypt it" |
| D-13 | The app opens to the gallery, with a prominent capture button. Programmer: "I like it when I can see what has been taken" | R2-1 B |
| D-14 | One entry = one moment: media + note + its own timestamp. Gallery groups entries by day. Programmer: a day can hold several events; moments answer both "what did I do that day" and "on this day what did I do" | R2-2 A |
| D-15 | Capture: tap = photo, hold = video, front/back switch, shots collect in a tray until Save. No dual front+back shot | R2-3 A |
| D-16 | Tags: chips field with autocomplete from the user's own past tags. Hashtags in the note are plain text, not tags | R2-4 A |
| D-17 | Gallery filter: several tags combined with AND, date range, full-text search in notes. No AND/OR toggle | R2-5 A |
| D-18 | Round 2 defaults accepted: edit and delete any time; trash kept 30 days; markdown editor is plain text with a preview toggle; max 20 media per entry (configurable). The 60 s video default is replaced by D-19 | Round 2 defaults, no objection |
| D-19 | No video length cap. Server config sets a per-file size cap and a per-user storage quota. Uploads are resumable (S3 multipart). User setting "Upload large files on Wi-Fi only". No transcoding on device | R2-6 A |
| D-20 | Media can come from the camera or from existing files. Android: system photo picker. Web: file picker, drag and drop, and paste from clipboard (Ctrl+V / Cmd+V). Entry time defaults to the earliest original capture time found in the media (EXIF `DateTimeOriginal`, video creation time); pasted images usually carry no metadata, so the time falls back to now; the user can edit it | R2-7 A, programmer: "same with web, by uploading and then Ctrl+V" |
| D-21 | Gallery: grid of entries under day headers, newest first, jump-to-date picker, plus a month calendar view with a thumbnail on days that have entries | R2-8 B |
| D-22 | "On this day" card at the top of the gallery with entries from the same calendar date in past years | R2-9 A |
| D-23 | Multi-select by long-press (web: also checkbox on hover and Shift+click range): add/remove tag, delete, share | R2-10 A |
| D-24 | Login proof: Argon2id(password, salt) -> key-encryption key material; libsodium `crypto_kdf_derive_from_key` derives separate subkeys: one login key (sent to the server, stored only as a hash) and one key-encryption key (never leaves the device). No SRP | R3-1 A |
| D-25 | Originals stored untouched (EXIF/GPS inside the encrypted file). Public links serve copies with EXIF/GPS stripped. Location shown as coordinates + "Open in Maps"; no in-app map in v1 | R3-2 A |
| D-26 | App lock: Android biometric/PIN on open, auto-lock 5 min in background (configurable); web keys in memory only, lock after 15 min idle, "Keep me unlocked on this browser" opt-in | R3-3 A |
| D-27 | Accounts invite-only now. First admin created by a server CLI command; admins create invite links; admins see users, storage used and quotas, never content. **The codebase must support open sign-up later (R4B) without restructuring**: sign-up eligibility is a policy chosen from config (`signupMode`: `invite` now; `open` later), quotas and rate limits are per user from day one, no code assumes a small fixed user count. Open sign-up is not built in v1 | R3-4 A, programmer: "might scale to R4B soon, make sure the codebase can support it" |
| D-28 | Operator backups: nightly Postgres dump + bucket object versioning (both ciphertext only), documented. In-app export: decrypted zip with media + one markdown file per entry (tags, time, location in front matter) | R3-5 A |
| D-30 | **Superseded by D-38.** Android file encryption: apply react-native-libsodium PR #108 as a pinned `pnpm patch`, reviewed, with known-answer tests proving byte-identical secretstream output vs web `libsodium-wrappers-sumo`; 4 MiB chunks via `expo-file-system` FileHandle through JSI; behind a `FileCipher` interface | R4-1 A |
| D-31 | Removing a share member: server revokes access immediately; no key rotation; UI warns that already-seen content may have been kept | R4-2 A |
| D-32 | Web video playback through our service worker: range requests mapped to 4 MiB ciphertext chunks, fetched with HTTP Range from the bucket, decrypted, answered as 206. Android v1: decrypt to a temp file in app cache, play, delete on lock or app close | R4-3 A |
| D-33 | Android uploads continue after leaving the app through a small native module running a foreground service with a progress notification until the queue is empty | R4-4 A |
| D-34 | Object storage: Garage for self-hosting, Backblaze B2 or Cloudflare R2 for cloud; one S3 adapter for all. Objects are immutable (new object key per change); the server deletes objects only after the 30-day trash plus a grace period; operator backup = scheduled `rclone sync` of the bucket to a second location. Replaces MinIO in D-08 and bucket versioning in D-28 | R4-5 A |
| D-35 | Local hosting = one Docker Compose stack on the programmer's machine: reverse proxy (TLS), API server, Postgres, Garage (single node, data on a local disk path). Clients upload/download directly to Garage through presigned URLs, so Garage's S3 endpoint is published through the same reverse proxy on its own hostname (e.g. `s3.<domain>`). No filesystem storage adapter: Garage gives the same code path as cloud storage | R4-5 follow-up "what if I host locally?" |
| D-36 | Remote access to a locally hosted server goes only through a private mesh VPN: Tailscale, NetBird, or self-hosted Headscale/NetBird. Nothing is exposed publicly. The app does not depend on which VPN; it only needs the server's HTTPS URL. Consequences: (1) when the VPN is off the app works offline (D-06) and syncs later; (2) public links (D-10, flag `public_links`) need a public path for link viewers; with D-36 that requires an extra public ingress (e.g. Tailscale Funnel) limited to the share routes, documented in the deployment guide, and the share routes must proxy ciphertext through the API rather than hand out presigned URLs to the private S3 hostname | R4-6 B, programmer: "tailscale or netbird, either way it works the same; may host my own headscale or netbird server" |
| D-37 | Local data on devices is stored as the server's ciphertext (entry metadata rows, encrypted media and thumbnails). Decrypted metadata exists only in memory; the gallery, tag filter, calendar and full-text search run on an in-memory index built in `packages/core` (one pure implementation for Android and web). An encrypted index snapshot (one blob, encrypted with the master key) speeds up unlock. Consequence: no SQLCipher dependency; Android uses plain `expo-sqlite` for ciphertext rows, outbox and sync cursors; web uses IndexedDB and OPFS. Exception: D-12 plain Android thumbnail cache | Architecture decision (Opus), see ADR 0007 |
| D-38 | Media objects use the STREAM construction as in age (F-CRY-02) on `crypto_aead_xchacha20poly1305_ietf`, seekable, 1 MiB plaintext chunks; full format in `docs/design/crypto.md`. **Supersedes D-30**: no patch of react-native-libsodium is needed, because the function is exposed natively (F-LIB-01) | R5-1 A |
| D-39 | Sharing with other users is **read-only**. No write, no collaborator role. Recipients can view and download entries and journals shared with them; they cannot add, edit or remove anything. Programmer: "no writes, no collabs ... they can just view and read the files and journals". Updates D-04 (which said read/write) | R5-2, answered "neither A nor B: read only" |
| D-40 | HTTPS: the programmer's own domain; DNS records point to the server's VPN IP; Caddy obtains Let's Encrypt certificates through the DNS-01 challenge (Caddy build with the DNS provider's plugin). Works with any VPN; nothing public | R5-3 A, programmer owns a domain |
| D-42 | Sync uses **per-collection monotonic sequence numbers** (`collections.last_seq`, allocated inside the change transaction) instead of a timestamp. Rationale: a global or timestamp cursor can skip a change that commits later with a lower value (Ente avoids this only by keeping boundary rows together, F-ENTE-17). Multi-collection changes lock collections in ascending id order to avoid deadlocks | `data-model.md` §3 |
| D-43 | **Client-generated ids** (22-char base64url of 16 random bytes) for users, collections, entries, media and objects, so every upload and create retry is idempotent. Server rejects ids already owned by someone else | `data-model.md` §1, `crypto.md` §3 |
| D-44 | An entry always has at least one photo/video. Text-only entries are out of scope (not requested) | `data-model.md` §6.1 |
| D-45 | Day grouping uses `takenAtUtc + utcOffsetMinutes`, so a moment stays on the day it happened even when viewed from another time zone. Both values are stored in the encrypted metadata | `data-model.md` §6.1 |
| D-46 | The encrypted **index snapshot is reserved but unused in v1**: the in-memory index is rebuilt on unlock by decrypting rows (2 AEAD ops per entry). A snapshot is added only if a measured unlock on a mid-range Android phone with 20,000 entries exceeds the budget (`UNLOCK_BUDGET_MS`). Avoids building a second persisted structure speculatively | `data-model.md` §6.4 |
| D-47 | Account bootstrap (D-27): the server CLI prints the first admin **invite link**; the admin signs up in the app, because key generation must happen on a client. The server never creates accounts itself | `data-model.md` §2.3 |
| D-48 | Public links operate on **stripped copies** of entries (`entries.purpose = 'link_copy'`, `collections.kind = 'link'`), never on the originals. Copies hold only the media needed for the link, are not shown in the owner's gallery, and are deleted when the link is revoked | `data-model.md` §2.6, §2.11 |
| D-49 | Storage roots: ciphertext objects and the RowStore live in the app's **documents** directory (survive an OS low-storage cache purge; app-managed LRU eviction, D-09); plaintext temp files live in the **cache** directory | `data-model.md` §4.1 |
| D-50 | Review 1 decisions: TypeScript pinned to 6.0.3 (F-TOOL-11); server bundled with esbuild; web app and API share one origin (`kodakiko.<domain>` serves `/` and `/api`), S3 on `s3.kodakiko.<domain>` with one CORS rule; Android picks its server at first run from an invite link or address; quota reserved once at presign; multipart completed server-side with `ListParts`; trash/restore do not change revision; public-link creation is client-driven; sync diff carries object rows; account deletion revokes sessions at once and only the cancel route works until erase | Review 1 |
| D-51 | Notes render markdown with `marked` used **only as a lexer** (`marked.lexer`); our own code renders its tokens to React Native components on both platforms; `marked`'s HTML output is never used, so note text cannot inject markup. Supported tokens defined in spec 13; unsupported tokens render as plain text | OI-1 A |
| D-52 | The journal export (D-28) writes the zip with `fflate` (streaming, zero dependencies), wrapped behind an `ArchiveWriter` port so it can be swapped. Programmer answered "A, I guess": low-stakes, reversible by replacing one adapter | OI-2 A |
| D-53 | Android application id `com.x42697262.kodakiko` (the programmer's `com.0x42697262.kodakiko` is invalid: every segment must start with a letter); confirmed by the programmer (OI-5 = A). Changeable only until the first store release | Programmer |
| D-54 | Crypto vectors live in `packages/core/vectors/` and are replayed by a pure `checkVectors` on Node, on Android and in the browser (diagnostics screen), proving both libsodium wrappers byte-identical (D-11) | Spec 07a design |
| D-55 | Device location for new camera captures is an **opt-in setting, off by default** ("Add location to new photos"). Uses `expo-location` on Android and `navigator.geolocation` on web. Imported files keep their own EXIF/container GPS inside the encrypted original regardless (D-25); the entry's displayed location comes from the earliest media that has one, else the device location when opted in | Spec 09 design (privacy default; programmer may override) |
| D-56 | Capture metadata readers: in-repo, bounded, pure readers in core for JPEG/PNG/WebP EXIF (date, offset, GPS, orientation) and MP4/MOV `mvhd` creation time + `©xyz` location; WebM has no embedded time. On Android, picker imports also use `expo-image-picker`'s `exif` output (covers HEIC). Camera captures use the device clock | Spec 09 design (media.md §4.1 left the reader choice open) |
| D-57 | Multi-select actions (D-23) run as one outbox item per entry through the normal sync path (ordering, retries, conflicts); the server batch endpoints were removed (no client used them; per-entry calls stay within the 300/min entries rate limit at push concurrency 4). Local `entries.localState` tracks metadata only (`synced`, `pending_create`, `pending_update`); pending trash/restore/delete live only in the outbox | Spec 10 design |
| D-58 | Entry time editing uses two validated text fields (date `YYYY-MM-DD`, time `HH:MM`, interpreted in the entry's own UTC offset); no date-picker dependency in v1 | Spec 10 design (no picker on the allowlist) |
| D-59 | Photos support **pinch-to-zoom**, double-tap zoom and panning while zoomed, in the entry viewer and the public-link viewer, using `react-native-gesture-handler` + `react-native-reanimated` (+ `react-native-worklets`), allowlisted for this. Videos are not zoomable | Programmer: "zoom is important" (replaces the earlier no-zoom default) |
| D-60 | Markdown links open only for `http:`, `https:` and `mailto:` schemes and only after a confirmation sheet showing the full URL; other schemes and raw HTML render as plain text; images in notes are never loaded (their alt text is shown), so a note can never make the app contact a third party by itself | Spec 13 design (privacy) |
| D-61 | Network rules: thumbnails download on any network; originals on demand on any network; pinned originals prefetch only on Wi-Fi; uploads larger than the device setting `largeUploadThresholdBytes` (**default 100 MiB**, configurable in Settings › Storage) wait for Wi-Fi when "Upload large files on Wi-Fi only" is on (default on). Network type from `expo-network` (Android); web treats every connection as Wi-Fi | Programmer: configurable, start with 100 MiB |
| D-62 | Video playback uses `expo-video` on both platforms. Web: the media service worker (scope `/`, handling only `/__kdks_media/*`) streams and decrypts; browsers without a working service worker (detected by a ping) fall back to whole-file decryption into a Blob for videos up to `WEB_BLOB_PLAYBACK_MAX_BYTES` (200 MiB), else a message to use Chrome or the Android app. Android: decrypt the whole original to a temp file, then play (D-32) | Spec 15 design |
| D-63 | Web "Keep me unlocked on this browser" (D-26): when on, the app unlocks itself on page load and does **not** idle-lock in that browser; turning it on also keeps the session signed in. "Lock Now" turns it off (deletes the stored wrapped key) and locks, so a manual lock is always real. Android has no such option: it always auto-locks and unlocks with biometrics or the password | Spec 16 design (programmer may override) |
| D-64 | Sharing and public-link actions (create/rename/delete shared journal, add/remove entries, share/unshare, create/revoke link) are **online-only direct calls**, not outbox items: they need the network for key lookup and security-code confirmation anyway. Entries that are not on the server yet (`baseRevision === 0`) cannot be added until they sync | Spec 17 design |
| D-65 | Public links carry **photos and videos**. When creating a link the owner chooses exactly which photos and videos of the selected entries are included (all preselected) and whether notes are included. Photos: re-encoded JPEG (max edge 1600, quality 85, no metadata). Videos: container metadata removed without re-encoding by an in-repo MP4/MOV rewriter (drops `udta`/`meta`/`uuid` boxes, zeroes creation and modification times, fixes chunk offsets), WebM copied as recorded (no location fields); every output is verified by the readers before upload; tags and location are never included. Link copies live only on the server | Programmer: "it should also contain videos … select which photos/videos and if include notes or not" (replaces photos-only) |
| D-66 | Journal export (D-28) = zip parts `kodakiko-export-<date>-part<NN>.zip`, each below `EXPORT_PART_MAX_BYTES` (3.75 GiB), media stored uncompressed, notes as markdown with YAML front matter; entries never split across parts. Because `fflate` writes no Zip64 (F-LIB-26), the server's `MAX_UPLOAD_BYTES` is capped at 3.5 GiB (default and maximum). Android writes into a folder chosen with the system picker; web writes into a folder chosen with `showDirectoryPicker` when available, else downloads each part from memory with parts limited to 1 GiB. Own, non-trashed entries only | Spec 20 design |
| D-67 | Device setting `originalCacheCapBytes = 0` means **no limit** (guidelines 4.3 convention); clearing downloaded originals is a separate action | Spec 20 / review of spec 14 |
| D-68 | The production Caddy image is built with the DNS module of the operator's DNS provider (build argument). The deployment guide documents **Cloudflare DNS** first; other providers use the same build argument and a one-line Caddyfile change. Cloudflare confirmed by the programmer (OI-3 closed) | Spec 21 design; programmer |
| D-69 | The web CSP allows `'wasm-unsafe-eval'` (libsodium WebAssembly) and inline scripts only by SHA-256 hash generated at build time; `style-src 'unsafe-inline'` for React Native Web's runtime styles | Spec 21 design (security.md §3) |
| D-70 | Allowed dependency licenses (CI gate): MIT, ISC, BSD-2-Clause, BSD-3-Clause, Apache-2.0, 0BSD, BlueOak-1.0.0, CC0-1.0, Unlicense, MPL-2.0, Python-2.0, CC-BY-4.0. Anything else fails CI and needs an explicit decision | Spec 21 design (programmer may tighten) |
| D-71 | Account deletion request: the server revokes every session (D-29); the app then clears its session token (keeping local data), signs out and shows "Your account will be deleted on (date). Sign in before then to keep it."; signing in reaches the cancel screen | Review 4 (spec 20 §2) |
| D-72 | Test-first development: for every spec, Sonnet writes the spec's tests first, runs them and records that they fail for the expected reason, then implements until they pass. User-visible behaviors are listed once in `docs/features/behaviors.md` (Given/When/Then, each mapped to the spec and the test that proves it); a spec is done only when all its behaviors' tests pass | Programmer: TDD from phase 1 "so we can attest the behaviors of the app" |
| D-73 | Phase 1 is **local**: the app and server run on the programmer's own machine/network (local compose stack, LAN or VPN); production deployment, release automation and public hosting (spec 21's CI, release workflow, registry images, public profile) move to a later phase. The Android app chooses its server at first run and can change it later (Settings › Account › Change Server…, which signs out); the web app talks to the server it is served from | Programmer: "start local support, no need for online servers; the app can be configured to which server it'll connect to" |
| D-74 | Screen behaviors are tested automatically: Playwright 1.64.1 (web, Chromium desktop + Pixel 7 emulation) and Maestro 2.12.1 (Android emulator) against a local HTTP-on-localhost UI test stack that uses the production CSP; elements found by `testID` = text key (spec 07c §2); flows `U-NN` written before each screen spec's code. Pinch, fingerprint, background upload and Wi-Fi rules stay manual | Programmer (OI-4 = A) |
| D-75 | Server setting `RATE_LIMITS_ENABLED` (default `true`). `false` is accepted only when `REQUIRE_HTTPS=false` and `PUBLIC_BASE_URL` is `localhost`/`127.0.0.1`, and logs a warning at boot; used only by the local UI test stack, where every request comes from one address | Opus, review of spec 07c (overridable) |
| D-76 | Opus decides what is tested; Sonnet writes the test code. Sonnet may add extra tests (listed in its report) but may never delete, skip, loosen or rewrite a planned test; a test that looks wrong → stop and ask (`features/README.md` rule 9) | Programmer |
| D-77 | Forwarded headers are trusted by **peer address**, not hop count: setting `TRUSTED_PROXIES` (default `loopback,uniquelocal`) is passed to Fastify's `trustProxy`; Fastify 5.12 treats numeric hop counts as trust-nothing. The API port is never published; only Caddy on the private network reaches it | Implementation of spec 03 (Fastify 5.12.8 `getTrustProxyFn`) |
| D-78 | The API's bucket key has `--owner` on the bucket (needed for `PutBucketCors` via `apply-bucket-cors`). Accepted because the same key can already delete every object; recorded as a limitation in security.md | Implementation of spec 05 (F-S3-06) |
| D-79 | Every AEAD additional data is valid UTF-8 text (Android wrapper limitation, F-LIB-29). STREAM chunk AD = UTF-8 of the lowercase hex of the 24-byte header (injective, same binding); sealed-blob AD unchanged (`context 0x00 id` is valid UTF-8). The native Sodium adapter converts AD with `utf8Decode` and throws if `utf8Encode(utf8Decode(ad))` ≠ `ad` | Implementation of spec 07a |
| D-80 | Android toolchain via `flake.nix` (nixpkgs pinned to akon-money's revision `d4079514`, so store paths are shared): platform 37, build-tools 37.0.0, CMake 3.22.1 (CMake 4 rejects expo-sqlite/react-native-libsodium `cmake_minimum_required(3.4.1)`), NDK **27.0.12077973** reused from the store instead of React Native 0.88's 27.1.12297006 (saves ~2.5 GB; Expo reads `ext.ndkVersion` first), JDK 17. No emulator in the Kodakiko shell: UI tests use the existing `akon` AVD (API 34 x86_64, KVM) started from akon-money's shell. Cost measured: 0.7 GB | Programmer: "only 8.1 GiB free" |
| D-81 | Android UI flows reach the UI stack through the emulator host alias `10.0.2.2` (not `adb reverse`, which forwards nothing on this setup, F-AND-02): Caddy site `http://:9580`, `10.0.2.2` added to the dev-only local-HTTP hosts, API started with `PUBLIC_BASE_URL`/`S3_PUBLIC_ENDPOINT` on `10.0.2.2` for the Android runner | Opus, debugging spec 07b Android flows |
| D-82 | **Temporary (programmer, 2026-11-12):** implementer agents run on **Opus 5.5** instead of Sonnet 5.5 (runner default `anthropic-account-2/claude-opus-5-5`, fallback `anthropic/claude-opus-5-5`; in-session agents also Opus). Switch back to Sonnet only when the programmer says so. The implementer rules (spec-only, test-first, guard) are unchanged | Programmer |
| D-83 | **Local-only mode (Android only).** `/welcome` offers "Use without a server" next to entering a server address. Account keys are created on the device with the spec 04 crypto (password + recovery key; biometric unlock as in spec 16); entries stay in the local index and outbox, nothing syncs. The user can **connect a server later**, which uploads everything through the normal sync path. Data safety: original eviction (spec 14) is **off** in this mode and setup shows a warning that the phone holds the only copy (journal export, spec 20, is the backup); no periodic export reminder. Web has no local-only mode (browser storage can be evicted). Built as new spec **20a** between 20 and 21; spec 20 stays as written. Details open in OI-6 | Programmer 2026-11-14: 1A 2A 3B 4A 5A |
| D-41 | Two deployment profiles with **identical code**: `private` (now: VPN-only, D-36) and `public` (later: reachable from the internet). The difference is ingress and config only. Everything a public deployment needs is built from day one (see "Public deployment readiness" below); turning public on is a documented checklist, not a code change | Programmer: "what if I open this to public? ... for now my own private network, but opening to the public is an architecture that should be supported" |
| D-29 | Round 3 defaults accepted: threat model as stated; username sign-in, no email; account deletion = immediate lock + erase after 7 days, cancellable; HTTPS required; rate limits on sign-in, invite redemption, share-link access | Round 3 defaults, no objection |

## Defaults assumed (stated in round 1, programmer did not object)

1. Crypto only through libsodium; no hand-made primitives. Per-file key, XChaCha20-Poly1305
   (secretstream for files); master key unlocked from the password with Argon2id.
2. Notes, tags, dates and locations are encrypted; the server cannot read content or metadata
   beyond sizes, counts and timestamps of sync operations.
3. Tag filtering and search run on the device against a local decrypted index.
4. Videos are encrypted in chunks so playback can stream and seek.
5. Thumbnails are generated and encrypted on the device so the gallery loads fast.

## Implications already noted (to be detailed in the architecture docs)

- D-02 local copies: store on the device the **same ciphertext** that is uploaded (no second
  encryption scheme, no re-encryption). The local index database is encrypted too (Android:
  SQLCipher-backed SQLite; web: values encrypted before IndexedDB/OPFS). On Android the master key
  is wrapped by an Android Keystore key, optionally gated by biometrics; on web it lives in memory,
  with optional "keep me unlocked" by wrapping it with a non-extractable WebCrypto key in
  IndexedDB (explicit user choice, guidelines 9.1).
- D-04 sharing: the Ente-style model fits: each journal/collection has its own collection key; the
  key is sealed to the recipient's X25519 public key (libsodium sealed box). Read vs write is
  enforced by the server (who may add/modify files in the collection); the crypto only grants
  readability. Revoking access means rotating the collection key for future entries; already
  downloaded content cannot be un-shared (must be stated in the UI and docs).
- D-05: the password must never reach the server in usable form, because it derives the key that
  unlocks everything. Login uses SRP (as Ente does) or a separate auth key derived from the
  password. To decide in round 3.
- D-06: needs a sync engine with an outbox on the device, idempotent uploads (client-generated IDs),
  a server-side monotonic change cursor for pulling diffs, and a conflict rule for edited notes.

## Reference projects

| Project | Why relevant | Verified? |
| --- | --- | --- |
| Ente (ente.io), photos | Open source, E2EE photos, self-hostable server ("Museum"), S3-compatible storage, libsodium, sharing via sealed collection keys, SRP login, recovery key. Most likely the project the programmer forgot | Audit by Cure53: UNVERIFIED. Verify the audit reports and the current crypto scheme from Ente's repo and docs before writing the crypto spec |
| Cryptomator | Open source encrypted vault placed on any cloud drive | Audit by Cure53: UNVERIFIED |

## Stack discussion (Q-07..Q-10)

Key fact for the choice: with E2EE the server is thin (auth, encrypted metadata rows, sync cursor,
sharing keys, quotas, presigned upload/download URLs). Almost all logic runs on the clients. So
sharing code **between the Android and web clients** matters most.

| Option | For | Against |
| --- | --- | --- |
| A. TypeScript: Expo (React Native) + React web + Node (Fastify) + Postgres | One language; one shared package for crypto, domain rules, Zod schemas and the sync engine; Sonnet is strongest in TS/React; iOS later is mostly free; matches guidelines 13.1/13.3 | Large-file chunked encryption in React Native needs native file I/O and JSI crypto (react-native-libsodium, Expo FileSystem File API); decrypted video playback needs a temp-file or local-stream strategy |
| B. Flutter (mobile + web) + Go server (Ente's stack) | Fast native camera and gallery; single UI codebase; Go server is one small binary, low RAM on a home server | Flutter web is heavy and renders on a canvas (Ente itself uses Next.js for its web app); two languages; no shared code with the server |
| C. Native Kotlin Android + React web + server | Best Android performance and camera control | Three codebases; iOS later is a rewrite; the most code for Sonnet to write |

Sub-choices if A:

- A1: one UI codebase with Expo Router for Android and web (React Native Web), platform files
  (`*.native.ts` / `*.web.ts`) behind adapter interfaces for camera, storage, crypto I/O.
- A2: Expo for Android plus a separate React (Vite) PWA for web, both sharing the core package.

Server language: Node/Fastify (shares Zod schemas with the clients) or Go (single binary, lower
memory). Uploads go straight to the bucket through presigned URLs, so server CPU load is low either
way.

## Open questions

| ID | Question | Recommended |
| --- | --- | --- |
| OI-3 | ~~DNS provider~~ — closed: **Cloudflare** (D-68 default confirmed) | Closed 2026-11-11 |
| OI-4 | ~~UI test tooling~~ — closed: Playwright + Maestro (D-74) | Closed 2026-11-11 |
| OI-5 | ~~Android id~~ — closed: `com.x42697262.kodakiko` (D-53) | Closed 2026-11-11 |
| OI-6 | ~~Spec 20a details~~ — closed: settled in `docs/features/20a-local-only-mode.md` §0 (reuse device keys and ids at connect; username chosen locally and changeable at connect; server-only rows hidden; `account.mode` gates sync, refresh and eviction; local mode ships in phase 1 debug APKs, signed release builds stay in phase 2) | Closed 2026-11-14 (Opus) |
| RISK-1-result | 2026-11-12: Argon2id 256 MiB × 16 ops — web (Firefox, Ryzen 5 5600H) median 4.6 s; Android emulator (API 34 x86_64, KVM) median 4.3 s; vectors 38/38 on native libsodium (D-79 confirmed). A real phone measurement is still required before release | Measured by Sonnet (web) and Opus (emulator) |
| RISK-1 | Argon2id at 4 GiB total work may be too slow on web (single-thread WASM) or freeze Android's JS thread; spec 04 records a Node baseline, spec 07a measures on a phone and in a browser against an 8 s budget; over budget = programmer decides whether to lower the floor | Measured in spec 07 |

Answered round 5 questions, kept for the record:

| ID | Question | Answer |
| --- | --- | --- |
| R5-1 | Media encryption format. Fact F-CRY-01: secretstream cannot seek, which breaks D-32 (web video seeking). Proposal: replace secretstream for media with the STREAM construction exactly as age uses it (F-CRY-02), on libsodium `crypto_aead_xchacha20poly1305_ietf`: per-file random key, nonce = 16-byte random file prefix + 7-byte big-endian chunk counter + 1-byte final flag, 1 MiB plaintext chunks. Seekable, audited design lineage, and `react-native-libsodium` already exposes this function natively, so D-30 (patching PR #108) is no longer needed. A switch to STREAM; B keep secretstream and give up seeking (whole-file decrypt like Ente) | A |
| R5-2 | Meaning of "write" permission on a shared journal (A collaborators add own entries; B also edit others') | Neither: read-only sharing (D-39) |
| R5-3 | HTTPS inside the VPN (A own domain + DNS-01; B Tailscale certs; C self-signed) | A (D-40) |

## Public deployment readiness (D-41)

Built from day one, active in both profiles:

1. Per-user storage quotas and upload size caps (D-19); per-IP and per-username rate limits on
   sign-in, recovery, invite redemption, share-link access (D-29).
2. No username enumeration: `POST /auth/params` returns deterministic fake Argon2id parameters and
   salt for unknown usernames (salt = first 16 bytes of HMAC-SHA-256(server secret, username)).
3. Client-side password strength floor (Ente's 2023 audit high finding was weak passwords on web,
   F-ENTE-01) and server-enforced Argon2id parameter floor.
4. Security headers: HSTS, strict CSP, `X-Content-Type-Options`, `Referrer-Policy: no-referrer`
   (keeps share-link fragments and paths out of referrers), `frame-ancestors 'none'`.
5. Request body limits, timeouts, structured logs with redaction, health endpoints.

Added only when going public (config + ingress, no code change):

1. Ingress: Cloudflare Tunnel, a VPS reverse proxy, or port forwarding; same domains (D-40).
2. `signupMode` stays `invite` unless the operator switches to `open` (D-27).
3. Public links (D-10) work directly; share routes may still proxy ciphertext.

Known risk to document for `open` sign-up: the operator hosts encrypted content they cannot see
or moderate; quotas and invite-only limit abuse, open sign-up removes that limit.

## Round 4 follow-up (answered B, recorded as D-36)

| ID | Question | Recommended |
|---|---|---|
| R4-6 (answered B) | How phones outside the home reach a locally hosted server: A Cloudflare Tunnel (public HTTPS hostname, no open router ports, works behind CGNAT; public links work; Cloudflare sees traffic metadata but only ciphertext content; free plan caps request bodies at 100 MB (UNVERIFIED), so multipart part size stays well below that); B Tailscale (only devices in your tailnet, nothing public; every family member installs Tailscale; public links need Tailscale Funnel); C port forwarding + own domain + Caddy with Let's Encrypt (needs a public IPv4 or IPv6, not behind CGNAT). The code supports all three; the answer picks the one the deployment guide documents first | A |

## Dependency policy (D-11, to become an ADR)

1. Allowlist file `docs/dependencies.md`: every runtime and dev dependency with exact version,
   purpose, maintainer health, known audits, and the date it was reviewed. Sonnet may not add a
   dependency that is not on the list; adding one is a design decision for Opus/programmer.
2. pnpm with a committed lockfile, exact versions (no `^`/`~`), install scripts blocked except an
   explicit allowlist (`onlyBuiltDependencies`), and a minimum release age before new versions
   are accepted (`minimumReleaseAge`). Verify both pnpm settings exist in the pinned pnpm version
   (UNVERIFIED).
3. CI gates: `pnpm audit` plus OSV-Scanner fail the build on known vulnerabilities; license check;
   SBOM (CycloneDX) per release.
4. Updates through Renovate with a delay and one PR per dependency; crypto-related updates reviewed
   by a human.
5. Crypto comes only from libsodium (audited). Wrappers: `libsodium-wrappers-sumo` (web/server)
   and `react-native-libsodium` (Android). The React Native wrapper's audit status is UNVERIFIED and
   must be checked; if unaudited, the spec pins a version and adds known-answer tests that prove
   identical output across both wrappers.

## Round 2: capture and gallery

| ID | Question | Recommended |
| --- | --- | --- |
| R2-1 | App opens to: A camera directly (QuickSnap feel); B gallery with a capture button | A |
| R2-2 | Entry model: A one entry = one moment (media + note + own timestamp), gallery groups by day; B one entry per day you keep appending to | A |
| R2-3 | Capture: A back/front switch, tap = photo, hold = video, shots collect in a tray until Save; B A plus dual front+back shot (BeReal style) | A |
| R2-4 | Tags: A chips field with autocomplete from your own past tags; B A plus `#hashtags` typed in the note also become tags | A |
| R2-5 | Gallery filter: A several tags combined with AND, plus date range, plus full-text search in notes; B A plus an AND/OR toggle | A |

Defaults stated with round 2 (programmer may object): edit and delete entries any time; deleted
entries go to a trash for 30 days; markdown editor is plain text with a preview toggle; limits of
20 media per entry and 60 s per video, both configurable in server config.

Answers: R2-1 B, R2-2 A, R2-3 A, R2-4 A, R2-5 A (recorded as D-13..D-18).

### Why a video limit at all (answer to the programmer's question)

The 60 s default was a sizing guess, not an architectural limit. The design (chunked secretstream
encryption, S3 multipart resumable upload, chunk-aligned streaming decrypt for playback) handles any
length with constant memory. The real costs grow with file size, not seconds:

- Size: 1080p H.264 on Android records at roughly 10 to 17 Mbit/s, about 75 to 130 MB per minute;
  4K at roughly 40 to 50 Mbit/s, about 300 to 375 MB per minute (typical figures, UNVERIFIED per
  device).
- Upload time and mobile data: 100 MB at 10 Mbit/s upload is about 80 s.
- Storage quota on the user's bucket.
- Encryption is not the bottleneck: libsodium XChaCha20 runs at hundreds of MB/s on modern ARM
  (UNVERIFIED, measure on a target device).
- No transcoding on device (slow, battery); videos are stored as recorded.

### Round 2 second half (answered: R2-6 A, R2-7 A, R2-8 B, R2-9 A, R2-10 A)

| ID | Question | Recommended |
| --- | --- | --- |
| R2-6 | Video limit: A no length cap; per-file size cap and per-user quota in server config; resumable uploads; setting "upload large files on Wi-Fi only"; B length cap (configurable, default 3 min) | A |
| R2-7 | Adding media: A camera plus picking existing photos/videos from the phone gallery (entry time defaults to the photo's original time); B camera only | A |
| R2-8 | Gallery layout: A grid of entries grouped under day headers, newest first, with a jump-to-date picker; B A plus a month calendar view showing a thumbnail on days with entries | B |
| R2-9 | "On this day": A a card at the top of the gallery with entries from the same date in past years; B none | A |
| R2-10 | Multi-select: A long-press to select several entries, then add/remove tag, delete, share; B none in v1 | A |

## Round 3: security and operations (answered: R3-1 A, R3-2 A, R3-3 A, R3-4 A, R3-5 A)

| ID | Question | Recommended |
| --- | --- | --- |
| R3-1 | Login proof: A the client derives a separate login key from the Argon2id output (libsodium KDF subkey); the server stores only a hash of it; the encryption key is a different subkey and never leaves the device. Only libsodium, no extra crypto dependency. B SRP like Ente (password-derived value never sent, but needs an SRP library that must be audited) | A |
| R3-2 | Photo metadata and location: A keep original files untouched (EXIF and GPS stay inside the encrypted file); strip EXIF/GPS from files shared through public links; show location as coordinates with "Open in Maps"; no in-app map in v1 (map tiles reveal the area to the tile provider); B strip location at capture/import; C A plus an in-app map view | A |
| R3-3 | App lock: A Android unlock with fingerprint/PIN on open, auto-lock after 5 min in background (configurable); web keeps keys in memory only, locks after 15 min idle, "Keep me unlocked on this browser" opt-in; B no app lock while signed in | A |
| R3-4 | New accounts: A invite-only; the first admin is created with a server CLI command; admins create invite links; admins see users, storage used and quotas, never content; B open sign-up switch in server config (default off) on top of A | A |
| R3-5 | Backups and export: A server operator backups (nightly Postgres dump + bucket versioning, documented, both contain only ciphertext) plus in-app export of a user's journal as a decrypted zip (media + one markdown file per entry with tags and time in front matter); B only the in-app export | A |

Defaults stated with round 3 (programmer may object):

1. Threat model: protect against the storage provider, the server operator, a network attacker,
   a database leak, and a stolen locked phone. Not protected: a phone or browser that is unlocked
   and compromised (malware, root), and someone watching the screen.
2. Sign-in with a username, no email needed (recovery is through the recovery key, so email has no
   job). No mail server to run.
3. Account deletion: immediate lock, data deleted after a 7-day grace period; user can cancel
   within it.
4. HTTPS required everywhere; the server refuses plain HTTP except on localhost in development.
5. Rate limits on sign-in, invite redemption and share-link access.

## Round 4: decisions forced by verified facts (answered: all A, see D-30..D-35)

| ID | Question | Recommended |
| --- | --- | --- |
| R4-1 | Android file encryption. Fact F-LIB-02: `react-native-libsodium` has no secretstream natively. A apply PR #108 as a pinned `pnpm patch`, reviewed by us, with known-answer tests proving byte-identical output with web `libsodium-wrappers`; 4 MiB chunks pass through JSI; all behind a `FileCipher` interface so B can replace it later. B own Expo native module (Kotlin + C++ JNI over libsodium) doing file-to-file encryption; fastest, plaintext never in JS, most native code. C self-built chunked XChaCha20-Poly1305 AEAD from exposed functions; violates "no hand-made crypto" and is incompatible with web secretstream | A |
| R4-2 | Removing a member from a shared journal. Fact F-ENTE-13: Ente does not rotate keys. A like Ente: server removes access immediately, no key rotation, UI states that content already seen may have been kept; B rotate the collection key and re-wrap every file key in the collection (only helps if the server itself is compromised) | A |
| R4-3 | Web video playback. Fact F-ENTE-15: Ente decrypts the whole file into memory. A our service worker answers the video element's range requests, fetches only the needed 4 MiB ciphertext chunks, decrypts them, returns 206 responses: instant start, seeking, constant memory; B whole-file decrypt into a Blob with a size cap, larger videos download-only. Android in both cases v1: decrypt to a temp file in app cache, play, delete on lock (start delay grows with length) | A |
| R4-4 | Android uploads after leaving the app. Fact F-LIB-14: no maintained background-upload library; WorkManager runs at most every 15 min. A small native module runs an Android foreground service with a notification ("Uploading 3 items") until the queue is empty; B uploads only while the app is open, plus WorkManager catch-up every 15+ min | A |
| R4-5 | Home-server object storage. Facts F-S3-01/02: MinIO is unmaintained; Garage is active but has no bucket versioning. A Garage for home, Backblaze B2 or Cloudflare R2 for cloud; our design does not need bucket versioning: objects are immutable (a new object key per change) and the server deletes objects only after the 30-day trash plus a grace period; operator backup = scheduled `rclone sync` of the bucket to a second location; B SeaweedFS or RustFS (not yet checked); C cloud only | A |

Consequences already applied regardless of the answers: D-08 no longer names MinIO; D-28 bucket
versioning is replaced by immutable objects + delayed deletion if R4-5 A is chosen.

## Topics queued for later rounds

None. Public link options were settled from 1Password's documented options (F-1P-03,
`data-model.md` §2.11; email restriction dropped because there is no email, D-29). Round 3 topics
were answered (D-24..D-29).

## Next step

1. Sonnet implements spec 01 (`docs/features/01-scaffolding.md`), following `AGENTS.md` §"Sonnet:
   each session".
2. The programmer passes Sonnet's report to Opus; Opus reviews the code against the spec, then the
   next spec starts.
