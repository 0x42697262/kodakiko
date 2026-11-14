# App shell, navigation and look

How the client is put together around the screens: routing, navigation, theme and the shared UI
pieces that make it behave like a Material 3 Android app (Mihon is the reference). Code:
`apps/client/app/` (routes), `src/composition/`, `src/navigation/`, `src/ui/`,
`src/features/navigation/`, `plugins/with-system-bars.js`.

## Startup and routing

- `app/_layout.tsx` wraps everything in the gesture root, safe-area and Paper providers. Public link
  pages (`/s/…`) get a minimal viewer context; every other route gets the full app context, built
  once by `createAppContext()` (platform adapters, services, lock steps).
- The account state (`noServer`, `signedOut`, `pendingDeletion`, `locked`, `unlocked`) decides where
  `/` goes (`route-by-state.ts`); protected routes redirect when the state does not allow them.
  On lock the current route is remembered and restored after unlock ([lock.md](lock.md)).
- `useRouter` (`src/navigation/use-router.ts`) wraps expo-router's: a repeated push or navigate to
  the **same** page within 600 ms is dropped, so tapping a row many times opens it once. Every
  screen uses this wrapper.

## Navigation

- **Bottom navigation:** Gallery, Calendar, Shared, More (icons with labels, Paper's bottom bar as
  the tab bar). It hides while scrolling down when **Hide bars on scroll** is on.
- **Stack:** pushed screens slide in from the right on Android; the capture screen slides up from
  the bottom; the video player fades. The web uses no transition.
- **Back:** every screen has an app-bar back arrow; Android's predictive back gesture is on
  (`predictiveBackGestureEnabled`), and the hardware back follows the stack.
- **App bar** (`ui/AppBar.tsx`): back arrow, title and subtitle, up to three icon actions, then an
  overflow menu; a search mode (the bar becomes a text field) and a selection mode (count, select
  all, select inverse).

## Look

- **Material 3** through `react-native-paper` and Material icons. Text buttons, settings rows,
  switches, chips and dialogs are Paper components; Kodakiko's own colors feed Paper's theme.
- **Theme:** System, Light or Dark; **Pure black** for dark mode on OLED screens; **Dynamic color**
  (Android 12+) takes the light and dark colors from the wallpaper palette through
  `@pchmn/expo-material3-theme`, with pure black still applied on top.
- **Edge to edge** on Android, with status-bar icons following the theme and no grey navigation-bar
  scrim. **Full screen** (default on) hides both system bars (swipe from an edge to show them); the
  content then keeps at least 28 dp at the top and 20 dp at the bottom free so nothing sits under
  the camera cutout or the gesture bar (`ui/system-insets.ts`).
- The system font size is respected everywhere; ESLint forbids `allowFontScaling={false}`.

## Shared UI behavior

| Piece | Behavior |
|---|---|
| `Touchable` | Every tappable surface: an Android ripple in the text color at 12% (borderless for icon buttons), a slight dim while pressed on the web. ESLint forbids raw `Pressable` outside `src/ui/` |
| `Sheet` | Bottom sheets slide up, close by dragging the handle or header down past 30% of their height (or a quick fling), by tapping outside, or by their own buttons; a short drag springs back |
| Snackbar | Short messages at the bottom ("Saved.", "Moved to trash." with Undo, bulk results), 4 s, one at a time (`ui/snackbar-store.ts`, `SnackbarHost`). Lasting warnings and errors stay as banners in the screen |
| Haptics | `ui/haptics.ts`: ticks on long-press select, switches, the shutter, recording start and stop, and pull-to-refresh; Android only, through `performAndroidHapticsAsync` so the system's touch-feedback switch applies; off with **Haptic feedback** |
| Pull to refresh | On every entry grid, the calendar and the Shared tab: runs a sync and shows the spinner until it settles; not in local-only mode or while sync is paused |
| Preferences | Settings screens are declared as data and rendered by one list ([settings.md](settings.md)) |

## Strings and ids

All UI text is in `src/text/en.ts` (American English), test ids in `src/keys/`, numbers and
tuning in `src/constants/`. UI tests find elements only by test id.

## Checked only on a phone

Ripples, transitions, predictive back, haptics, dynamic color, full screen and the system-bar
insets.
