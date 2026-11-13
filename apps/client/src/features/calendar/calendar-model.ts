import type { GalleryFilter, GalleryIndex, WeekStart } from '@kodakiko/core';
import {
  CALENDAR_LOCALE,
  CALENDAR_TIME_ZONE,
  DATE_DAY_END,
  DATE_DAY_START,
  DATE_MONTH_END,
  DATE_MONTH_START,
  DATE_YEAR_END,
  DECIMAL_RADIX,
  LOCAL_DATE_ROUTE_PATTERN,
  MS_PER_DAY,
  REFERENCE_SUNDAY_UTC_MS,
  SINGLE_ENTRY,
  DAYS_PER_WEEK,
  MONTH_FIRST_DAY_SUFFIX,
  MONTH_LAST_DAY_SUFFIX,
} from '../../constants/calendar-constants';
import { EN } from '../../text/en';

export type DayInfo = { readonly count: number; readonly coverEntryId: string };


const monthTitleFormat = new Intl.DateTimeFormat(CALENDAR_LOCALE, {
  month: 'long',
  year: 'numeric',
  timeZone: CALENDAR_TIME_ZONE,
});
const weekdayFormat = new Intl.DateTimeFormat(CALENDAR_LOCALE, {
  weekday: 'short',
  timeZone: CALENDAR_TIME_ZONE,
});
const longDateFormat = new Intl.DateTimeFormat(CALENDAR_LOCALE, {
  month: 'long',
  day: 'numeric',
  year: 'numeric',
  timeZone: CALENDAR_TIME_ZONE,
});

function datePart(localDate: string, start: number, end: number): number {
  return Number.parseInt(localDate.slice(start, end), DECIMAL_RADIX);
}

function utcOf(localDate: string): Date {
  const date = new Date(0);
  date.setUTCFullYear(
    datePart(localDate, 0, DATE_YEAR_END),
    datePart(localDate, DATE_MONTH_START, DATE_MONTH_END) - 1,
    datePart(localDate, DATE_DAY_START, DATE_DAY_END),
  );
  return date;
}

export function isValidLocalDate(value: string): boolean {
  if (!LOCAL_DATE_ROUTE_PATTERN.test(value)) return false;
  const date = utcOf(value);
  return (
    date.getUTCFullYear() === datePart(value, 0, DATE_YEAR_END) &&
    date.getUTCMonth() === datePart(value, DATE_MONTH_START, DATE_MONTH_END) - 1 &&
    date.getUTCDate() === datePart(value, DATE_DAY_START, DATE_DAY_END)
  );
}

export function formatMonthTitle(yearMonth: string): string {
  return monthTitleFormat.format(utcOf(`${yearMonth}${MONTH_FIRST_DAY_SUFFIX}`));
}

export function weekdayNames(weekStartsOn: WeekStart): readonly string[] {
  return Array.from({ length: DAYS_PER_WEEK }, (_, position) =>
    weekdayFormat.format(
      new Date(REFERENCE_SUNDAY_UTC_MS + ((position + weekStartsOn) % DAYS_PER_WEEK) * MS_PER_DAY),
    ),
  );
}

export function cellLabel(localDate: string, count: number): string {
  const date = longDateFormat.format(utcOf(localDate));
  if (count === 0) return EN['calendar.cellNoEntries'](date);
  if (count === SINGLE_ENTRY) return EN['calendar.cellOneEntry'](date);
  return EN['calendar.cellEntries'](date, count);
}

export function monthDayInfo(
  index: GalleryIndex,
  yearMonth: string,
  scope: GalleryFilter['scope'],
): ReadonlyMap<string, DayInfo> {
  const covers = index.daysWithEntries(yearMonth, scope);
  if (covers.size === 0) return new Map();
  const sections = index.query({
    scope,
    view: 'journal',
    tags: [],
    fromDate: `${yearMonth}${MONTH_FIRST_DAY_SUFFIX}`,
    toDate: `${yearMonth}${MONTH_LAST_DAY_SUFFIX}`,
    text: '',
  });
  const info = new Map<string, DayInfo>();
  for (const section of sections) {
    const coverEntryId = covers.get(section.localDate);
    if (coverEntryId === undefined) continue;
    info.set(section.localDate, { count: section.entryIds.length, coverEntryId });
  }
  return info;
}
