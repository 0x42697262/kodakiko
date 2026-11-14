# Calendar

A month grid with the cover photo of each day that has entries. Code:
`packages/core/src/domain/calendar.ts`, `apps/client/src/features/calendar/`.

## The calendar tab

- **Header:** ‹ previous month, the month title ("November 2026"), › next month, **Today** (back to
  the current month) and **Jump to…** (a `YYYY-MM` field; "Use the format 2026-11.").
- **Grid:** a weekday row (Mon … Sun; weeks start on Monday) and always 6 rows of 7 square cells,
  so the layout never jumps. A day with entries shows the cover thumbnail of its newest entry with
  the day number on top; an empty day shows the number only; days of the neighboring months are
  dimmed and cannot be tapped; today has a colored ring. Cells read as "November 9, 2026, 3
  entries" or "…, no entries" to screen readers.
- **Changing months:** the grid is a horizontal paging list, so the month follows the finger and
  snaps (scroll snap on the web); ‹ ›, the arrow keys (web) and Jump scroll it. The pages are every
  month from a year before the earliest entry up to the current month, then only future months that
  have entries.
- **Future months:** past the current month, › skips months without entries and goes to the next
  month that has some; it is disabled after the last one. Jump to an empty future month answers
  "No entries in that month yet." and stays.
- **Pull down** to sync now.
- Counts and covers come from the gallery index (own, not trashed entries) and update when an
  entry changes, for example when its time moves it to another day.

## A day (`/day/[date]`)

A tap on a day with entries opens its entries in the gallery grid, with the date as the title
("Today", "Monday, Nov 9", "Nov 9, 2024"), selection mode and bulk actions. An invalid date returns
to the calendar; a day whose entries were all trashed meanwhile shows "No entries on this day."

## Core helpers

`monthGrid(yearMonth, today, weekStartsOn)` (6 × 7 cells, UTC date arithmetic on `YYYY-MM-DD`
strings), `addMonths`, `yearMonthOf`, `isValidYearMonth`; client `calendar-model.ts` adds
`canShowMonth`, `monthTarget` (the skip-empty-months rule) and `pagerMonths` (the page list).
