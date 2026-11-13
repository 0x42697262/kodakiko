# 22 — Material 3 UI modeled on Mihon (D-84)

Status: draft 1 (2026-11-14, Opus). Depends on all client specs (07a-20a). Replaces how screens
look and are navigated; it does not change services, storage, crypto, sync or routes' meaning.

Goal: the whole client looks and behaves like **Mihon** (`mihonapp/mihon`, Apache-2.0, Kotlin +
Jetpack Compose, Material 3): top app bars with icon actions, a bottom navigation bar with icons, a
library grid with category tabs, a tabbed **Filter / Sort / Display** sheet, a **More** tab, and a
settings tree built from a small set of preference widgets with many options. No text-only buttons
for actions that have a standard icon. Mihon's code cannot be reused (Kotlin); its **structure,
patterns and labels** are copied. Reference files (read 2026-11-14, `main`):
`presentation/library/LibrarySettingsDialog.kt`, `presentation/library/components/LibraryToolbar.kt`,
`presentation/components/AppBar.kt`, `presentation/more/MoreScreen.kt`,
`presentation/more/settings/screen/Settings*.kt`, `presentation/more/settings/widget/*.kt`,
`tachiyomi/ui/home/HomeScreen.kt`.

Read first: client.md (theme, navigation), specs 10-20a (the screens being reworked), 07c (test ids).

## 0. Decisions

