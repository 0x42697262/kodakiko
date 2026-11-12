# 12 — Calendar view

Status: draft 1 (2026-11-11).

Goal: a month calendar where every day with entries shows the cover thumbnail of its newest entry
(D-21); tapping a day lists that day's entries; month navigation and a jump-to-date picker. "On
this day" already lives in the gallery (spec 11).

Read first: spec 11 (`daysWithEntries`, `Thumbnail`, `formatDayHeader`, filter store);
`docs/design/client.md` §§1-2; D-21, D-45. Depends on 11.

## 1. Core (`packages/core/src/domain/calendar.ts`)

```ts
export type CalendarCell = { readonly localDate: string; readonly inMonth: boolean; readonly isToday: boolean };
export function monthGrid(yearMonth: string, todayLocalDate: string, weekStartsOn: 0 | 1): readonly (readonly CalendarCell[])[];
export function addMonths(yearMonth: string, delta: number): string;
export function yearMonthOf(localDate: string): string;
export function isValidYearMonth(value: string): boolean;
```

- `monthGrid` returns 6 rows × 7 cells (always 6 rows so the layout never jumps), including the
  trailing days of the previous month and the leading days of the next (`inMonth: false`). Pure
  calendar arithmetic on `YYYY-MM-DD` strings via UTC `Date` (no time zones involved).
- `weekStartsOn`: `WEEK_STARTS_ON = 1` (Monday) in client constants for v1 (no locale detection).
- `addMonths` handles year wrap; `isValidYearMonth` = `^\d{4}-(0[1-9]|1[0-2])$`.

Tests: February in leap and non-leap years, a month starting on Monday and on Sunday, December →
January, `isToday` marking.

## 2. Screen `/(tabs)/calendar`

- Header: "‹" previous month, month title ("November 2026", `Intl.DateTimeFormat('en-US', { month:
  'long', year: 'numeric', timeZone: 'UTC' })`), "›" next month, "Today" button (jumps to the
  current month), "Jump to…" (sheet with a `YYYY-MM` field validated by `isValidYearMonth`, inline
  error "Use the format 2026-11.").
- Weekday row: Mon … Sun (short names from `Intl`, `timeZone: 'UTC'`).
- Grid: 6×7 square cells sized to the screen width. A cell with entries (`daysWithEntries` for the
  displayed month and scope home, non-trashed) shows the cover `<Thumbnail>` of that entry with the
  day number overlaid (white text with a shadow for contrast); empty days show the number only;
  `inMonth: false` cells are dimmed and not tappable; today has a `focus` color ring.
- Swiping horizontally (Android) or the arrow keys (web) changes months.
- Tap a day with entries → `/day/[date]`.
- The displayed month lives in component state, starting at the current month; it re-queries on
  `useIndexVersion()` changes.

Accessibility: each cell's label is "November 9, 2026, 3 entries" or "November 9, 2026, no
entries". Counts are computed for the whole month once per index version with one `query` over
the month's date range (cheap, spec 11 §1.3) into a `Map<localDate, count>`.

## 3. Screen `/day/[date]`

Validates `date` (`^\d{4}-\d{2}-\d{2}$` and a real date; invalid → redirect to `/calendar`).
Header: `formatDayHeader(date, today)`. Body: the same tile grid as the gallery for `query({ scope:
home, view: journal, tags: [], fromDate: date, toDate: date, text: '' })`, with tap → `/entry/[id]`
and selection mode (reuse the gallery grid component: extract `EntryGrid` from spec 11's screen
into `src/features/gallery/entry-grid.tsx` in this spec if spec 11 did not already). Empty (all
entries of that day trashed or deleted meanwhile) → "No entries on this day." with a back button.

## 4. Tests

1. Core calendar tests (§1).
2. Component-level logic in Node: the month count map for a fixture index (3 entries on two days,
   one trashed) gives the right counts and covers.

## 5. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Android and web: current month shows covers on days with entries; navigate back a year with
      "Jump to…"; tap a day → its entries; tap an entry → entry route.
- [ ] Changing an entry's time (spec 10 time editor) to another day moves its cover in the calendar
      without a restart.
- [ ] Screen reader (TalkBack or the browser's accessibility tree): cells read date and count.
