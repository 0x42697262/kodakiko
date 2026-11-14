# Bug fixes found on v0.1.3 — plan

Status: **B1-B3 fixed for v0.2.1** (branch `fix/v0.2.1-bugs`, cut from `main` at v0.2.0). Written 2026-11-14; results added the same day.
Branch: `fix/v0.1.3-bugs`, cut from `8c85425` (`chore: version 0.1.3`, tag `v0.1.3`).
More bugs are still being collected; this list grows before any spec is written.

## 0. Baseline caveat (read first)

- Every bug below was **found on v0.1.3** on a device. The programmer has **not** tested v0.1.4
  (still building on GitHub) or v0.2.0 (not built yet, spec 23).
- A later version **might** have fixed any of them, or might not. Do not assume either way.
- Evidence so far: `git diff v0.1.3 main` (main at `8f3e4c9`, spec 23 phase 1) touches **none** of
  the files named below (`features/calendar/`, `features/settings/`, `ui/preferences/`,
  `features/capture/`, `adapters/capture/`). So v0.1.4 most likely still has all three bugs.
- Spec 23 step 8 ("remaining spec 22 phase 5 screens (calendar, ...) on app bars") will rewrite the
  calendar header in v0.2.0. Bug 1 may move to a different component there; the rule stays the same.

Before writing the build spec:

1. Programmer re-tests each bug on v0.1.4 once its APK is built (5 minutes per bug).
2. Mark each bug below: `still in v0.1.4` / `fixed in v0.1.4` / `untested`.
3. Bugs fixed in a later version are closed here with the version that fixed them, not coded.
4. Decide the base: fix on this branch (v0.1.3 base, ship as v0.1.5) or rebase onto `main` first
   (open question Q1).

## 1. Bug list

| # | Area | Found on | Symptom (programmer's words, condensed) | v0.1.4 | v0.2.0 |
|---|---|---|---|---|---|
| B1 | Calendar | v0.1.3 | Can move to a future month even when it has no upload | untested | **still there** (U-30 fails on v0.2.0) → fixed in v0.2.1 |
| B2 | Settings | v0.1.3 | Some toggle (switch) rows get stuck | untested | **still there** (code unchanged) → fixed in v0.2.1, Android re-check pending |
| B3 | Capture | v0.1.3 | While a video is recording, NEXT can be pressed before the recording is ended | untested | **still there** (code unchanged) → fixed in v0.2.1 |
| B4 | Tags | v0.1.3 | Tags should only be added or updated in Settings, like categories in Mihon | n/a | **moved to v0.2.0** (D-87) |

## 2. B1 — calendar moves into empty future months

**Where (v0.1.3):** `apps/client/src/features/calendar/CalendarScreen.tsx`.
`move(delta)` calls `addMonths` with no limit. Four paths reach a new month, all unguarded:

1. "›" button (`calendar.next`)
2. Swipe left (`PanResponder`, `NEXT_MONTH`)
3. Keyboard (`useMonthKeys(move)`, web)
4. Jump sheet (`submitJump`, any valid `YYYY-MM`)

