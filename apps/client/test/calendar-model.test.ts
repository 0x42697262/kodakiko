import { describe, expect, it } from 'vitest';
import { createGalleryIndex, type IndexedEntry } from '@kodakiko/core';
import {
  cellLabel,
  formatMonthTitle,
  isValidLocalDate,
  monthDayInfo,
  weekdayNames,
} from '../src/features/calendar/calendar-model';

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
