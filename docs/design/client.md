# Client design

Design reference written before the build (2026-11-11). Where it differs from the code, the code
and the feature specs' implementation notes are right.

Client parts describe the Expo app (v0.1–v0.3); since v0.4.0 the client is the Kotlin Android app
(`apps/android`, [architecture.md](../architecture.md)) and there is no web app.

Normative for `apps/client/`. Covers navigation, screens, state, the composition root, and platform adapters in enough detail that each screen is a bounded build task.

One codebase, Android and web, through Expo Router (D-07). Platform differences live in
`*.native.ts` / `*.web.ts` files behind the ports in `architecture.md` §4.2; screens and features
never branch on platform directly.

## 1. Navigation map

```
(auth)                      unauthenticated group
  /welcome                  Android: paste an invite link or type the server address; web: sign in / sign up
  /invite                   web: reads #token, goes to /sign-up prefilled
  /sign-in
  /sign-up                  needs an invite token (D-27) unless config says signupMode = open
  /securing                 static "Securing your account…" screen during Argon2id (spec 07b)
  /recovery-key             show + confirm the 24 words (spec 07b)
  /trash                    trashed entries; restore or delete permanently (spec 11)
  /day/[date]               entries of one day, from the calendar (spec 12)
  /play/[entryId]/[mediaId] full-screen video player (spec 15)
  /recover                  recovery-key flow
  /cancel-deletion          only screen reachable while status = pending_deletion (api.md §4)
  /lock                     app lock screen (D-26): biometric or password
(tabs)                      authenticated group
  /                         Gallery (default tab, D-13)
  /calendar                 Month calendar (D-21)
  /capture                  Camera-first capture (opens over the tabs as a modal sheet)
  /settings                 Settings root
Entry stack
  /entry/[id]               Entry detail (viewer)
  /entry/[id]/edit          Edit note/tags/time/location
  /entry/new                Compose from imported media (no camera)
Shared (flag sharing)
  /(tabs)/shared            Shared tab: my shared journals and journals shared with me (spec 17)
  /collections/[id]         Collection view (grid, same components as gallery)
  /collections/[id]/share   Share with a user, security code (spec 17)
  /links/new                Create a public link (spec 18)
Public link viewer (web route, no auth)
  /s/[linkId]               Renders a link collection from #secret
Settings subroutes
  /settings/account         username, storage used, sign-out, delete account
  /settings/security        app lock timing, encrypt-thumbnails toggle, keep-me-unlocked (web),
                            recovery key (show once / re-show with password)
  /settings/storage         cache sizes, original cache cap, keep-offline management, sync now
  /settings/links           (flag public_links) list and revoke public links (spec 18)
  /settings/export          journal export (spec 20)
  /settings/admin           admin only (spec 19)
  /settings/password, /settings/delete-account (spec 20)
  /settings/about           version, licenses, server config
```

Expo Router file layout mirrors this under `apps/client/app/`. The `(auth)` and `(tabs)` groups gate
on auth state; the `/lock` route is presented modally over whatever was showing when the app locked.

## 2. Screens

Each screen = route file (thin) + a feature folder with components and a view-model hook. The
view-model hook reads the in-memory index and the RowStore through services; screens never touch
adapters or crypto directly (layers, guidelines 3).

| Screen | Primary content | Primary action |
|---|---|---|
| Gallery | FlashList grid of entries under day headers, newest first; "On this day" card on top when matches exist (D-22); filter bar (tags AND-combined, date range, note search, D-17) | Tap entry → detail; long-press → multi-select (D-23); FAB → capture |
| Calendar | Month grid, each day with entries shows its cover thumbnail (D-21); tap a day → that day's entries | Tap day |
| Capture | Camera preview; tap = photo, hold = video, front/back switch; tray of taken shots; import button (picker/paste) (D-15, D-20) | Save → creates the entry |
| Entry new (web/import) | Imported media tray + note + tags before first save | Save |
| Entry detail | Full-screen media pager, note (rendered markdown), tag chips, time, location row, share/menu | Edit; delete; share |
| Entry edit | Markdown plain-text editor with preview toggle (D-18), tag chips with autocomplete (D-16), time and location editors | Save |
| Collections | List of shared collections with covers | Open; share |
| Share sheet | User picker (search by username), fingerprint display for verification (crypto.md §8.1) | Share |
| Public link viewer | Read-only grid + entry view for `#secret` links; download buttons | View |
| Lock | Biometric prompt (Android) or password field (web/Android fallback) | Unlock |
| Settings | Grouped list per the subroutes above | — |

