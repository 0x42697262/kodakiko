import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  currentTimeZone,
  localDateOf,
  localPartsToUtc,
  localTimeOf,
} from '../src/index.ts';

const MINUTES_PER_HOUR = 60;
const UTC_PLUS_14 = 14 * MINUTES_PER_HOUR;
const UTC_MINUS_12 = -12 * MINUTES_PER_HOUR;

afterEach(() => {
  vi.restoreAllMocks();
});

describe('localDateOf and localTimeOf', () => {
  it.each([
    { name: 'utc', utc: '2026-11-11T14:05:00.000Z', offset: 0, date: '2026-11-11', time: '14:05' },
    { name: 'a positive offset crossing midnight', utc: '2026-11-11T23:30:00.000Z', offset: 60, date: '2026-11-12', time: '00:30' },
    { name: 'a negative offset crossing midnight', utc: '2026-11-12T00:30:00.000Z', offset: -120, date: '2026-11-11', time: '22:30' },
    { name: 'the leap day', utc: '2028-02-29T12:00:00.000Z', offset: 0, date: '2028-02-29', time: '12:00' },
    { name: 'the day after the leap day', utc: '2028-02-29T23:59:00.000Z', offset: 1, date: '2028-03-01', time: '00:00' },
    { name: '23:59 local', utc: '2026-06-01T23:59:00.000Z', offset: 0, date: '2026-06-01', time: '23:59' },
    { name: 'offset +14:00 into the next year', utc: '2026-12-31T10:00:00.000Z', offset: UTC_PLUS_14, date: '2027-01-01', time: '00:00' },
    { name: 'offset -12:00 into the previous day', utc: '2026-01-01T11:59:00.000Z', offset: UTC_MINUS_12, date: '2025-12-31', time: '23:59' },
    { name: 'an offset with minutes', utc: '2026-03-01T18:30:00.000Z', offset: 345, date: '2026-03-02', time: '00:15' },
  ])('$name', ({ utc, offset, date, time }) => {
    expect(localDateOf(utc, offset)).toBe(date);
    expect(localTimeOf(utc, offset)).toBe(time);
  });
});

describe('localPartsToUtc', () => {
  it.each([
    { name: 'a plain time', date: '2026-11-11', time: '14:05', offset: 60, expected: '2026-11-11T13:05:00.000Z' },
    { name: 'utc', date: '2026-11-11', time: '00:00', offset: 0, expected: '2026-11-11T00:00:00.000Z' },
    { name: 'the leap day', date: '2028-02-29', time: '23:59', offset: 0, expected: '2028-02-29T23:59:00.000Z' },
    { name: 'offset +14:00', date: '2026-01-01', time: '00:00', offset: UTC_PLUS_14, expected: '2025-12-31T10:00:00.000Z' },
    { name: 'offset -12:00', date: '2026-12-31', time: '23:59', offset: UTC_MINUS_12, expected: '2027-01-01T11:59:00.000Z' },
  ])('converts $name', ({ date, time, offset, expected }) => {
    expect(localPartsToUtc(date, time, offset)).toBe(expected);
  });

  it.each([
    { name: 'a date that does not exist', date: '2026-02-30', time: '10:00' },
    { name: 'a leap day in a common year', date: '2027-02-29', time: '10:00' },
    { name: 'month 13', date: '2026-13-01', time: '10:00' },
    { name: 'day 00', date: '2026-01-00', time: '10:00' },
    { name: 'a date without padding', date: '2026-1-1', time: '10:00' },
    { name: 'a date with other separators', date: '2026/01/01', time: '10:00' },
    { name: 'hour 24', date: '2026-01-01', time: '24:00' },
    { name: 'minute 60', date: '2026-01-01', time: '12:60' },
    { name: 'a time without padding', date: '2026-01-01', time: '9:05' },
    { name: 'a time with seconds', date: '2026-01-01', time: '09:05:00' },
    { name: 'empty fields', date: '', time: '' },
    { name: 'a date with trailing text', date: '2026-01-01x', time: '10:00' },
  ])('refuses $name', ({ date, time }) => {
    expect(localPartsToUtc(date, time, 0)).toBeNull();
  });

  it('refuses a result outside the years 0000 to 9999', () => {
    expect(localPartsToUtc('9999-12-31', '23:59', UTC_MINUS_12)).toBeNull();
  });

  it.each([0, 60, -300, UTC_PLUS_14, UTC_MINUS_12])(
    'round-trips localDateOf and localTimeOf at offset %i',
    (offset) => {
      const utc = '2026-11-11T14:05:00.000Z';
      expect(localPartsToUtc(localDateOf(utc, offset), localTimeOf(utc, offset), offset)).toBe(utc);
    },
  );
});

describe('currentTimeZone', () => {
  it('returns the resolved time zone of the runtime', () => {
    expect(currentTimeZone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
  });

  it('returns null when the runtime reports no time zone', () => {
    vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(
      () => ({ resolvedOptions: () => ({}) }) as unknown as Intl.DateTimeFormat,
    );
    expect(currentTimeZone()).toBeNull();
  });
});
