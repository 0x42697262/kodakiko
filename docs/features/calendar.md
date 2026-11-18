# Calendar

A month grid with the cover photo of each day that has entries. Code: `:ui:feature:calendar`
(`CalendarContent`, `CalendarSheets`, `CalendarViewModel`, `CalendarLabels`), `:shared:domain`
(calendar helpers), `:shared:index` (days with entries).

## The calendar tab

- **Header:** ‹ previous month, the month title ("November 2026"), › next month, **Today** (back to
  the current month) and **Jump to…**.
- **Grid:** a weekday row (Mon … Sun; weeks start on Monday) and always 6 rows of 7 square cells,
  so the layout never jumps. A day with entries shows the cover thumbnail of its newest entry with
  the day number on top; an empty day shows the number only; today has a colored ring. Days of the
  neighboring months are **blank cells** (no number, no background), so the month's own days stand
  out (v0.4.1; v0.4.0 only dimmed their numbers, which was hard to tell apart). Cells read as
  "November 9, 2026, 3 entries" or "…, no entries" to screen readers.
- **Changing months:** the grid is a horizontal pager, so the month follows the finger and snaps.
  The pages are every month from January 1900 up to the current month, then only future months
  that have entries, so a page's position never changes while you browse. ‹ and › scroll the pager
  by one page, and a month counts as chosen only when scrolling stops (v0.4.1: in v0.4.0 the page
  list was rebuilt on every change, so arrows and swipes could jump to another month).
- **Future months:** past the current month, › skips months without entries and goes to the next
  month that has some; it is disabled after the last one.
- **Jump to…** (v0.4.1) opens a picker instead of v0.3.1's `YYYY-MM` text field: a year with ‹ ›
  buttons and twelve month buttons (the shown month highlighted). Picking a month goes there; an
  empty future month answers "No entries in that month yet." and stays.
- **Pull down** to sync now (server accounts).
- Counts and covers come from the gallery index (own, not trashed entries) and update when an
  entry changes, for example when its time moves it to another day.

## A day (`/day/<date>`)

A tap on a day with entries opens its entries in the gallery grid, with the date as the title
("Today", "Monday, Nov 9", "Nov 9, 2024"), selection mode and bulk actions. An invalid date returns
to the calendar; a day whose entries were all trashed meanwhile shows "No entries on this day."

## Domain helpers

`monthGrid(yearMonth, today, weekStartsOn)` (6 × 7 cells, date arithmetic on `YYYY-MM-DD`
strings), `addMonths`, `yearMonthOf`, `isValidYearMonth`, `canShowMonth`, `monthTarget` (the
skip-empty-months rule) and `pagerMonths`, in `:shared:domain`, golden-tested against the v0.3.1
TypeScript versions.
