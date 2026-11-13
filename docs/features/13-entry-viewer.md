# 13 — Entry viewer, markdown note, edit screen, add media to an entry

Status: draft 1 (2026-11-11).

Goal: opening an entry shows its photos and videos in a swipeable pager, the time, location, tags
and the note rendered from markdown; the owner can edit everything on one screen (with a markdown
preview toggle), add more media through the capture screen, and trash or delete it. Originals come
from local data in this spec; spec 14 adds downloading behind the same port; spec 15 adds video
playback.

Read first: `docs/design/client.md` §§1-2; `docs/design/media.md` §7; specs 09 (tray, capture),
10 (entries service, editors, tag input, location row, formatting), 11 (index hooks, thumbnails);
decisions D-18, D-39, D-51, D-59, D-60. Depends on 10 (and 11 for hooks).

## 1. Markdown (`src/features/markdown/`)

Add `marked` (allowlist, D-51) to `apps/client`.

### 1.1 `parse.ts` (pure, Node-testable)

```ts
export type Inline =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'strong' | 'em' | 'del'; readonly children: readonly Inline[] }
  | { readonly type: 'code'; readonly text: string }
  | { readonly type: 'link'; readonly href: string | null; readonly children: readonly Inline[] }
  | { readonly type: 'break' };
export type Block =
  | { readonly type: 'heading'; readonly level: 1 | 2 | 3; readonly children: readonly Inline[] }
  | { readonly type: 'paragraph'; readonly children: readonly Inline[] }
  | { readonly type: 'code'; readonly text: string }
  | { readonly type: 'quote'; readonly blocks: readonly Block[] }
  | { readonly type: 'list'; readonly ordered: boolean; readonly start: number; readonly items: readonly ListItem[] }
  | { readonly type: 'rule' };
export type ListItem = { readonly checked: boolean | null; readonly blocks: readonly Block[] };
export function parseMarkdown(source: string): readonly Block[];
```

- `marked.lexer(source, { gfm: true, breaks: true })` — **only** the lexer; never `marked.parse`,
  never any HTML output (D-51).
- Token mapping: `heading` (depth > 3 → 3), `paragraph`, `text` (with nested `tokens` when
  present), `strong`, `em`, `del`, `codespan` → inline `code`, `code` → block `code`,
  `blockquote` → `quote`, `list`/`list_item` (`task: true` → `checked` from the item's `checked`;
  the item's leading `checkbox` inline token is skipped; `start` is `""` for unordered lists, so
  `start = ordered ? Number(token.start) : 1`), `hr` → `rule`, `br` → `break`, `space` → dropped,
  `escape` → `text`. Token `text` is used as is: marked does not HTML-escape it (F-LIB-24), and
  entities such as `&amp;` are shown literally, as typed.
- `link`: `href` kept only when its scheme (lowercased, after trimming) is `http:`, `https:` or
  `mailto:` (D-60); otherwise `href: null` (rendered as plain text).
- `image` → inline `text` with the alt text (or `[image]` from the text catalog when empty); never
  a URL load (D-60).
- `html` (block, and inline inside paragraph `tokens`), `table`, `def` and any unknown token → a
  `paragraph` (block) or inline `text` (inline) with the token's `raw` text verbatim.
- Nesting deeper than `MARKDOWN_MAX_DEPTH = 6` (quotes/lists) → the deeper content becomes plain
  text of its `raw`.
- Inputs above `NOTE_MAX_CHARS` cannot exist (schema), so no size guard beyond that.

### 1.2 `markdown-view.tsx`

Renders `Block[]` with React Native `Text`/`View` only: headings with `FONT_SIZE.title` /
`headline` / `body` bold; paragraphs; code in a monospace font (`Platform`-free: the theme defines
`FONT_FAMILY_MONO = 'monospace'`, which React Native maps on Android and react-native-web maps in
the browser) on a `surface` background; quotes with a left border; lists with "•" or "n." markers
and nested indentation `SPACING.lg` per level; task items with "☐"/"☑" (read-only); rules as a
1-px `border` line. Links: `primary` color, underlined, `accessibilityRole="link"`; press → sheet
"Open this link?" with the full URL as selectable text, buttons "Open" (`Linking.openURL`) and
"Cancel" (D-60). `parseMarkdown` is memoized per source string (`useMemo`).

