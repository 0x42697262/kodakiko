import { describe, expect, it } from 'vitest';
import {
  createGalleryIndex,
  localDateOf,
  sortEntryIds,
  type GalleryFilter,
  type IndexedEntry,
} from '../../src/index.ts';

const HOME = 'home-collection';
const SHARED = 'shared-collection';

function entry(id: string, overrides: Partial<Omit<IndexedEntry, 'localDate' | 'monthDay'>> = {}): IndexedEntry {
  const takenAtUtc = overrides.takenAtUtc ?? '2026-11-11T12:00:00.000Z';
  const localDate = localDateOf(takenAtUtc, 0);
  return {
    id,
    isOwn: true,
    collectionIds: [HOME],
    trashed: false,
    unreadable: false,
    tags: [],
    coverMediaId: `${id}-media`,
    mediaCount: 1,
    hasVideo: false,
    notePreview: '',
    pending: false,
    ...overrides,
    takenAtUtc,
    utcOffsetMinutes: 0,
    localDate,
    monthDay: localDate.slice(5),
  };
}

function filter(attributes: GalleryFilter['attributes']): GalleryFilter {
  return { scope: { kind: 'home' }, view: 'journal', tags: [], fromDate: null, toDate: null, text: '', attributes };
}

function ids(index: ReturnType<typeof createGalleryIndex>, value: GalleryFilter): string[] {
  return index.query(value).flatMap((section) => [...section.entryIds]).sort();
}

const ENTRIES = [
  entry('plain'),
  entry('video', { hasVideo: true }),
  entry('noted', { notePreview: 'a day at the lake' }),
  entry('many', { mediaCount: 3 }),
  entry('shared', { collectionIds: [HOME, SHARED] }),
  entry('pending', { pending: true }),
];

function filled() {
  const index = createGalleryIndex({ homeCollectionId: HOME });
  for (const item of ENTRIES) index.upsert(item, item.notePreview);
  return index;
}

describe('gallery attribute filters (spec 22)', () => {
  it('includes or excludes each attribute', () => {
    const index = filled();
    expect(ids(index, filter({ video: 'include' }))).toEqual(['video']);
    expect(ids(index, filter({ note: 'include' }))).toEqual(['noted']);
    expect(ids(index, filter({ multiple: 'include' }))).toEqual(['many']);
    expect(ids(index, filter({ shared: 'include' }))).toEqual(['shared']);
    expect(ids(index, filter({ pending: 'include' }))).toEqual(['pending']);
    expect(ids(index, filter({ video: 'exclude' }))).toEqual(['many', 'noted', 'pending', 'plain', 'shared']);
  });

  it('combines filters with AND and treats off and missing as no filter', () => {
    const index = filled();
    index.upsert(entry('video-note', { hasVideo: true, notePreview: 'clip' }), 'clip');
    expect(ids(index, filter({ video: 'include', note: 'include' }))).toEqual(['video-note']);
    expect(ids(index, filter({ video: 'include', note: 'exclude' }))).toEqual(['video']);
    expect(ids(index, filter({ video: 'off' }))).toHaveLength(ENTRIES.length + 1);
    expect(ids(index, filter(undefined))).toHaveLength(ENTRIES.length + 1);
  });

  it('counts an entry from another account as shared', () => {
    const index = filled();
    index.upsert(entry('theirs', { isOwn: false, collectionIds: [SHARED] }), '');
    const value: GalleryFilter = { ...filter({ shared: 'include' }), scope: { kind: 'collection', collectionId: SHARED } };
    expect(ids(index, value)).toEqual(['shared', 'theirs']);
  });
});

describe('sortEntryIds (spec 22)', () => {
  const lookup = new Map<string, IndexedEntry>([
    ['a', entry('a', { takenAtUtc: '2026-01-01T00:00:00.000Z', notePreview: 'xx', mediaCount: 2 })],
    ['b', entry('b', { takenAtUtc: '2026-03-01T00:00:00.000Z', notePreview: '', mediaCount: 5 })],
    ['c', entry('c', { takenAtUtc: '2026-02-01T00:00:00.000Z', notePreview: 'xxxxx', mediaCount: 1 })],
  ]);
  const get = (id: string) => lookup.get(id) ?? null;

  it('sorts by date taken, note length and number of photos in both directions', () => {
    expect(sortEntryIds(['a', 'b', 'c'], get, { key: 'takenAt', descending: true }, 0)).toEqual(['b', 'c', 'a']);
    expect(sortEntryIds(['a', 'b', 'c'], get, { key: 'takenAt', descending: false }, 0)).toEqual(['a', 'c', 'b']);
    expect(sortEntryIds(['a', 'b', 'c'], get, { key: 'noteLength', descending: true }, 0)).toEqual(['c', 'a', 'b']);
    expect(sortEntryIds(['a', 'b', 'c'], get, { key: 'mediaCount', descending: false }, 0)).toEqual(['c', 'a', 'b']);
  });

  it('shuffles the same way for the same seed and differently for another seed', () => {
    const many = Array.from({ length: 20 }, (_, position) => `id${position}`);
    for (const id of many) lookup.set(id, entry(id));
    const first = sortEntryIds(many, get, { key: 'random', descending: false }, 7);
    expect(sortEntryIds(many, get, { key: 'random', descending: false }, 7)).toEqual(first);
    expect(sortEntryIds(many, get, { key: 'random', descending: false }, 8)).not.toEqual(first);
    expect([...first].sort()).toEqual([...many].sort());
  });
});

describe('allTags (spec 22 tag tabs)', () => {
  it('lists every tag of journal entries alphabetically with its count', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('x', { tags: ['beach', 'family'] }), '');
    index.upsert(entry('y', { tags: ['beach'] }), '');
    index.upsert(entry('z', { tags: ['zoo'], trashed: true }), '');
    expect(index.allTags()).toEqual([
      { tag: 'beach', count: 2 },
      { tag: 'family', count: 1 },
    ]);
  });
});
