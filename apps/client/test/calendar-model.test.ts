import { describe, expect, it } from 'vitest';
import { createGalleryIndex, type IndexedEntry } from '@kodakiko/core';
import {
  cellLabel,
  formatMonthTitle,
  isValidLocalDate,
  monthDayInfo,
  weekdayNames, canShowMonth, monthTarget } from '../src/features/calendar/calendar-model';

const HOME = 'H'.repeat(22);

function entry(id: string, takenAtUtc: string, localDate: string, trashed = false): IndexedEntry {
  return {
    id,
    isOwn: true,
    collectionIds: [HOME],
    trashed,
    unreadable: false,
    takenAtUtc,
    utcOffsetMinutes: 0,
    localDate,
    monthDay: localDate.slice(5),
    tags: [],
    coverMediaId: `${id}-media`,
    mediaCount: 1,
    hasVideo: false,
    notePreview: '',
    pending: false,
  };
}

describe('monthDayInfo', () => {
  it('counts non-trashed entries per day and picks the newest as cover', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('a'.repeat(22), '2026-11-03T08:00:00.000Z', '2026-11-03'), '');
    index.upsert(entry('b'.repeat(22), '2026-11-03T18:00:00.000Z', '2026-11-03'), '');
    index.upsert(entry('c'.repeat(22), '2026-11-17T09:00:00.000Z', '2026-11-17', true), '');
    index.upsert(entry('d'.repeat(22), '2026-10-31T09:00:00.000Z', '2026-10-31'), '');

    const info = monthDayInfo(index, '2026-11', { kind: 'home' });

    expect([...info.keys()]).toEqual(['2026-11-03']);
    expect(info.get('2026-11-03')).toEqual({ count: 2, coverEntryId: 'b'.repeat(22) });
  });

  it('is empty for a month without entries', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    expect(monthDayInfo(index, '2026-11', { kind: 'home' }).size).toBe(0);
  });
});

describe('calendar text', () => {
  it('formats the month title in English', () => {
    expect(formatMonthTitle('2026-11')).toBe('November 2026');
    expect(formatMonthTitle('2024-02')).toBe('February 2024');
  });

  it('lists short weekday names starting on Monday or Sunday', () => {
    expect(weekdayNames(1)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    expect(weekdayNames(0)).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']);
  });

  it('labels cells with the date and the entry count', () => {
    expect(cellLabel('2026-11-09', 3)).toBe('November 9, 2026, 3 entries');
    expect(cellLabel('2026-11-09', 1)).toBe('November 9, 2026, 1 entry');
    expect(cellLabel('2026-11-09', 0)).toBe('November 9, 2026, no entries');
  });
});

describe('isValidLocalDate', () => {
  it('accepts real dates only', () => {
    expect(isValidLocalDate('2024-02-29')).toBe(true);
    expect(isValidLocalDate('2026-11-09')).toBe(true);
    for (const bad of ['2026-02-29', '2026-13-01', '2026-11-31', '2026-1-1', 'today', '']) {
      expect(isValidLocalDate(bad)).toBe(false);
    }
  });
});

describe('monthTarget (v0.2.1 fix B1)', () => {
  const withEntries = new Set(['2026-08', '2026-12', '2025-01']);

  it('moves freely into past months and up to the current month', () => {
    expect(monthTarget('2026-03', -1, '2026-06', withEntries)).toBe('2026-02');
    expect(monthTarget('2026-05', 1, '2026-06', withEntries)).toBe('2026-06');
    expect(monthTarget('2025-01', 1, '2026-06', withEntries)).toBe('2025-02');
  });

  it('beyond the current month, jumps over empty months to the next month with entries', () => {
    expect(monthTarget('2026-06', 1, '2026-06', withEntries)).toBe('2026-08');
    expect(monthTarget('2026-08', 1, '2026-06', withEntries)).toBe('2026-12');
  });

  it('refuses to move past the last future month with entries', () => {
    expect(monthTarget('2026-12', 1, '2026-06', withEntries)).toBeNull();
    expect(monthTarget('2026-06', 1, '2026-06', new Set())).toBeNull();
  });

  it('allows jumping to a month when it is not in the future or has entries', () => {
    expect(canShowMonth('2020-01', '2026-06', withEntries)).toBe(true);
    expect(canShowMonth('2026-06', '2026-06', withEntries)).toBe(true);
    expect(canShowMonth('2026-08', '2026-06', withEntries)).toBe(true);
    expect(canShowMonth('2026-07', '2026-06', withEntries)).toBe(false);
  });
});