## 2. Original source port (local-only here)

`src/services/originals/original-source.ts`:

```ts
export type OriginalRef = { readonly uri: string; readonly mimeType: string; readonly release: () => Promise<void> };
export interface OriginalSource {
  readonly open: (entryId: string, mediaId: string) => Promise<OriginalRef | 'missing'>;
  readonly clearAll: () => Promise<void>;
}
```

`createLocalOriginalSource(…)` for **photos** (videos are spec 15):
- Original object present locally → decrypt with the original variant key (`createStreamDecryptor`,
  `plaintextSize` from the object row) chunk by chunk:
  - Android: `TempFiles.create('view', ext)` (spec 09 §4.1; ext from the MIME type: `image/jpeg` →
    `jpg`, `image/png` → `png`, `image/webp` → `webp`, `image/heic` → `heic`, `image/heif` → `heif`,
    a frozen map in `keys/`), write the plaintext, return its `file://` URI; `release` deletes it.
  - Web: collect chunks into a `Blob` with the MIME type → `URL.createObjectURL`; `release` revokes.
- Not present → `'missing'` (spec 14 downloads).
- `clearAll` (on lock and sign-out) releases every open ref and deletes `tmp/view-*`.
- Updates the object's `lastAccessAt` (used by spec 14's eviction).

## 3. Screens

### 3.1 `/entry/[id]` (viewer)

Replaces spec 11's placeholder. Data: `useIndexedEntry(id)` for summary, `entriesService.readMetadata(id)`
for full metadata (media list, note, location).

- **Media pager**: a horizontal `FlatList` with `pagingEnabled`, one page per media in metadata
  order, page width = screen width, height = width × 4/3 capped at 70% of the screen height; each
  page shows the thumbnail immediately (`contentFit="contain"`) and swaps to the original when
  `OriginalSource.open` resolves (photo). `'missing'` keeps the thumbnail and shows a small
  "Showing preview" label (spec 14 replaces this with a download). Video pages show the poster with
  a play button that calls the `VideoLauncher` port (§3.4). A page indicator "2 / 5" under the
  pager. Refs are released when a page leaves a window of ±1 around the visible page.
- Web keyboard: ←/→ change page, Esc → back.
- Below the pager: `formatEntryTime` line; location row (spec 10) when present; tag chips (tap →
  gallery with that tag as the only filter); `MarkdownView` of the note (nothing when empty).
- Header actions (owner, not trashed): "Edit" → `/entry/[id]/edit`; overflow: "Add Photos or
  Videos" → `/capture?entryId=<id>`; "Move to Trash" (→ back to the gallery with `Banner` "Moved to
  trash." and an "Undo" button calling `restore`); "Share…" opens spec 17's `ShareEntriesSheet` for this entry; enabled when **either** `sharingEnabled`
  or `publicLinksEnabled` is true, else disabled with the hint "Sharing is turned off on this
  server." (until spec 17 is built the menu item is disabled with that hint).
- Trashed entry (owner): banner "This entry is in the trash." with "Restore" and "Delete
  Permanently…" (spec 10 confirmation); no Edit.
- Viewer role (shared with me, D-39): no edit, trash or delete actions; header shows "Shared by
  (username)" when known (spec 17 fills the name).
- Unreadable entry: message "This entry can't be opened. It may be damaged." and, for the owner,
  "Delete Permanently…".
- Unknown id (deleted meanwhile) → `/` with `Banner` "That entry no longer exists."

### 3.2 `/entry/[id]/edit`

Owner only (else redirect to the viewer). One form with local draft state:
- Media strip: thumbnails in order with "Move Left"/"Move Right"/"Remove" for the selected item;
  "Remove" disabled when only one media remains (hint "An entry needs at least one photo or video.").
- When (time editor), Where (location row with "Remove Location"), Tags (tag input with index
  suggestions), Note: a multi-line field with a segmented control "Write" / "Preview" (D-18);
  Preview renders `MarkdownView` of the draft.
