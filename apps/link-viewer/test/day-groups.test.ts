import { describe, expect, it } from 'vitest';
import { formatDayHeader, groupByDay, localDayKey, todayKey } from '../src/day-groups.ts';

type Item = { readonly name: string; readonly takenAtUtc: string; readonly utcOffsetMinutes: number };

function item(name: string, takenAtUtc: string, utcOffsetMinutes = 0): Item {
  return { name, takenAtUtc, utcOffsetMinutes };
}

describe('localDayKey', () => {
  it('uses the local date of the capture, from its own UTC offset', () => {
    expect(localDayKey('2026-05-05T08:00:00.000Z', 0)).toBe('2026-05-05');
    expect(localDayKey('2026-05-05T23:30:00.000Z', 60)).toBe('2026-05-06');
    expect(localDayKey('2026-05-05T02:00:00.000Z', -300)).toBe('2026-05-04');
    expect(localDayKey('2026-12-31T23:59:59.999Z', 1)).toBe('2027-01-01');
    expect(localDayKey('2024-02-29T12:00:00.000Z', 840)).toBe('2024-03-01');
  });

  it('does not depend on the time zone of the visitor', () => {
    const before = process.env['TZ'];
    try {
      process.env['TZ'] = 'Pacific/Kiritimati';
      expect(localDayKey('2026-05-05T08:00:00.000Z', -600)).toBe('2026-05-04');
    } finally {
      if (before === undefined) delete process.env['TZ'];
      else process.env['TZ'] = before;
    }
  });
});

describe('todayKey', () => {
  it('is the visitor’s local calendar date of the given instant', () => {
    expect(todayKey(new Date(2026, 10, 11, 0, 5))).toBe('2026-11-11');
    expect(todayKey(new Date(2026, 0, 1, 23, 59))).toBe('2026-01-01');
  });
});

describe('formatDayHeader (twin of the gallery day header)', () => {
  it.each([
    ['2026-11-11', '2026-11-11', 'Today'],
    ['2026-11-10', '2026-11-11', 'Yesterday'],
    ['2026-11-09', '2026-11-11', 'Monday, Nov 9'],
    ['2026-01-01', '2026-11-11', 'Thursday, Jan 1'],
    ['2026-12-25', '2026-11-11', 'Friday, Dec 25'],
    ['2024-11-09', '2026-11-11', 'Nov 9, 2024'],
    ['2024-02-29', '2026-11-11', 'Feb 29, 2024'],
    ['2025-12-31', '2026-01-01', 'Yesterday'],
    ['2025-12-30', '2026-01-01', 'Dec 30, 2025'],
    ['2024-02-29', '2024-03-01', 'Yesterday'],
    ['2024-05-05', '2026-11-11', 'May 5, 2024'],
  ])('%s seen on %s → %s', (localDate, today, expected) => {
    expect(formatDayHeader(localDate, today)).toBe(expected);
  });
});

describe('groupByDay', () => {
  it('returns nothing for no items', () => {
    expect(groupByDay([])).toEqual([]);
  });

  it('groups items by local day, newest day first, keeping the order inside a day', () => {
    const items = [
      item('a', '2026-05-05T18:00:00.000Z'),
      item('b', '2026-05-05T09:00:00.000Z'),
      item('c', '2026-05-05T09:00:00.000Z'),
      item('d', '2026-05-03T10:00:00.000Z'),
    ];
    expect(groupByDay(items).map((group) => [group.dayKey, group.items.map((entry) => entry.name)])).toEqual([
      ['2026-05-05', ['a', 'b', 'c']],
      ['2026-05-03', ['d']],
    ]);
  });

  it('places an item under its own local day even when its UTC time sorts it among another day', () => {
    const items = [
      item('late-utc', '2026-05-05T23:30:00.000Z', 0),
      item('next-local-day', '2026-05-05T22:00:00.000Z', 180),
      item('same-day', '2026-05-05T08:00:00.000Z', 0),
    ];
    expect(groupByDay(items).map((group) => [group.dayKey, group.items.map((entry) => entry.name)])).toEqual([
      ['2026-05-06', ['next-local-day']],
      ['2026-05-05', ['late-utc', 'same-day']],
    ]);
  });

  it('keeps the full item objects so the grid and the viewer share one order', () => {
    const items = [item('x', '2026-05-01T10:00:00.000Z'), item('y', '2026-05-02T10:00:00.000Z')];
    const groups = groupByDay(items);
    const flattened = groups.flatMap((group) => group.items);
    expect(flattened).toEqual([items[1], items[0]]);
    expect(flattened[0]).toBe(items[1]);
  });
});
