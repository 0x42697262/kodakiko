import type { WeekStart } from '@kodakiko/core';
import {
  canShowMonth,
  cellLabel,
  formatMonthTitle,
  isValidLocalDate,
  monthTarget,
  PAGER_LEAD_MONTHS,
  pagerMonths,
  weekdayNames,
} from '../../src/features/calendar/calendar-model';
import { functionCase, setJson, type GoldenCase, type GoldenWriter } from './shared';

const PATH = 'calendar/model.json';
const WITH_ENTRIES = ['2026-08', '2026-12', '2025-01'] as const;
const PAGER_ENTRIES = ['2026-02', '2026-08', '2026-12'] as const;
const MONDAY: WeekStart = 1;
const SUNDAY: WeekStart = 0;

type MonthTargetSpec = readonly [name: string, from: string, delta: number, current: string, months: readonly string[]];
type CanShowSpec = readonly [name: string, yearMonth: string, current: string, months: readonly string[]];
type PagerSpec = readonly [name: string, current: string, months: readonly string[], shown: string];

const VALID_DATES: readonly (readonly [string, string])[] = [
  ['accepts real dates only (2024-02-29)', '2024-02-29'],
  ['accepts real dates only (2026-11-09)', '2026-11-09'],
  ['accepts real dates only (2026-02-29)', '2026-02-29'],
  ['accepts real dates only (2026-13-01)', '2026-13-01'],
  ['accepts real dates only (2026-11-31)', '2026-11-31'],
  ['accepts real dates only (2026-1-1)', '2026-1-1'],
  ['accepts real dates only (today)', 'today'],
  ['accepts real dates only (empty)', ''],
  ['edge: 1900-02-29 is not a leap day', '1900-02-29'],
  ['edge: 2000-02-29 is a leap day', '2000-02-29'],
  ['edge: year 0000', '0000-01-01'],
  ['edge: year 0099 is not shifted to 1999', '0099-12-31'],
  ['edge: year 9999', '9999-12-31'],
  ['edge: month 00', '2026-00-10'],
  ['edge: day 00', '2026-02-00'],
  ['edge: day 32', '2026-01-32'],
  ['edge: April 31', '2026-04-31'],
  ['edge: December 31', '2026-12-31'],
  ['edge: leading space', ' 2026-01-01'],
  ['edge: trailing newline', '2026-01-01\n'],
  ['edge: fullwidth digits', '２０２６-01-01'],
  ['edge: Arabic-Indic digits', '٢٠٢٦-01-01'],
  ['edge: extended year', '+02026-01-01'],
  ['edge: date with a time', '2026-01-01T00:00'],
  ['edge: slashes', '2026/01/01'],
];

const CAN_SHOW: readonly CanShowSpec[] = [
  ['allows jumping to a month when it is not in the future or has entries (2020-01)', '2020-01', '2026-06', WITH_ENTRIES],
  ['allows jumping to a month when it is not in the future or has entries (2026-06)', '2026-06', '2026-06', WITH_ENTRIES],
  ['allows jumping to a month when it is not in the future or has entries (2026-08)', '2026-08', '2026-06', WITH_ENTRIES],
  ['allows jumping to a month when it is not in the future or has entries (2026-07)', '2026-07', '2026-06', WITH_ENTRIES],
  ['edge: next year without entries', '2027-01', '2026-12', []],
  ['edge: months compare as strings', '2026-6', '2026-06', []],
];

const MONTH_TARGETS: readonly MonthTargetSpec[] = [
  ['moves freely into past months and up to the current month (back one)', '2026-03', -1, '2026-06', WITH_ENTRIES],
  ['moves freely into past months and up to the current month (forward to current)', '2026-05', 1, '2026-06', WITH_ENTRIES],
  ['moves freely into past months and up to the current month (from a month with entries)', '2025-01', 1, '2026-06', WITH_ENTRIES],
  ['beyond the current month, jumps over empty months to the next month with entries (to 2026-08)', '2026-06', 1, '2026-06', WITH_ENTRIES],
  ['beyond the current month, jumps over empty months to the next month with entries (to 2026-12)', '2026-08', 1, '2026-06', WITH_ENTRIES],
  ['refuses to move past the last future month with entries (after 2026-12)', '2026-12', 1, '2026-06', WITH_ENTRIES],
  ['refuses to move past the last future month with entries (no entries)', '2026-06', 1, '2026-06', []],
  ['edge: delta 0 on the current month', '2026-06', 0, '2026-06', []],
  ['edge: delta 0 on an empty future month jumps to the next month with entries', '2026-07', 0, '2026-06', WITH_ENTRIES],
  ['edge: backwards across a year boundary', '2026-01', -1, '2026-06', []],
  ['edge: backwards by 13 months', '2026-02', -13, '2026-06', []],
  ['edge: backwards from an empty future month', '2026-10', -1, '2026-06', WITH_ENTRIES],
  ['edge: forward by 2 lands on a future month with entries', '2026-06', 2, '2026-06', WITH_ENTRIES],
  ['edge: forward by 12 from the past', '2025-06', 12, '2026-06', []],
  ['edge: forward across a year boundary into a month with entries', '2026-11', 2, '2026-06', ['2027-01', '2027-03']],
  ['edge: forward past the current month skips to a later year', '2026-06', 1, '2026-06', ['2030-05', '2028-02']],
];

