import { localDateOf, type IndexedEntry } from '@kodakiko/core';
import { describe, expect, it } from 'vitest';
import { journalStatistics } from '../src/features/more/statistics-model';

function entry(id: string, takenAtUtc: string, extra: Partial<IndexedEntry> = {}): IndexedEntry {
  const localDate = localDateOf(takenAtUtc, 0);
  return {
    id, isOwn: true, collectionIds: ['home'], trashed: false, unreadable: false, takenAtUtc, utcOffsetMinutes: 0,
    localDate, monthDay: localDate.slice(5), tags: [], coverMediaId: null, mediaCount: 1, hasVideo: false, notePreview: '', pending: false,
    ...extra,
  };
}

describe('journalStatistics', () => {
  const entries = [
    entry('a', '2025-12-20T10:00:00.000Z', { mediaCount: 3, tags: ['beach', 'family'], notePreview: 'x' }),
    entry('b', '2026-10-01T10:00:00.000Z', { hasVideo: true, tags: ['beach'] }),
    entry('c', '2026-11-05T10:00:00.000Z', { tags: ['beach'], pending: true }),
  ];
  const stats = journalStatistics(entries, '2026-11-14');

  it('counts entries, items, videos, notes and waiting entries', () => {
    expect(stats.entries).toBe(3);
    expect(stats.mediaItems).toBe(5);
    expect(stats.entriesWithVideo).toBe(1);
    expect(stats.entriesWithNote).toBe(1);
    expect(stats.waitingToUpload).toBe(1);
  });

  it('ranks tags by count and gives the first and last day', () => {
    expect(stats.topTags).toEqual([{ tag: 'beach', count: 3 }, { tag: 'family', count: 1 }]);
    expect(stats.firstDay).toBe('2025-12-20');
    expect(stats.lastDay).toBe('2026-11-05');
  });

  it('gives the last 12 months, oldest first, and every year', () => {
    expect(stats.perMonth).toHaveLength(12);
    expect(stats.perMonth[0]).toEqual({ month: '2025-12', count: 1 });
    expect(stats.perMonth[11]).toEqual({ month: '2026-11', count: 1 });
    expect(stats.perMonth[10]).toEqual({ month: '2026-10', count: 1 });
    expect(stats.perYear).toEqual([{ year: '2025', count: 1 }, { year: '2026', count: 2 }]);
  });
});
