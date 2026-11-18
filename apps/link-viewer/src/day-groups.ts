import {
  DATE_LOCALE,
  DATE_PART_WIDTH,
  DATE_TIME_ZONE,
  ISO_DATE_LENGTH,
  MONTH_OFFSET,
  MS_PER_DAY,
  MS_PER_MINUTE,
  YEAR_LENGTH,
} from './constants.ts';
import { DATE_SEPARATOR, MIDNIGHT_SUFFIX } from './keys.ts';
import { TEXT } from './text.ts';

export type DatedItem = { readonly takenAtUtc: string; readonly utcOffsetMinutes: number };

export type DayGroup<Item> = { readonly dayKey: string; readonly items: readonly Item[] };

const WEEKDAY_FORMAT = new Intl.DateTimeFormat(DATE_LOCALE, {
  weekday: 'long',
  month: 'short',
  day: 'numeric',
  timeZone: DATE_TIME_ZONE,
});

const FULL_FORMAT = new Intl.DateTimeFormat(DATE_LOCALE, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  timeZone: DATE_TIME_ZONE,
});

function midnight(localDate: string): Date {
  return new Date(`${localDate}${MIDNIGHT_SUFFIX}`);
}

function pad(value: number): string {
  return String(value).padStart(DATE_PART_WIDTH, '0');
}

export function localDayKey(takenAtUtc: string, utcOffsetMinutes: number): string {
  return new Date(Date.parse(takenAtUtc) + utcOffsetMinutes * MS_PER_MINUTE)
    .toISOString()
    .slice(0, ISO_DATE_LENGTH);
}

export function todayKey(now: Date): string {
  return [String(now.getFullYear()), pad(now.getMonth() + MONTH_OFFSET), pad(now.getDate())].join(
    DATE_SEPARATOR,
  );
}

export function formatDayHeader(localDate: string, today: string): string {
  if (localDate === today) return TEXT.today;
  const yesterday = new Date(midnight(today).getTime() - MS_PER_DAY)
    .toISOString()
    .slice(0, ISO_DATE_LENGTH);
  if (localDate === yesterday) return TEXT.yesterday;
  const sameYear = localDate.slice(0, YEAR_LENGTH) === today.slice(0, YEAR_LENGTH);
  return (sameYear ? WEEKDAY_FORMAT : FULL_FORMAT).format(midnight(localDate));
}

export function groupByDay<Item extends DatedItem>(items: readonly Item[]): DayGroup<Item>[] {
  const days = new Map<string, Item[]>();
  for (const item of items) {
    const dayKey = localDayKey(item.takenAtUtc, item.utcOffsetMinutes);
    const day = days.get(dayKey);
    if (day === undefined) days.set(dayKey, [item]);
    else day.push(item);
  }
  return [...days.entries()]
    .sort(([left], [right]) => Number(right > left) - Number(right < left))
    .map(([dayKey, dayItems]) => ({ dayKey, items: dayItems }));
}
