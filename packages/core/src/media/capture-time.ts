import { UTC_OFFSET_MINUTES_MAX, UTC_OFFSET_MINUTES_MIN } from '@kodakiko/contract';
import {
  EXIF_DATE_PARTS_PATTERN,
  EXIF_OFFSET_PATTERN,
  MEDIA_DECIMAL_RADIX,
  MEDIA_MINUTES_PER_HOUR,
  MEDIA_MS_PER_MINUTE,
} from './constants.ts';
import { MEDIA_TEXT } from './text.ts';

export type TimeSource = 'exif' | 'container' | 'file' | 'now';

export type ResolvedTime = {
  readonly capturedAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly source: TimeSource;
};

export type CaptureTimeInput = {
  readonly exifDateTimeOriginal: string | null;
  readonly exifOffset: string | null;
  readonly containerCreationUtc: string | null;
  readonly fileModifiedUtc: string | null;
  readonly nowUtc: string;
  readonly deviceOffsetMinutes: number;
};

function parseOffsetMinutes(text: string | null): number | null {
  if (text === null) return null;
  const match = EXIF_OFFSET_PATTERN.exec(text);
  if (match === null) return null;
  const hours = Number.parseInt(match[2] ?? '', MEDIA_DECIMAL_RADIX);
  const minutes = Number.parseInt(match[3] ?? '', MEDIA_DECIMAL_RADIX);
  if (minutes >= MEDIA_MINUTES_PER_HOUR) return null;
  const total = hours * MEDIA_MINUTES_PER_HOUR + minutes;
  const signedTotal = match[1] === '-' ? 0 - total : total;
  return signedTotal < UTC_OFFSET_MINUTES_MIN || signedTotal > UTC_OFFSET_MINUTES_MAX
    ? null
    : signedTotal;
}

function localMilliseconds(text: string): number | null {
  const match = EXIF_DATE_PARTS_PATTERN.exec(text);
  if (match === null) return null;
  const parts = match.slice(1).map((part) => Number.parseInt(part, MEDIA_DECIMAL_RADIX));
  const [year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0] = parts;
  const milliseconds = Date.UTC(year, month - 1, day, hour, minute, second);
  const check = new Date(milliseconds);
  const matches =
    check.getUTCFullYear() === year &&
    check.getUTCMonth() === month - 1 &&
    check.getUTCDate() === day &&
    check.getUTCHours() === hour &&
    check.getUTCMinutes() === minute &&
    check.getUTCSeconds() === second;
  return matches ? milliseconds : null;
}

function normalizeIso(text: string | null): string | null {
  if (text === null) return null;
  const milliseconds = Date.parse(text);
  return Number.isNaN(milliseconds) ? null : new Date(milliseconds).toISOString();
}

export function resolveCaptureTime(input: CaptureTimeInput): ResolvedTime {
  const local = input.exifDateTimeOriginal === null ? null : localMilliseconds(input.exifDateTimeOriginal);
  if (local !== null) {
    const offset = parseOffsetMinutes(input.exifOffset) ?? input.deviceOffsetMinutes;
    return {
      capturedAtUtc: new Date(local - offset * MEDIA_MS_PER_MINUTE).toISOString(),
      utcOffsetMinutes: offset,
      source: 'exif',
    };
  }
  const container = normalizeIso(input.containerCreationUtc);
  if (container !== null) {
    return {
      capturedAtUtc: container,
      utcOffsetMinutes: input.deviceOffsetMinutes,
      source: 'container',
    };
  }
  const file = normalizeIso(input.fileModifiedUtc);
  if (file !== null) {
    return { capturedAtUtc: file, utcOffsetMinutes: input.deviceOffsetMinutes, source: 'file' };
  }
  return {
    capturedAtUtc: input.nowUtc,
    utcOffsetMinutes: input.deviceOffsetMinutes,
    source: 'now',
  };
}

export function earliestTime(times: readonly ResolvedTime[]): ResolvedTime {
  const [first, ...rest] = times;
  if (first === undefined) throw new RangeError(MEDIA_TEXT.noTimes);
  return rest.reduce(
    (earliest, time) =>
      Date.parse(time.capturedAtUtc) < Date.parse(earliest.capturedAtUtc) ? time : earliest,
    first,
  );
}

export function deviceOffsetMinutes(date: Date): number {
  return 0 - date.getTimezoneOffset();
}
