# App shell, navigation and look

How the Android app is put together around the screens: startup, routing, navigation, theme and the
shared UI pieces that make it behave like a Material 3 Android app (Mihon is the reference and the
source of much of the code). Code: `:app` (`KodakikoApp`, `MainActivity`, `AppStartup`, `di/`
(Metro dependency graph), `navigation/`, `system/`, `log/`), `:ui:core` (theme, components,
settings widgets, `Route`, strings, test tags), `:platform:device` (haptics, secure screen, system
bars).

## Startup and routing

- `KodakikoApp` builds the app graph once and installs the crash recorder
  ([settings.md](settings.md#debug-log)). `AppStartup` then wipes the plaintext temp directory
  (files a killed process may have left), adopts a v0.3.1 install on the first start after the
  update ([lock.md](lock.md#updating-from-v031)) and restores the session.
- `MainActivity` (a single activity) applies the stored theme at the root: `KodakikoTheme` with the
  stored light/dark choice, color preset, pure black and dynamic color, around a `Surface` in the
  theme's background and text color, so every screen, sheet and dialog follows it (v0.4.1; v0.4.0
  ignored the stored theme).
- The account state (`NoServer`, `SignedOut`, `PendingDeletion`, `Locked`, `Unlocked`) decides where
  the app goes: Welcome, Cancel deletion, Lock, or the start tab (**Start screen**: Gallery or
  Calendar). Every route except Welcome, the sign-up/sign-in screens and `/local-setup` needs an
  unlocked account; otherwise the app redirects by state (`RouteGuards`). On lock the current route
  is remembered and restored after unlock ([lock.md](lock.md)).
- Routes keep the v0.3.1 paths (`/entry/<id>`, `/settings/advanced`, …) as `Route` keys, so lock
  and restore work the same way. `/settings/diagnostics` (debug builds) and `/settings/debug-log`
  (v0.4.1) are routes too.
- A repeated push of the **same** route within 600 ms is dropped, so tapping a row many times opens
  it once; going back resets the guard.

## Navigation

- **Bottom navigation:** Gallery, Calendar, Shared, More (icons with labels). It hides while
  scrolling down when **Hide bars on scroll** is on. The Shared tab is hidden when the server has
  sharing off and in local-only mode.
- **Stack** (Voyager, as in Mihon): pushed screens slide in from the right with a short fade
  (300 ms); the capture screen slides up from the bottom; the video player fades; switching tabs
  cross-fades.
- **Overlay routes** (v0.4.1): the Share sheet is a route drawn over the screen it was opened from,
  which stays visible behind it ([sharing.md](sharing.md#the-share-sheet)).
- **Back:** every screen has an app-bar back arrow, and the system Back follows the stack. There is
  no predictive-back preview animation (v0.3.1 had one; Voyager does not support it, an accepted
  change in v0.4.0). Screens with something to lose ask first: the edit page (Save / Discard /
  Keep editing, [edit-page.md](edit-page.md#saving-done-back-and-discard-changed-in-v041)) and the
  capture screen with items in its tray.
- **App bar** (Mihon's): back arrow, title and subtitle, up to three icon actions, then an
  overflow menu (items may carry icons; destructive ones in the error color); a search mode (the
  bar becomes a text field) and a selection mode (count, select all, select inverse).
- Every screen change is written to the [debug log](settings.md#debug-log) as a screen name with
  ids only (never dates or paths) when the log is on.

## Look

- **Material 3** in Jetpack Compose with Material icons, adapted from Mihon's components (app bar,
  scaffold, settings widgets, tabs, sheets, dialogs, snackbars). Typography and shapes are the
  Material 3 defaults.
- **Theme** (Settings › Appearance, [settings.md](settings.md#theme)): Light, System or Dark; 14
  color presets (Kodakiko's own default plus 13 from Mihon); **Dynamic** colors from the wallpaper
  (Android 12+); **Pure black** for dark mode on OLED screens. Dark mode fills every Material color
  role so text stays readable on every page (v0.4.1).
- **Edge to edge**, with transparent system bars whose icons follow the theme. **Full screen**
  (default on) hides both system bars (swipe from an edge to show them); the content then keeps at
  least 28 dp at the top and 20 dp at the bottom free so nothing sits under the camera cutout or the
  gesture bar.
- **Secure screen** (default on) sets `FLAG_SECURE`, so screenshots and the recent-apps preview are
  blank; the lock screen always has it ([lock.md](lock.md#privacy-on-screen)).
- The system font size is respected everywhere.
- Every UI string and number is formatted as American English (en-US) whatever the phone's
  language, like v0.3.1.

## Shared UI behavior

| Piece | Behavior |
|---|---|
| Touch feedback | Material ripples on every tappable surface |
| Sheets (`AdaptiveSheet`) | Bottom sheets slide up and close when dragged down past 30 % of their height, on a fling faster than 1000 dp/s, on a tap outside, or by their own buttons; a short drag springs back. On wide screens they show as dialogs |
| Snackbar | Short messages at the bottom ("Saved.", "Moved to trash." with Undo, bulk results), 4 s each; a new one replaces the one shown. Lasting warnings and errors stay as banners in the screen |
| Haptics | Ticks on long-press select, switches, the shutter, recording start and stop, and pull to refresh, through the view's haptic feedback, so the system's touch-feedback setting applies; off with **Haptic feedback** |
| Pull to refresh | On every entry grid, the calendar and the Shared tab: runs a sync and shows the spinner until it settles; not in local-only mode or while sync is paused |
| Preferences | Settings screens are declared as data and rendered by one list ([settings.md](settings.md)) |
| Clipboard | Copies of a link or a note are marked sensitive, so Android's clipboard preview hides them |

## Strings and ids

All UI text is in Android string resources in `:ui:core`: `strings.xml` is generated from the
v0.3.1 text by `tests/fixtures/ui-strings/emit-android.ts` (do not edit it by hand), and newer text
lives in `strings_v040.xml` and `strings_v041_edit.xml`. Test ids are in `TestTags.kt` and are
exposed as resource ids, so UI tests (Compose tests and Maestro) find elements by id only.

## Checked only on a phone

Ripples, transitions, haptics, dynamic color, full screen and the system-bar insets, and the share
sheet drawn over the screen below.