UI rules from guidelines 10 apply throughout: one obvious primary action per view, sheets for short
tasks, sentence case, verbs on buttons, disabled-with-hint for flagged-off features (sharing,
public links), light/dark through color tokens, all strings in
`src/text/`.

## 3. State model

| Kind | Where | Rule |
|---|---|---|
| Server state (entries, collections, media, objects) | RowStore (ciphertext) + in-memory decrypted index | The index is derived; rebuilt on unlock; never persisted in plaintext (D-37) |
| Sync state | RowStore (`outbox`, cursors, `localState`) | `sync.md` |
| Session/auth state | `KeyVault` + memory (master key, private key); session token per platform (data-model.md §4) | Cleared on lock/sign-out |
| UI state (selection, filter, sheet open, scroll position) | React state / URL params only | Never persisted (guidelines 8) |

There is no React Query in v1: "server state" is local-first. The in-memory index is the query
cache. `packages/core/src/index/gallery-index.ts` holds the pure index (spec 11 §1);
`apps/client/src/services/index/index-service.ts` builds it on unlock and updates it on
`entriesChanged`; hooks in `src/features/gallery/hooks.ts` (`useIndexVersion`,
`useGallerySections(filter)`, `useIndexedEntry(id)`, `useOnThisDay`, `useIndexStatus`) read it.
Mutations update the RowStore, which emits `entriesChanged`, which updates the index (optimistic),
and reconcile through the outbox; entries with unconfirmed changes or uploads show a "not synced"
dot, and dead outbox items surface in the sync banner with Retry (guidelines 8).

### 3.1 The in-memory index (D-37, D-46)

Built on unlock by decrypting every entry's metadata (2 AEAD operations per entry). Its exact
types, structures (`entries`, `byTag`, `tagCounts`, `words`, `byMonthDay`, ordered ids), query
rules (tags AND, inclusive date range, prefix search with AND across query tokens), tokenization,
"On this day" and calendar helpers are specified in spec 11 §1, which is normative for them.

- Budget check (D-46): spec 11 §5 measures the build with 20,000 entries on a mid-range Android
  device; if it exceeds `UNLOCK_BUDGET_MS` (2 s), the encrypted snapshot blob (reserved in
  `crypto.md` §3 as `indexSnapshot`) becomes a new spec.

## 4. Composition root

`apps/client/src/composition/create-app-context.ts`:

```ts
export async function createAppContext(): Promise<AppContext>;

export type AppContext = {
  readonly settings: ClientSettings;
  readonly sodium: Sodium;
  readonly fileStore: FileStore;
  readonly rowStore: RowStore;
  readonly keyVault: KeyVault;
  readonly camera: Camera;
  readonly mediaPicker: MediaPicker;
  readonly uploader: Uploader;
  readonly videoSource: VideoSource;
  readonly thumbnailCache: ThumbnailCache;
  readonly http: Http;
  readonly clock: Clock;
  readonly services: Services;
  readonly indexService: IndexService;
};
```

| Field | Meaning |
|---|---|
| `settings` | validated public config + device settings |
| `http` | fetch + timeouts + auth header injection |
| `clock` | injectable for tests |
| `services` | auth, entries, media, sync, sharing, links, export |

Built once at app start (native: module scope; web: before the router mounts), provided through one
React context. Tests build the same context with fakes (guidelines 3: one wiring place; tests call
the real entry with fakes, never module mocks).

