import { localDateOf, localTimeOf } from '@kodakiko/core';
import { ENTRY_TIME_LOCALE, ENTRY_TIME_ZONE, MS_PER_DAY, MS_PER_MINUTE } from '../../constants/entry-constants';
import { EN } from '../../text/en';

const YEAR_LENGTH = 4;

const WEEKDAY_DAY_FORMAT = new Intl.DateTimeFormat(ENTRY_TIME_LOCALE, {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  timeZone: ENTRY_TIME_ZONE,
});

const FULL_DATE_FORMAT = new Intl.DateTimeFormat(ENTRY_TIME_LOCALE, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  timeZone: ENTRY_TIME_ZONE,
});

function shifted(takenAtUtc: string, utcOffsetMinutes: number): Date {
  return new Date(Date.parse(takenAtUtc) + utcOffsetMinutes * MS_PER_MINUTE);
}

export function formatEntryTime(
  takenAtUtc: string,
  utcOffsetMinutes: number,
  nowUtc: string,
  deviceOffsetMinutes: number,
): string {
  const entryDate = localDateOf(takenAtUtc, utcOffsetMinutes);
  const time = localTimeOf(takenAtUtc, utcOffsetMinutes);
  const today = localDateOf(nowUtc, deviceOffsetMinutes);
  const yesterday = localDateOf(
    new Date(Date.parse(nowUtc) - MS_PER_DAY).toISOString(),
    deviceOffsetMinutes,
  );
  if (entryDate === today) return EN['entryTime.today'](time);
  if (entryDate === yesterday) return EN['entryTime.yesterday'](time);
  const sameYear = entryDate.slice(0, YEAR_LENGTH) === today.slice(0, YEAR_LENGTH);
  const day = shifted(takenAtUtc, utcOffsetMinutes);
  const dateText = sameYear ? WEEKDAY_DAY_FORMAT.format(day) : FULL_DATE_FORMAT.format(day);
  return EN['entryTime.dated'](dateText, time);
}