**Intended rule (to confirm, Q2):** moving forward past the current month (today's month) is
allowed only if the target month has at least one entry in the home scope
(`index.daysWithEntries(yearMonth, scope).size > 0`). Past months and the current month stay
reachable as now.

**Fix outline:**

1. Add a pure helper in `calendar-model.ts`, e.g. `canShowMonth(index, yearMonth, todayYearMonth, scope)`.
2. Route all four paths through it; a refused move does nothing.
3. Disable the "›" button (`accessibilityState.disabled`) when the next month is refused.
4. Jump sheet: refused future month shows an inline error (new `EN` string; wording is Q4).

**Tests (written first, must fail on v0.1.3):**

1. Unit: helper allows past, current, and future-with-entry months; refuses future-without-entry.
2. UI flow: on the current month with no future entries, "›" is disabled and swipe left keeps the title.
3. UI flow: with an entry dated next month, "›" moves there.
4. UI flow: jump to an empty future month shows the error and keeps the month.

## 3. B2 — settings toggles get stuck

**Where (v0.1.3):** two switch implementations.

- `apps/client/src/ui/preferences/PreferenceList.tsx`, `case 'switch'`: the whole row is a
  `Pressable` that calls `item.onChange(!item.value)`; the right side is a `react-native-paper`
  `Switch` with `pointerEvents="none"` and no `onValueChange`. Used by Appearance, Gallery,
  Security, Storage and Capture settings.
- `apps/client/src/features/settings/settings-ui.tsx`, `SwitchRow` (hand-drawn track and knob).
  Used by `LocalSetupScreen` only.

**Unknown:** which toggles stick, and what "stuck" looks like (Q3). Not diagnosed yet.
Candidates to check, none confirmed:

1. Tapping the Paper `Switch` itself: on Android the native switch may take the touch despite
   `pointerEvents="none"`, flip visually, then snap back because nothing handles it.
2. `value` comes from a store that updates late or not at all (e.g. `services.deviceSettings.update`
   in Storage settings swallows errors with `.catch(() => undefined)`), so the row never re-renders.
3. Toggles with side effects (app lock / biometrics, location permission, full screen) refuse the
   change and the row shows no reason.

**Plan:**

1. Programmer names the stuck toggles and how they stick (tap row vs tap switch, snaps back vs no
   movement vs needs two taps).
2. Reproduce on the emulator, find the cause per toggle, record it here.
3. Then write the fix and the tests (first a failing UI-flow test per stuck toggle: tap → value
   flips → persists after leaving and reopening the screen).

## 4. B3 — NEXT works while recording

**Where (v0.1.3):** `apps/client/src/features/capture/CaptureScreen.tsx` renders NEXT
(`capture.next`) with `disabled={items.length === 0}` and Add-to-entry (`capture.addToEntry`) with
`disabled={items.length === 0 || pending > 0}`. Recording state (`recordingMs`) lives inside
`CameraSurface` in `adapters/capture/capture-surface.native.tsx` (and `web-camera.tsx` on web) and is
never reported to `CaptureScreen`. So with one earlier photo in the tray, NEXT stays enabled during
recording. Spec 09 says only "Next (enabled when ≥ 1 item)"; it is silent on recording.

**Intended behavior:** open (Q5). Options:

- A. NEXT and Add-to-entry are disabled while recording; the user ends the recording first.
  (recommended: smallest change, no lost clips)
- B. NEXT stops the recording, waits for the clip to reach the tray, then navigates.

**Fix outline (option A):**

1. Add an `onRecordingChange(recording: boolean)` prop to `CaptureSurfaceProps`
   (`adapters/capture/capture-surface.tsx`), called by the native and web surfaces.
2. `CaptureScreen` keeps `recording` state and adds `|| recording` to both FABs' `disabled`.
3. Decide whether Close (`capture.close`) is also blocked while recording (Q6).

**Tests (written first, must fail on v0.1.3):**

1. UI flow: take a photo, start recording → NEXT is disabled; stop → clip in tray, NEXT enabled.
2. Same for Add-to-entry when capture opens from an existing entry.

## 4b. B4 — tags only in Settings: moved to v0.2.0

Moved out of this branch (programmer 2026-11-14: "move it to v0.2.0"). Decision **D-87** in
`docs/planning/context.md` on `main`; built in spec 23 phase 4 (`docs/features/23-mihon-parity.md`,
row "Tags only in Settings", flow U-30). Not fixed on the v0.1.x line.

## 5. Open questions (ask in one round)

1. **Q1 Base:** A. fix here on v0.1.3 and ship v0.1.5 (recommended if v0.1.4 still has the bugs)
   B. rebase onto `main` and fix in v0.2.0.
2. **Q2 Calendar rule:** future month with no entry but a later month with one (e.g. empty June,
   entry in August): A. jump over empty months to the next month with entries (recommended)
   B. refuse; only the jump sheet reaches August C. allow walking through empty months up to the
   last month with entries.
3. **Q3 Toggles:** which toggles stick, and do you tap the row or the switch itself?
4. **Q4 Jump error:** A. "No entries in that month yet" (recommended) B. disable future months in
   the jump field entirely.
5. **Q5/Q6 Recording:** NEXT behavior A or B above; and should Close also be blocked while
   recording? A. no, Close keeps its discard prompt (recommended) B. yes.

## 6. Next step

1. Programmer: keep collecting bugs; add each as a row in §1 with the version found.
2. Programmer: re-test B1-B3 on v0.1.4 when the APK finishes building; fill the v0.1.4 column.
3. Opus: ask §5, record answers as D-NN rows in `docs/planning/context.md`, then write one build
   spec for Sonnet (`docs/features/`), one section per bug, tests first.

## 7. Result (v0.2.1, Opus, autopilot under D-85)

Answers chosen as the plan recommended: Q1 B (fix on v0.2.0, ship v0.2.1, per the programmer),
Q2 A, Q4 A, Q5 A, Q6 A. Q3 was answered from the code (cause below).

- **B1:** `calendar-model.ts` `canShowMonth` / `monthTarget`. Past and current months stay
  reachable. Beyond the current month, › jumps over empty months to the next month with entries,
  and is disabled when there is none. Swipe and keys use the same rule; the jump sheet refuses an
  empty future month with "No entries in that month yet.". Unit tests red first; UI flow **U-30**
  failed on v0.2.0 (› went to an empty month) and passes with the fix.
- **B2:** cause: the Paper `Switch` inside a preference row had `pointerEvents="none"` and no
  `onValueChange`. Android's native switch ignores that, takes the tap, flips and snaps back to
  the controlled value. Now the switch handles taps itself on native (`onValueChange`); on web the
  row keeps the click (a web click would otherwise toggle twice). **UNVERIFIED on a device**: it
  cannot be reproduced on web; re-test on Android.
- **B3:** `capture-actions.ts` `captureActionsEnabled` (unit-tested red first). Both camera
  surfaces report recording through `onRecordingChange`; Next and Add to entry stay disabled while
  recording. Close keeps its discard prompt. Web has no camera in the UI test stack, so the check is
  the unit test plus manual Android use.