| Point | Decision |
|---|---|
| Component library | `react-native-paper` 5.15.4 (MIT, Material 3, works on Android and web, pure JS) |
| Icons | `@expo/vector-icons` 15.1.1 (MIT), family **MaterialIcons** (Google Material Icons, the closest match to Mihon's Material Symbols Rounded in that package). One family only. Paper is configured to render its icons with the same family |
| Theme | Paper `MD3DarkTheme` / `MD3LightTheme` extended with our colors; `ui/theme.ts` becomes a thin adapter so existing `useTheme().colors` callers keep working during the migration. A new device setting `appTheme` = `system` \| `light` \| `dark` (default `system`) |
| Material You (wallpaper colors) | **Not in this spec** (needs a native package); listed as a follow-up |
| Test ids | Every existing `testID` stays on the element that does the same job (07c flows keep working). Icon buttons get `accessibilityLabel` from the text catalog (the label the old text button showed) |
| Text catalog | Labels stay in `src/text/en.ts`; new labels follow Mihon's wording where the concept is the same ("Filter", "Sort", "Display", "Select all", "Select inverse", "Display mode", "Items per row") |
| Web | Same components; the bottom bar becomes a **navigation rail** at width ≥ 840 dp (Mihon does the same on tablets) |

## 1. Shell

### 1.1 Bottom navigation (`app/(tabs)/_layout.tsx`)

Tabs, in order, each with an icon + label (Paper `BottomNavigation.Bar` rendered as the expo-router
`tabBar`):

| Tab | Icon (MaterialIcons) | Mihon counterpart |
|---|---|---|
| Gallery | `photo-library` | Library |
| Calendar | `calendar-month` | Updates |
| Shared | `people` | Browse |
| More | `more-horiz` | More |

The Settings gear in the gallery top bar (spec 20) moves into **More**. Test ids `tabs.gallery`,
`tabs.calendar`, `tabs.shared` stay; new `tabs.more`.

### 1.2 System bars ("full screen")

- Edge to edge on Android: content draws behind the status and navigation bars; the bottom bar and
  sheets pad by the safe-area insets.
- The navigation-bar contrast scrim is turned off (`android:enforceNavigationBarContrast=false` via
  a config plugin `plugins/with-system-bars.js`), so there is no grey strip under the bottom bar.
- Status bar icons follow the theme (light icons on dark).

### 1.3 Top app bar (`ui/AppBar.tsx`, Mihon `AppBar.kt`)

- Normal mode: optional back arrow (`arrow-back`, test id `<screen>.back`, replaces every "Back"
  button, including `settings.back`), title (+ optional subtitle), up to 3 icon actions, then an
  overflow menu (`more-vert`) for the rest.
- Search mode (Mihon `SearchToolbar`): the search icon turns the bar into a text field with a
  close (`close`) icon; typing filters live.
- Action mode (selection): close icon, the selected count as the title, `select-all` and
  `flip-to-back` (select inverse) actions; Mihon's bottom action bar holds the bulk actions.

## 2. Gallery (Mihon Library)

### 2.1 Toolbar

Title "Gallery" with the item count as a pill (Mihon "Show number of items"). Actions: `search`
(search mode, replaces the text filter field), `filter-list` (opens §2.3; tinted with the primary
color while any filter is active, Mihon `LibraryToolbar`), overflow: "Select all", "Open random
entry", "Trash" (moves the trash link out of the old More sheet), "Lock now".

### 2.2 Category tabs = tags

- A scrollable tab row under the toolbar (Mihon `LibraryTabs`): **All** first, then one tab per
  tag in alphabetical order, each with its count when "Show number of items" is on. Selecting a tag
  tab sets `GalleryFilter.tags = [tag]`; All clears it. Swiping the grid left/right moves between
  tabs (Mihon `LibraryPager`).
- Hidden when the user has no tags or when Display › "Show tag tabs" is off.
- Multi-tag filtering stays available in the Filter tab (§2.3).

### 2.3 Filter / Sort / Display sheet (Mihon `LibrarySettingsDialog`)

A bottom sheet with three tabs.

**Filter** — tri-state rows (off → include ✓ → exclude ✗, Mihon `TriStateItem`):
- Has video (`hasVideo`), Has note (`notePreview !== ''`), Multiple photos (`mediaCount > 1`),
  Shared (`collectionIds` has a shared collection), Waiting to upload (`pending`), Kept offline
  (pinned, from the originals service).
- Date range (the existing from/to fields, spec 11) as a row that opens a date-range dialog.
- Tags: a checkbox list (any of the checked tags) for multi-tag filtering.

**Sort** — single choice with direction arrow (Mihon sort rows): Date taken (default, newest first),
Note length, Number of photos, Random (re-shuffles each time it is chosen).

**Display**:
- Display mode (radio): Comfortable grid (default; tile + caption line: time and note preview),
  Compact grid (caption over the image, Mihon "Compact grid"), Photo-only grid, List (thumbnail +
  note preview + tags).
- Items per row: slider 0 (Auto) … 10, separately for portrait and landscape.
- Group by day: switch (default on; off = one continuous grid).
- Badges (switches): Video, Photo count, Note, Waiting to upload, Kept offline.
- Tabs: "Show tag tabs", "Show number of items".

All of these are device settings (§6) and survive restarts.

### 2.4 Grid items

`entry-grid.tsx` renders the four display modes with badges in the top-left corner (Mihon
`LibraryBadges`: small rounded pills). Long press enters action mode (§1.3); the bottom action bar
shows icon buttons with labels: Tags (`label`), Share (`share`), Pin (`offline-pin`), Trash
(`delete`).

### 2.5 Capture button

A Material **extended FAB** "New" with `add-a-photo` (collapses to an icon while scrolling),
bottom right above the bottom bar (test id `gallery.capture`).

## 3. Other tabs and screens

| Screen | Change |
|---|---|
| Calendar | App bar with `chevron-left`/`chevron-right` month arrows, `today` action, overflow "Jump to…"; day cells keep their layout |
| Shared | App bar; list items with an avatar letter and recipient count; FAB "New journal" (`add`) |
| Entry viewer | Immersive: bars hidden on tap (Mihon reader), app bar overlay with back, `edit`, `share`, overflow (Pin, Trash, Export); page indicator as a pill |
| Capture | Full-screen preview; top row: close (`close`), flash (`flash-auto/on/off`); bottom row: gallery import (`photo-library`), shutter, flip (`flip-camera-android`); tray strip above; "Next" as an `arrow-forward` FAB |
| Compose / edit | App bar with back and a `check` save action; tags as Paper chips with close icons |
| Video player | Immersive like the viewer |
| Lock, sign-in, sign-up, welcome, recovery | Paper text fields (outlined), filled buttons, centered logo header (Mihon `LogoHeader`) |
| Trash | App bar + action mode with Restore (`restore-from-trash`) and Delete forever (`delete-forever`) |
| Links list, Admin, Collection | App bars, list items, FABs where something is created |

## 4. More tab (Mihon `MoreScreen`)

Top: logo header. Then:
1. Switch **Hide shared journals** (filters other people's entries out of the gallery; Mihon
   "Downloaded only" pattern).
2. **Lock now** (`lock`).
3. Divider.
4. **Trash** (`delete`) with the count.
5. **Tags** (`label`): rename/delete tags across entries (new screen; Mihon "Categories").
6. **Statistics** (`query-stats`): entries, photos, videos, storage used, entries per month (new
   screen; reads the local index only).
7. **Data and storage** (`storage`): opens §5 Data and storage.
8. **Settings** (`settings`).
9. **About** (`info`).

## 5. Settings (Mihon `SettingsMainScreen` + widgets)

### 5.1 Widgets (`ui/preferences/`)

Mirroring Mihon `presentation/more/settings/widget/`: `PreferenceGroupHeader`,
`TextPreference` (title, subtitle, icon, onPress), `SwitchPreference`, `ListPreference` (shows the
current value as subtitle, opens a radio dialog), `MultiSelectListPreference`,
`SliderPreference`, `EditTextPreference`, `InfoPreference`. A screen is declared as data
(Mihon `Preference.kt`): `{ title, items: PreferenceItem[] }`, rendered by one
`PreferenceScreen` component. That also gives **Settings search** (Mihon `SearchableSettings`):
the search action lists matching items across all screens.

### 5.2 Main list

| Row (icon) | Summary line | Contents |
|---|---|---|
| Appearance (`palette`) | Theme, date and time format | Theme (System/Light/Dark), Date format (System, YYYY-MM-DD, DD/MM/YYYY, MM/DD/YYYY), Relative dates ("Today", "Yesterday") switch, Start screen (Gallery/Calendar) |
| Gallery (`photo-library`) | Display, tags, on this day | everything in §2.3 Display as preferences, "On this day" card switch, Confirm before moving to trash switch |
| Capture (`photo-camera`) | Location, camera | Add location (spec 20), Start with the back/front camera, Keep the screen on while capturing, Save a copy to the phone's gallery: **no** (privacy; info row explaining why) |
| Sync (`sync`) | Last synced, Wi-Fi rule | spec 20 Sync + Storage upload rules |
| Data and storage (`storage`) | Cache, export | cache cap, usage, clear originals, Export journal, persistence (web) |
| Security and privacy (`security`) | App lock, secure screen | auto-lock as a ListPreference, biometric, keep unlocked (web), encrypt thumbnails, **Secure screen** (blocks screenshots and hides the app in recents; Android `FLAG_SECURE`, needs `expo-screen-capture` — decision OQ-2), Hide note previews in the gallery |
| Account (`account-circle`) | username, server | spec 20 Account / local mode |
| Public links (`link`) | — | spec 18 list (when enabled) |
| Admin (`admin-panel-settings`) | — | spec 19 (admins only) |
| About (`info`) | version | spec 20 About, open-source licenses |

## 6. Device settings additions

`DeviceSettingsValue` gains (schema version + 1, defaults in brackets): `appTheme` [`system`],
`dateFormat` [`system`], `relativeDates` [true], `startScreen` [`gallery`], `galleryDisplayMode`
[`comfortableGrid`], `galleryColumnsPortrait` [0 = auto], `galleryColumnsLandscape` [0],
`galleryGroupByDay` [true], `galleryBadges` [all on except `keptOffline`], `galleryShowTagTabs`
[true], `galleryShowCounts` [false], `gallerySort` [`{ key: 'takenAt', descending: true }`],
`galleryFilters` [all off], `hideShared` [false], `onThisDay` [true], `confirmTrash` [true],
`captureStartCamera` [`back`], `captureKeepScreenOn` [true], `hideNotePreviews` [false],
`secureScreen` [true]. Older stored settings parse with these defaults.

Core changes: `GalleryFilter` gains `triState` filters and `sort`; `query()` applies them (pure
functions, unit-tested in `packages/core`). Pinned/kept-offline filtering stays in the client.

## 7. Phases (each one commit, each leaves every test green)

1. Dependencies, theme adapter, `AppBar`, icon helper, bottom navigation with icons, system bars
   plugin, More tab with existing destinations; every "Back" button → app bar back.
2. Preference widgets + declarative screens; port spec 20 settings onto them; settings search.
3. Gallery: toolbar, search mode, tag tabs, Filter/Sort/Display sheet, display modes, badges,
   action mode, FAB; core filter/sort additions.
4. Capture, viewer and video immersive layouts; compose/edit app bars and chips.
5. Calendar, Shared, Trash, Links, Admin, auth screens; Tags screen; Statistics screen.
6. Screenshots regenerated (`tests/ui/screenshots`), README images updated.

## 8. Tests

- Core: tri-state filters and each sort key (`packages/core/test/index/*`).
- Client: device-settings migration (old value → new defaults); preference screen model (search
  finds items across screens; ListPreference shows the current value).
- UI (07c): existing flows keep passing with unchanged test ids (navigation helpers updated where
  a step changes, e.g. Settings is now under More). New flows: **U-24** Filter/Sort/Display —
  set "Has video" include + List mode + 3 per row, reload, the settings persist and only the video
  entry shows; **U-25** tag tabs — two tags seeded, the tab row shows All + both, selecting one shows
  only its entries, swiping returns to All.
- Manual (Android): no grey bar under the bottom navigation; capture preview fills the screen;
  dark mode has no black-on-dark text anywhere (the spec 07b recovery-label bug class).

## 8a. Implementation notes

- **Phase 1 (2026-11-14):**
  - Dependencies added; `pnpm licenses` still passes D-70.
  - `ui/icons.tsx` (semantic `ICONS` map; every name checked against the MaterialIcons glyph map),
    `ui/paper-theme.ts` (MD3 theme from `COLORS`), `ui/AppBar.tsx`. `PaperProvider` in the root
    layout with MaterialIcons as Paper's icon renderer.
  - Bottom bar: `features/navigation/material-tab-bar.tsx`, a Paper `BottomNavigation.Bar` passed
    as the expo-router `tabBar`. It uses expo-router 58's own tab-bar props (`navigateToTab`, no
    `navigation`). Shared is hidden in local mode and when sharing is off. Tab scenes use the theme
    background.
  - More tab `features/more/MoreScreen.tsx` (Lock now, Trash, Data and storage, Settings, About;
    Tags and Statistics come in phase 5). The gallery gear is gone; U-16, U-17, U-19, U-20 (web) and
    U-15, U-21, U-23 (Android) now go through `tabs.more` › `more.settings`.
  - `SettingsPage` uses `AppBar` (back keeps `settings.back`).
  - `plugins/with-system-bars.js` sets `android:enforceNavigationBarContrast=false` (unit-tested;
    checked on a device at the next APK build).
  - `test/tabs-layout.test.ts` mocks the new tab-bar module and hooks; assertions unchanged.