const PAGERS: readonly PagerSpec[] = [
  [
    'lists every month from a year before the earliest entry to now, then only future months with entries',
    '2026-06',
    PAGER_ENTRIES,
    '2026-06',
  ],
  ['starts a year before the shown month when that is earlier, and works without entries (shown 2020-05)', '2026-06', [], '2020-05'],
  ['starts a year before the shown month when that is earlier, and works without entries (13 months)', '2026-06', [], '2026-06'],
  ['edge: a shown future month without entries is not listed', '2026-06', [], '2026-09'],
  ['edge: entries only in the past across a year boundary', '2026-02', ['2025-11'], '2026-02'],
  ['edge: future entries in later years', '2026-06', ['2028-01', '2026-07', '2027-12'], '2026-07'],
  ['edge: current month in January', '2026-01', [], '2026-01'],
];

function canShowCases(): GoldenCase[] {
  return CAN_SHOW.map(([name, yearMonth, current, months]) =>
    functionCase(
      `canShowMonth: ${name}`,
      'canShowMonth',
      { yearMonth, currentYearMonth: current, monthsWithEntries: setJson(new Set(months)) },
      () => canShowMonth(yearMonth, current, new Set(months)),
    ),
  );
}

function monthTargetCases(): GoldenCase[] {
  return MONTH_TARGETS.map(([name, from, delta, current, months]) =>
    functionCase(
      `monthTarget: ${name}`,
      'monthTarget',
      { from, delta, currentYearMonth: current, monthsWithEntries: setJson(new Set(months)) },
      () => monthTarget(from, delta, current, new Set(months)),
    ),
  );
}

function pagerCases(): GoldenCase[] {
  return PAGERS.map(([name, current, months, shown]) =>
    functionCase(
      `pagerMonths: ${name}`,
      'pagerMonths',
      { currentYearMonth: current, monthsWithEntries: setJson(new Set(months)), shownYearMonth: shown },
      () => pagerMonths(current, new Set(months), shown),
    ),
  );
}

function validDateCases(): GoldenCase[] {
  return VALID_DATES.map(([name, value]) =>
    functionCase(`isValidLocalDate: ${name}`, 'isValidLocalDate', { value }, () => isValidLocalDate(value)),
  );
}

const MONTH_TITLES: readonly (readonly [string, string])[] = [
  ['formats the month title in English (2026-11)', '2026-11'],
  ['formats the month title in English (2024-02)', '2024-02'],
  ['edge: January of year 0999', '0999-01'],
];

const CELL_LABELS: readonly (readonly [string, string, number])[] = [
  ['labels cells with the date and the entry count (3 entries)', '2026-11-09', 3],
  ['labels cells with the date and the entry count (1 entry)', '2026-11-09', 1],
  ['labels cells with the date and the entry count (no entries)', '2026-11-09', 0],
  ['edge: 1000 entries', '2026-01-01', 1000],
];

function textCases(): GoldenCase[] {
  return [
    ...MONTH_TITLES.map(([name, yearMonth]) =>
      functionCase(`formatMonthTitle: ${name}`, 'formatMonthTitle', { yearMonth }, () => formatMonthTitle(yearMonth)),
    ),
    functionCase('weekdayNames: lists short weekday names starting on Monday or Sunday (Monday)', 'weekdayNames', { weekStartsOn: MONDAY }, () =>
      weekdayNames(MONDAY),
    ),
    functionCase('weekdayNames: lists short weekday names starting on Monday or Sunday (Sunday)', 'weekdayNames', { weekStartsOn: SUNDAY }, () =>
      weekdayNames(SUNDAY),
    ),
    ...CELL_LABELS.map(([name, localDate, count]) =>
      functionCase(`cellLabel: ${name}`, 'cellLabel', { localDate, count }, () => cellLabel(localDate, count)),
    ),
  ];
}

export async function writeCalendarGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATH, [
    ...canShowCases(),
    ...monthTargetCases(),
    ...pagerCases(),
    ...validDateCases(),
    ...textCases(),
    { name: 'constants', fn: 'constants', args: {}, result: { PAGER_LEAD_MONTHS } },
  ]);
}
