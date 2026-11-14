# 24 — Native feel pass (v0.2.2)

Status: draft 1 (2026-11-14, Opus). Source plan: `docs/planning/v0.2.2-native-feel.md` (D-102,
D-103). Base: `main` at `d47e550` (v0.2.1). Branch `feat/v0.2.2-native-feel`, one commit per phase.

Goal: on Android every tap, scroll, transition, sheet and message behaves like a Material 3 app
(Mihon, D-84). Web keeps working from the same code and looks Material too (D-103 Q5). Not a
redesign: layouts, screens, test ids and flows stay; existing UI tests stay unchanged (rule 9).

## 0. Decisions

| Topic | Decision |
|---|---|
| Touch feedback | One shared `ui/Touchable.tsx` over `Pressable`: Android ripple (`android_ripple`, theme ripple color, `borderless` for icon buttons), web pressed overlay through the style callback. Every raw `Pressable` outside `src/ui/` moves to it. ESLint `no-restricted-imports` (`react-native` › `Pressable`) fails `pnpm verify` outside `src/ui/` |
| Buttons | `ui/Button` renders Paper `Button` (contained = primary, outlined = secondary, contained with the error color = danger, `loading` = busy). Props and test ids unchanged |
| Settings rows | `settings-ui` `SettingsRow` / `SwitchRow` / `ChoiceRow` render Paper `List.Item` + Paper `Switch` / `RadioButton` with the touchable ripple; props and test ids unchanged |
| Transient messages | Paper `Snackbar` through one host (`ui/snackbar.tsx`, store + `SnackbarHost` in the root layout), 4 s, **no Undo** (D-103 Q4). Used for: entry saved, moved to trash / gone notices, bulk-action results, Advanced results. The Snackbar text keeps the old banner test id. `Banner` stays for persistent warnings and errors |
| Transitions | Native-stack `animation: 'slide_from_right'` on Android for pushed screens (Mihon's slide), `'fade'` for the full-screen player, `'slide_from_bottom'` for capture; web keeps none. `android.predictiveBackGestureEnabled: true` in `app.config.ts`. Edge-to-edge is already on (SDK 58 default); system bar colors follow the theme (spec 22) |
| Sheets | Kodakiko's own `Sheet` (D-103 Q3): slides up on open; drag the handle or header down to close (Gesture Handler pan + Reanimated, both installed). Closes when dragged past 30% of its height or flung down; otherwise springs back. A visible drag handle on top |
| Haptics | `expo-haptics` 58.0.7 (SDK 58 bundled `~58.0.7`, released 2026-10-16). `ui/haptics.ts`: `tick(kind)` with kinds `select`, `toggle`, `shutter`, `recordStart`, `recordStop`, `refresh`; Android uses `performAndroidHapticsAsync` (honors the system "touch feedback" switch), no-op on web, and off when the new setting **Haptic feedback** (Appearance, default on) is off |
| Pull-to-refresh | `RefreshControl` (no dependency) on Gallery, Calendar and Shared runs `services.sync.syncNow()`; spinner until it settles; hidden in local-only mode and while sync is paused |
| Long-press select | Already in the gallery grid; added to the Day list (same selection bar). Haptic `select` on entering selection |
| Material You | `@pchmn/expo-material3-theme` 1.4.0 (MIT, 2026-02-02; reads the Android 12+ system palette, falls back to a seed color with `@material/material-color-utilities`). New setting **Dynamic color** (Appearance, Android 12+ only, default off); when on, the light/dark color set comes from the system palette, otherwise the current theme (D-103 Q2) |
| Font scale | Already respected: no `allowFontScaling={false}` or `maxFontSizeMultiplier` in the client (checked 2026-11-14). A lint rule keeps it that way (`allowFontScaling={false}` forbidden) |
| Device audit (plan phase 1) | Needs the programmer on a phone; not blocking. §5 is the checklist to fill on the v0.2.2 APK |

## 1. Phases

1. Spec, dependencies allowlisted (`docs/dependencies.md`), catalog entries.
2. Touch feedback: `Touchable`, migrate all raw `Pressable`s, lint rule.
3. Paper `Button`, settings rows, Snackbar host and the transient messages.
4. Transitions, predictive back, sheet drag-to-close.
5. Haptics, pull-to-refresh, long-press select in the Day list.
6. Material You setting; font-scale lint rule.
7. Screenshots, version 0.2.2, tag `v0.2.2`.

## 2. Tests

- Unit: `Touchable` passes `android_ripple` on Android and none on web; `haptics.tick` is a no-op
  on web and when the setting is off, and maps kinds to Android haptic types; snackbar store
  shows, replaces and expires messages; sheet close rule (`shouldCloseSheet(dy, vy, height)`);
  dynamic-color mapping from an M3 scheme to Kodakiko's color set.
- Lint: a raw `Pressable` import outside `src/ui/` fails `pnpm verify` (checked by a script test).
- Web UI flows: all existing flows unchanged. New **U-32**: trash an entry from the viewer → a
  Snackbar shows "Moved to trash." without an Undo button.
- Android (UNVERIFIED until a device run): ripple, transitions, predictive back, haptics,
  pull-to-refresh, dynamic color.

## 3. Out of scope

Shared-element transitions; swipe between tag tabs; Undo; a sheet library; Kotlin.

## 4. Implementation notes

- **Phase 2:** `ui/Touchable.tsx` (Android foreground ripple in the text color at 12%, borderless
  option; web dims to 70% while pressed). All 23 files with `<Pressable>` use it. ESLint:
  `react-native` › `Pressable` is a restricted import outside `src/ui/`, and
  `allowFontScaling={false}` is forbidden; `scripts/test/client-lint-rules.test.ts` (red first).
  Unit test `touchable.test.ts` (red first).

## 5. Device checklist (programmer, on the v0.2.2 APK)

| Screen | Action | Feels native? | Note |
|---|---|---|---|
| Gallery | tap a tile, long-press, pull down | | |
| Viewer | swipe, zoom, play a video, back gesture | | |
| Capture | shutter tap and hold, mode switch | | |
| Calendar | swipe months, pull down | | |
| Settings | every row and switch, back gesture | | |
| Sheets | open, drag down to close | | |
