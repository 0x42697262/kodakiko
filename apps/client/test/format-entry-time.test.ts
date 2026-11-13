import { describe, expect, it } from 'vitest';
import { formatEntryTime } from '../src/features/entries/format-entry-time';

const NOW_UTC = '2026-11-11T20:00:00.000Z';

describe('formatEntryTime', () => {
  it.each([
    { name: 'today', taken: '2026-11-11T14:05:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Today, 14:05' },
    { name: 'today at midnight', taken: '2026-11-11T00:00:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Today, 00:00' },
    { name: 'yesterday', taken: '2026-11-10T09:30:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Yesterday, 09:30' },
    { name: 'earlier this year', taken: '2026-11-09T18:00:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Mon, Nov 9, 18:00' },
    { name: 'the first day of this year', taken: '2026-01-01T08:15:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Thu, Jan 1, 08:15' },
    { name: 'last year', taken: '2024-11-09T18:00:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Nov 9, 2024, 18:00' },
    { name: 'the last day of last year', taken: '2025-12-31T23:59:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Dec 31, 2025, 23:59' },
    { name: 'tomorrow', taken: '2026-11-12T10:00:00.000Z', offset: 0, now: NOW_UTC, device: 0, expected: 'Thu, Nov 12, 10:00' },
  ])('shows $name', ({ taken, offset, now, device, expected }) => {
    expect(formatEntryTime(taken, offset, now, device)).toBe(expected);
  });

  it.each([
    { name: 'a +14:00 entry that is already today there', taken: '2026-11-10T20:00:00.000Z', offset: 840, now: NOW_UTC, device: 0, expected: 'Today, 10:00' },
    { name: 'a -12:00 entry that is still yesterday there', taken: '2026-11-11T08:00:00.000Z', offset: -720, now: NOW_UTC, device: 0, expected: 'Yesterday, 20:00' },
    { name: 'a device ahead of UTC across midnight', taken: '2026-11-11T12:00:00.000Z', offset: 0, now: '2026-11-11T23:30:00.000Z', device: 120, expected: 'Yesterday, 12:00' },
    { name: 'a device behind UTC before midnight UTC', taken: '2026-11-10T15:00:00.000Z', offset: 0, now: '2026-11-11T02:00:00.000Z', device: -300, expected: 'Today, 15:00' },
    { name: 'the entry own offset, not the device offset', taken: '2026-11-11T23:30:00.000Z', offset: 60, now: NOW_UTC, device: 0, expected: 'Thu, Nov 12, 00:30' },
  ])('shows $name', ({ taken, offset, now, device, expected }) => {
    expect(formatEntryTime(taken, offset, now, device)).toBe(expected);
  });

  it.each([
    { name: 'yesterday across New Year', taken: '2026-12-31T23:00:00.000Z', now: '2027-01-01T10:00:00.000Z', expected: 'Yesterday, 23:00' },
    { name: 'last year across New Year', taken: '2026-12-30T10:00:00.000Z', now: '2027-01-01T10:00:00.000Z', expected: 'Dec 30, 2026, 10:00' },
  ])('shows $name', ({ taken, now, expected }) => {
    expect(formatEntryTime(taken, 0, now, 0)).toBe(expected);
  });
});
