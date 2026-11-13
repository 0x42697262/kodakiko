import type { SeedEntry } from './seed.ts';

const NOON_UTC_HOURS = 12;
const DAYS = [3, 17] as const;
const MONTH_DIGITS = 2;

function noonOfDay(now: number, day: number): string {
  const date = new Date(now);
  date.setUTCDate(day);
  date.setUTCHours(NOON_UTC_HOURS, 0, 0, 0);
  return date.toISOString();
}

export function calendarEntries(now: number): readonly SeedEntry[] {
  return DAYS.map((day) => ({
    takenAtUtc: noonOfDay(now, day),
    offsetMinutes: 0,
    note: `Day ${day}`,
    tags: [],
    media: ['screenshot.png'],
  }));
}

export function calendarDayId(now: number, day: number): string {
  const date = new Date(now);
  const month = String(date.getUTCMonth() + 1).padStart(MONTH_DIGITS, '0');
  const dayText = String(day).padStart(MONTH_DIGITS, '0');
  return `calendar.day.${date.getUTCFullYear()}-${month}-${dayText}`;
}
