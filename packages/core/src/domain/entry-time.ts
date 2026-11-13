import {
  DECIMAL_RADIX,
  HOURS_PER_DAY,
  ISO_DATE_LENGTH,
  ISO_TIME_END,
  ISO_TIME_START,
  LOCAL_DATE_PATTERN,
  LOCAL_TIME_PATTERN,
  MINUTES_PER_HOUR,
  MS_PER_MINUTE,
  YEAR_MAX,
  YEAR_MIN,
} from './constants.ts';

function shiftedIso(takenAtUtc: string, utcOffsetMinutes: number): string {
  return new Date(Date.parse(takenAtUtc) + utcOffsetMinutes * MS_PER_MINUTE).toISOString();
}

export function localDateOf(takenAtUtc: string, utcOffsetMinutes: number): string {
  return shiftedIso(takenAtUtc, utcOffsetMinutes).slice(0, ISO_DATE_LENGTH);
}

export function localTimeOf(takenAtUtc: string, utcOffsetMinutes: number): string {
  return shiftedIso(takenAtUtc, utcOffsetMinutes).slice(ISO_TIME_START, ISO_TIME_END);
}

function parts(match: RegExpExecArray): number[] {
  return match.slice(1).map((part) => Number.parseInt(part, DECIMAL_RADIX));
}

export function localPartsToUtc(
  date: string,
  time: string,
  utcOffsetMinutes: number,
): string | null {
  const dateMatch = LOCAL_DATE_PATTERN.exec(date);
  const timeMatch = LOCAL_TIME_PATTERN.exec(time);
  if (dateMatch === null || timeMatch === null) return null;
  const [year = 0, month = 0, day = 0] = parts(dateMatch);
  const [hours = 0, minutes = 0] = parts(timeMatch);
  if (hours >= HOURS_PER_DAY || minutes >= MINUTES_PER_HOUR) return null;
  const local = new Date(0);
  local.setUTCFullYear(year, month - 1, day);
  local.setUTCHours(hours, minutes, 0, 0);
  const isRealDate =
    local.getUTCFullYear() === year && local.getUTCMonth() === month - 1 && local.getUTCDate() === day;
  if (!isRealDate) return null;
  const utc = new Date(local.getTime() - utcOffsetMinutes * MS_PER_MINUTE);
  const utcYear = utc.getUTCFullYear();
  return utcYear < YEAR_MIN || utcYear > YEAR_MAX ? null : utc.toISOString();
}

export function currentTimeZone(): string | null {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return typeof zone === 'string' && zone !== '' ? zone : null;
}
