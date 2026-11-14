import { localDateOf, type IndexedEntry } from '@kodakiko/core';
import { describe, expect, it } from 'vitest';
import { UI_SETTINGS_DEFAULTS } from '../src/constants/ui-settings-constants';
import { arrangeSections, columnsFor, galleryAttributes, groupSections, UNGROUPED_DATE, UNTAGGED_GROUP } from '../src/features/gallery/gallery-arrange';
import { buildGalleryItems } from '../src/features/gallery/gallery-items';

function entry(id: string, takenAtUtc: string, mediaCount = 1): IndexedEntry {
  const localDate = localDateOf(takenAtUtc, 0);
  return {
    id, isOwn: true, collectionIds: ['home'], trashed: false, unreadable: false, takenAtUtc, utcOffsetMinutes: 0,
    localDate, monthDay: localDate.slice(5), tags: [], coverMediaId: null, mediaCount, hasVideo: false, notePreview: '', pending: false,
  };
}

const ENTRIES = new Map([
  ['a', entry('a', '2026-11-02T10:00:00.000Z', 3)],
  ['b', entry('b', '2026-11-02T08:00:00.000Z', 1)],
  ['c', entry('c', '2026-11-01T09:00:00.000Z', 2)],
]);
const get = (id: string) => ENTRIES.get(id) ?? null;
const SECTIONS = [
  { localDate: '2026-11-02', entryIds: ['a', 'b'] },
  { localDate: '2026-11-01', entryIds: ['c'] },
];

describe('arrangeSections', () => {
  it('keeps day sections for newest-first date order', () => {
    expect(arrangeSections(SECTIONS, get, { key: 'takenAt', descending: true }, true, 0)).toEqual(SECTIONS);
  });

  it('reverses days and entries for oldest-first date order', () => {
    expect(arrangeSections(SECTIONS, get, { key: 'takenAt', descending: false }, true, 0)).toEqual([
      { localDate: '2026-11-01', entryIds: ['c'] },
      { localDate: '2026-11-02', entryIds: ['b', 'a'] },
    ]);
  });

  it('returns one ungrouped section for other sorts or when grouping is off', () => {
    expect(arrangeSections(SECTIONS, get, { key: 'mediaCount', descending: true }, true, 0)).toEqual([
      { localDate: UNGROUPED_DATE, entryIds: ['a', 'c', 'b'] },
    ]);
    expect(arrangeSections(SECTIONS, get, { key: 'takenAt', descending: true }, false, 0)).toEqual([
      { localDate: UNGROUPED_DATE, entryIds: ['a', 'b', 'c'] },
    ]);
  });

  it('builds no day header for the ungrouped section', () => {
    const items = buildGalleryItems([{ localDate: UNGROUPED_DATE, entryIds: ['a', 'b'] }], 2, false);
    expect(items.some((item) => item.type === 'header')).toBe(false);
  });
});

describe('galleryAttributes', () => {
  it('maps the tri-state settings and hides shared journals on request', () => {
    expect(galleryAttributes({ ...UI_SETTINGS_DEFAULTS.galleryFilters, video: 'include' }, false)).toEqual({
      video: 'include', note: 'off', multiple: 'off', shared: 'off', pending: 'off', favorite: 'off',
    });
    expect(galleryAttributes(UI_SETTINGS_DEFAULTS.galleryFilters, true).shared).toBe('exclude');
  });
});

describe('columnsFor', () => {
  it('uses the portrait or landscape setting and falls back to the automatic count', () => {
    expect(columnsFor(400, 800, 3, 6)).toBe(3);
    expect(columnsFor(800, 400, 3, 6)).toBe(6);
    expect(columnsFor(400, 800, 0, 6)).toBeGreaterThan(0);
  });
});

describe('grouping (spec 23)', () => {
  const tagged = new Map([
    ['a', { ...entry('a', '2026-11-02T10:00:00.000Z'), tags: ['beach'] }],
    ['b', { ...entry('b', '2026-10-02T08:00:00.000Z'), tags: ['beach', 'city'] }],
    ['c', { ...entry('c', '2025-11-01T09:00:00.000Z'), tags: [] }],
  ]);
  const lookup = (id: string) => tagged.get(id) ?? null;
  const sections = [
    { localDate: '2026-11-02', entryIds: ['a'] },
    { localDate: '2026-10-02', entryIds: ['b'] },
    { localDate: '2025-11-01', entryIds: ['c'] },
  ];
  const newest = { key: 'takenAt', descending: true } as const;

  it('groups by month and by year, newest first', () => {
    expect(groupSections(sections, lookup, newest, 'month', 0).map((section) => section.localDate)).toEqual(['m:2026-11', 'm:2026-10', 'm:2025-11']);
    expect(groupSections(sections, lookup, newest, 'year', 0)).toEqual([
      { localDate: 'y:2026', entryIds: ['a', 'b'] },
      { localDate: 'y:2025', entryIds: ['c'] },
    ]);
  });

  it('groups by tag alphabetically, lists an entry under each of its tags, and puts untagged entries last', () => {
    expect(groupSections(sections, lookup, newest, 'tag', 0)).toEqual([
      { localDate: 't:beach', entryIds: ['a', 'b'] },
      { localDate: 't:city', entryIds: ['b'] },
      { localDate: UNTAGGED_GROUP, entryIds: ['c'] },
    ]);
  });

  it('keeps day sections for day grouping and one section for none', () => {
    expect(groupSections(sections, lookup, newest, 'day', 0)).toEqual(sections);
    expect(groupSections(sections, lookup, newest, 'none', 0)).toEqual([{ localDate: UNGROUPED_DATE, entryIds: ['a', 'b', 'c'] }]);
  });
});