- "Save": first, for each removed media (in draft order), `entriesService.removeMedia` (sequential;
  each re-seals the metadata without that media); then one `EntryEdit` with every other changed
  field (`note`, `tags`, `time`, `location`, `mediaOrder` over the **remaining** media ids, a
  permutation of the current media after the removals) → `entriesService.edit`; then back to the
  viewer. Nothing changed → just back.
- Leaving with changes (back button, Esc, hardware back) → sheet "Discard your changes?" with
  "Discard" and "Keep Editing".

### 3.3 Add media to an existing entry

`/capture?entryId=<id>` (spec 09 screen, new mode): the tray starts empty; the limit is
`maxMediaPerEntry − current media count`; if the tray still holds items from an unfinished new
entry, the sheet "Discard (n) items in progress?" ("Discard" / "Cancel") appears first; "Next" is labeled "Add to Entry" and calls
`entriesService.addMedia(entryId, tray.items)` then `captureTray.clear(false)` and returns to the
viewer. Closing discards the tray as usual. The entry's owner check happens before the screen
opens.

### 3.3a Zoomable photo (D-59)

Add `react-native-gesture-handler`, `react-native-reanimated`, `react-native-worklets` (allowlist) to
`apps/client`. No Babel change: `babel-preset-expo` adds the worklets plugin itself (F-LIB-27).
`app/_layout.tsx` wraps everything in `GestureHandlerRootView style={{ flex: 1 }}` (07a's layout
gains this wrapper).

`src/features/media/zoomable-photo.tsx`: props `{ uri, width, height, onZoomChange(zoomed: boolean) }`.
- Shared values (reanimated): `scale`, `savedScale`, `translateX/Y`, `savedTranslateX/Y`.
- Gestures (gesture-handler `Gesture.Simultaneous(pinch, pan)` raced with `doubleTap`):
  - pinch: `scale = clamp(savedScale × event.scale, ZOOM_MIN = 1, ZOOM_MAX = 5)`, zooming around the
    focal point (translate adjusted by `(focal − center) × (1 − scaleRatio)`);
  - pan (`minPointers(1)`, enabled only while `scale > 1`): translate, clamped so the image edge
    never moves inside the viewport (`maxTranslate = (displayed size × scale − viewport size) / 2`
    per axis, 0 when smaller);
  - double tap: `scale === 1` → animate to `ZOOM_DOUBLE_TAP = 2.5` around the tap point; else animate
    back to 1 and translate 0 (`withTiming`, `ZOOM_ANIMATION_MS = 200`);
  - on end: snap `scale < 1.05` back to 1.
- `onZoomChange(scale > 1)` is called through `runOnJS` when crossing 1; the pager sets
  `scrollEnabled={!zoomed}` so horizontal swipes pan the zoomed photo instead of changing pages;
  changing page resets zoom.
- Web: the same component (gesture-handler supports web); trackpad pinch arrives as pinch; mouse:
  double-click zooms, drag pans while zoomed; Ctrl/Cmd + wheel zooms around the cursor
  (`Gesture.Native` is not needed; a `wheel` listener on the container via `onWheel` in the web file
  `zoomable-photo.web-wheel.ts`, applied only on web through a platform file).
- Accessibility: `accessibilityHint` "Double-tap to zoom"; `accessibilityLabel` is
  `viewer.photoLabel` "(n) => `Photo ${n}`" when not zoomed and `viewer.photoZoomedLabel` "(n) =>
  `Photo ${n}, zoomed`" when zoomed (n = 1-based page); `testID` `viewer.zoomable.<n−1>` (spec 07c §2;
  UI flow U-12 asserts the label).
- Constants in `src/features/media/constants.ts`.

Used for every photo page in §3.1 and by the public-link viewer (spec 18 §2.5). Videos are not
zoomable.

Tests: the clamp and focal-point math extracted into `zoom-math.ts` (pure) with a table (scale
limits, translate clamp for portrait/landscape images, focal zoom keeps the focal point fixed);
manual: pinch, pan at the edges, double-tap in/out, swipe to the next photo only when not zoomed,
web trackpad and double-click.