## 5. Platform adapter notes

| Port | Decisions already made |
|---|---|
| `Sodium` | crypto.md §2. Web Argon2id runs in a dedicated Web Worker |
| `FileStore` | Android: `expo-file-system` File/FileHandle (F-LIB-05). Web: OPFS. Interface in spec 07a §4.3 (`writeObject`, `readRange`, `size`, `delete`, `listSizes`) |
| `RowStore` | Android: `expo-sqlite` (plain DB; rows are ciphertext, D-37). Web: IndexedDB (`idb` key-value wrapper decided in `docs/dependencies.md`). One repository interface over both |
| `KeyVault` | Android: `expo-secure-store` with `requireAuthentication` when biometrics enrolled + `expo-local-authentication` fallback (crypto.md §9.1). Web: memory; opt-in non-extractable WebCrypto wrap (crypto.md §9.2) |
| `Camera` | Android: vision-camera. Web: getUserMedia; photo = canvas frame grab; video = MediaRecorder (WebM). Permission flows per platform |
| `Uploader` | Android: `packages/native-upload` foreground service (D-33, storage.md §3.3). Web: in-page bounded queue |
| `VideoSource` | Web: service worker (storage.md §5). Android: temp-file decrypt |
| `ThumbnailCache` | storage.md §6 |

IndexedDB rule (web `RowStore`): a transaction auto-commits as soon as the microtask queue is
empty, so code inside `RowStore.transaction(work)` awaits only IndexedDB operations. Services
decrypt, encrypt and fetch **before** opening the transaction and pass plain rows into it.

## 6. App lock and lifecycle (D-26)

| Event | Behavior |
|---|---|
| App start, session valid | Android: unlock via KeyVault (biometric prompt). Web: show `/lock` (password) unless "keep me unlocked" produced a stored wrapped key (D-63: then no idle lock either) |
| Backgrounded | Start the auto-lock timer (Android default 5 min, web idle 15 min; user-configurable) |
| Lock fires | The ordered lock steps of spec 16 §1: stop sync, cancel downloads, release video and originals, clear thumbnails, tray, temp files, index and UI state, then wipe keys; show `/lock` |
| Sign-out | Everything in lock, plus delete RowStore rows, FileStore objects, KeyVault items and the session token; server session revoked |

## 7. Web specifics

- Output mode `single` (SPA) for v1 (F-LIB-11); static export later if wanted.

### 7.1 Which server the app talks to

- Web: same origin. The web app is served from `https://kodakiko.<domain>/` and calls
  `/api/v1/...` relative to it (`deployment.md` §2), so the API needs no CORS.
- Android: the server is chosen at runtime, because the app is self-hosted. `/welcome` accepts
  either an invite link (`https://<host>/invite#<token>`: host and token are both taken from it)
  or a bare server address (for signing in to an existing account). The app normalizes it to
  `https://<host>`, calls `GET /api/v1/config` with a timeout, and stores the base URL in
  `expo-secure-store` (key `kodakiko.server`, spec 07a §4.5) only after a valid `PublicConfigDto`
  comes back. Plain `http://` is
  refused. Android App Links for invite URLs are out of scope for v1.
- S3 hosts are never configured on the client: they arrive inside presigned URLs.
- Service workers: only the media worker (`storage.md` §5; scope `/`, handles only
  `/__kdks_media/*`). No app-shell caching in v1, so no stale-shell problems; PWA install works with a manual `manifest.json` (F-LIB-11).
- COOP/COEP headers are **not** required (we do not use the wasm SQLite build on web, F-LIB-07).
- Clipboard paste (D-20) works in the browser only; the Android app uses the system picker.

## 8. Feature flags on the client

The client reads `PublicConfigDto` (`api.md` §11) at start and on every foreground. Flagged-off
features render disabled with a hint (guidelines 4.5): sharing tab, public-link buttons, open
sign-up. The flag list with owners and removal dates lives in `docs/flags.md` (written with the
deployment doc).
