import type { SeedEntry } from './seed.ts';

const DAY_MS = 86_400_000;
const NOON_UTC_HOURS = 12;
const DAYS_AGO = [2, 3, 4] as const;

function noonDaysAgo(now: number, days: number): string {
  const day = new Date(now - days * DAY_MS);
  day.setUTCHours(NOON_UTC_HOURS, 0, 0, 0);
  return day.toISOString();
}

export function galleryFilterEntries(now: number): readonly SeedEntry[] {
  const [first, second, third] = DAYS_AGO;
  return [
    { takenAtUtc: noonDaysAgo(now, first), offsetMinutes: 0, note: 'Morning run', tags: ['a'], media: ['screenshot.png'] },
    { takenAtUtc: noonDaysAgo(now, second), offsetMinutes: 0, note: 'Picnic', tags: ['a', 'b'], media: ['screenshot.png'] },
    { takenAtUtc: noonDaysAgo(now, third), offsetMinutes: 0, note: 'Swim in the lake', tags: ['c'], media: ['screenshot.png'] },
  ];
}