### 3.4 `VideoLauncher` port

`src/services/video/video-launcher.ts`: `{ canPlay(): boolean; open(entryId, mediaId): void }`.
This spec registers a placeholder implementation with `canPlay() = false`; the play button is then
disabled with the hint "Video playback arrives in spec 15" **only in development builds**; spec 15
must replace it before any release (the release checklist in spec 21 verifies `canPlay()` is
`true`).

## 4. Tests

1. `parseMarkdown` table (Node): every supported token; nested list with a task item; link with
   `javascript:` → `href: null`; `<script>` HTML → plain text paragraph containing the raw text;
   image → alt text; heading depth 5 → level 3; depth > 6 quotes → plain text; `breaks: true`
   single newline → `break`.
2. Original source (Node, memory FileStore, wrappers Sodium): decrypts to the original bytes
   (web path with `Blob`); `release` revokes; `clearAll` releases everything; missing → `'missing'`.
3. Edit form logic extracted into `src/features/entries/edit-draft.ts` (pure): diff of draft vs
   metadata → `{ removedMediaIds, edit: EntryEdit }` where `edit.mediaOrder` (only when the order
   changed) lists the remaining ids; tested with combinations, including "remove one and reorder
   the rest", whose `edit` must pass `applyEntryEdit` on the metadata after the removal.

## 4a. Implementation notes (2026-11-12, Opus)

- Plaintext for viewing goes through a platform port `PlaintextSink`
  (`src/adapters/plaintext-sink/`): web collects a `Blob` and returns an object URL; Android writes a
  `TempFiles.create('view', ext)` file. `createLocalOriginalSource` takes it as a dependency, plus
  `readMetadata` for the MIME type; MIME → extension map in `keys/mime-extensions.ts`.
- Gallery notices ("That entry no longer exists.", "Moved to trash." + Undo) use a
  `services.galleryNotice` observable shown by `GalleryNoticeBanner` in the gallery.
- `/entry/[id]` became `app/entry/[id]/index.tsx` (with `app/entry/[id]/edit.tsx` beside it).
- Capture add mode: `/capture?entryId=…` → `CaptureScreen addToEntry`; the import hook takes an
  optional limit override (`maxMediaPerEntry − current count`).
- U-12 needs spec 14 (entries from another device have no local thumbnail or original before
  downloads exist); its flow files are parked in `tests/ui/pending/` and spec 14 moves them back
  (07c §8 updated).
- The flaky 07b test "recovery decodes the words locally" now breaks the checksum
  deterministically (flip the last word's lowest bit) instead of swapping two words (≈1/256 of
  swaps kept a valid checksum).

- Fix (2026-11-13): the viewer screen scrolls (`Screen scroll`), so the note is reachable on
  phones.
- Fix (2026-11-13, spec 10 code): saving a new entry used `router.dismissTo('/')`, which pushed a
  second gallery and left the capture modal mounted under it. It now dismisses the stack and
  replaces it with `/` only when the stack's first screen is not the tabs. U-06 asserts one
  gallery screen and no capture screen after saving.

## 5. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Android and web: open an entry with 3 photos → swipe through, originals replace thumbnails;
      note with headings, a list, a task list, bold/italic, a code block and a link renders; the
      link asks before opening; a `javascript:` link is plain text.
- [ ] Edit: change note (preview toggle works), add/remove tags, change time, remove location,
      reorder and remove a photo → Save → viewer and gallery reflect it; another device after sync
      too.
- [ ] Add 2 photos to an existing entry via the capture screen → they appear at the end.
- [ ] Zoom (D-59): pinch to 5× and back, double-tap in and out, pan stops at the photo edges,
      swiping changes photo only when not zoomed; same on web with trackpad pinch and double-click.
- [ ] Trash from the viewer → Undo restores it.
- [ ] After leaving the viewer and on lock, `tmp/` contains no `view-*` files (diagnostics "List
      temp files").
- [ ] A note containing `![x](https://example.com/a.png)` causes no network request to
      example.com (web devtools Network tab; Android: server-independent, check with a proxy or
      skip and mark UNVERIFIED).