- **Phase 2 (2026-11-14):**
  - The `DeviceSettingsValue` fields of §6 are added **without** a schema version bump. Stored
    settings are parsed field by field, and missing fields take defaults, so a bump would only reset
    every user's settings (deviation from §6). Defaults: `constants/ui-settings-constants.ts`,
    spread into both platform defaults.
  - Widgets: `ui/preferences/` (`PreferenceList` with header, text, switch, choice (radio dialog)
    and info rows). Choice options have the test id `<testID>.<value>`, so U-15 opens
    `settings.autoLock` and then taps `settings.autoLock.1`. Switch rows keep `role="switch"` and
    `aria-checked` (U-16). No slider widget: items per row is a choice 0 (Auto)–10.
  - Settings main list with icons, summaries and search (`settings-search.ts`, unit-tested).
    New screens Appearance, Gallery and Capture; Security, Data and storage (now holds Export,
    so U-20 opens `settings.row.storage` first) and the main list are ported to preferences.
    Add-location moved to Capture.
  - Theme choice: module store in `ui/theme.ts` set by `SettingsBinders`. Secure screen via
    `expo-screen-capture` on Android: on by default, and on before the settings load (lock
    screen).
  - Not yet applied to the screens they affect (phase 3+): date format, relative dates, start
    screen, gallery display settings, hide shared, hide note previews, camera side, keep screen on.

## 9. Answered questions (programmer, 2026-11-14)

| ID | Question | Answer |
|---|---|---|
| OQ-1 | Bottom tabs Gallery, Calendar, Shared, More (Shared hidden in local mode and when sharing is off) | Yes |
| OQ-2 | Secure screen via `expo-screen-capture` | Yes, configurable, **on by default** (`secureScreen` default `true`, §6) |
| OQ-3 | Material You colors | Later |
| OQ-4 | Tags management and Statistics screens | Yes (phase 5) |

## 10. Verification checklist

- [ ] `pnpm verify`, `pnpm ui:web`, e2e green after each phase.
- [ ] Android: every screen of §3 checked in dark and light themes; no text-only action buttons
      left where §1-§5 name an icon.
- [ ] Screenshots regenerated.
