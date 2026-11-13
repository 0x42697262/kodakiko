import { describe, expect, it } from 'vitest';
import {
  createGalleryIndex,
  localDateOf,
  type GalleryFilter,
  type IndexedEntry,
} from '../../src/index.ts';

const HOME = 'home-collection';
const SHARED = 'shared-collection';
const DAY_MS = 86_400_000;
const BASE_MS = Date.parse('2026-11-11T12:00:00.000Z');
const WORDS = ['beach', 'sunset', 'lake', 'forest', 'city', 'dinner', 'family', 'river'];
const TAGS = ['a', 'b', 'c', 'd'];

type EntryOverrides = Partial<Omit<IndexedEntry, 'localDate' | 'monthDay'>>;

function entry(id: string, overrides: EntryOverrides = {}): IndexedEntry {
  const takenAtUtc = overrides.takenAtUtc ?? '2026-11-11T12:00:00.000Z';
  const utcOffsetMinutes = overrides.utcOffsetMinutes ?? 0;
  const localDate = localDateOf(takenAtUtc, utcOffsetMinutes);
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
    utcOffsetMinutes,
    localDate,
    monthDay: localDate.slice(5),
  };
}

function filter(overrides: Partial<GalleryFilter> = {}): GalleryFilter {
  return {
    scope: { kind: 'home' },
    view: 'journal',
    tags: [],
    fromDate: null,
    toDate: null,
    text: '',
    ...overrides,
  };
}

type Synthetic = { readonly entry: IndexedEntry; readonly note: string };

function syntheticEntries(): readonly Synthetic[] {
  return Array.from({ length: 50 }, (_, index) => {
    const id = `e${String(index).padStart(2, '0')}`;
    const takenAtUtc = new Date(BASE_MS - Math.floor(index / 3) * DAY_MS - (index % 3) * 3_600_000).toISOString();
    const tags = TAGS.filter((_, tagIndex) => index % (tagIndex + 2) === 0);
    return {
      entry: entry(id, {
        takenAtUtc,
        tags,
        trashed: index % 10 === 9,
        collectionIds: index % 7 === 0 ? [HOME, SHARED] : [HOME],
      }),
      note: `${WORDS[index % WORDS.length]} ${WORDS[(index + 3) % WORDS.length]} number${index}`,
    };
  });
}

function filled(items: readonly Synthetic[] = syntheticEntries()) {
  const index = createGalleryIndex({ homeCollectionId: HOME });
  for (const item of items) index.upsert(item.entry, item.note);
  return index;
}

function flatIds(sections: readonly { readonly entryIds: readonly string[] }[]): string[] {
  return sections.flatMap((section) => [...section.entryIds]);
}

function expectedIds(predicate: (item: Synthetic) => boolean): string[] {
  return syntheticEntries()
    .filter(predicate)
    .map((item) => item.entry)
    .sort((left, right) =>
      left.takenAtUtc === right.takenAtUtc
        ? left.id.localeCompare(right.id)
        : Date.parse(right.takenAtUtc) - Date.parse(left.takenAtUtc),
    )
    .map((item) => item.id);
}

describe('gallery index: ordering and grouping', () => {
  it('orders by takenAtUtc descending over 50 entries and groups by local date', () => {
    const index = filled();
    expect(index.size()).toBe(50);
    const sections = index.query(filter());
    expect(flatIds(sections)).toEqual(expectedIds((item) => !item.entry.trashed));
    const dates = sections.map((section) => section.localDate);
    expect(dates).toEqual([...dates].sort().reverse());
    expect(new Set(dates).size).toBe(dates.length);
    for (const section of sections) {
      for (const id of section.entryIds) expect(index.get(id)?.localDate).toBe(section.localDate);
    }
  });

  it('breaks ties on equal times by id ascending', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    for (const id of ['c', 'a', 'b']) index.upsert(entry(id), '');
    expect(flatIds(index.query(filter()))).toEqual(['a', 'b', 'c']);
  });

  it('orders mixed ISO precisions by instant, not by string', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('whole', { takenAtUtc: '2026-11-10T10:00:00Z' }), '');
    index.upsert(entry('fraction', { takenAtUtc: '2026-11-10T10:00:00.500Z' }), '');
    expect(flatIds(index.query(filter()))).toEqual(['fraction', 'whole']);
  });

  it('puts an entry at 2026-11-10T23:30Z with offset +60 on 2026-11-11', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('late', { takenAtUtc: '2026-11-10T23:30:00.000Z', utcOffsetMinutes: 60 }), '');
    index.upsert(entry('earlier', { takenAtUtc: '2026-11-10T22:30:00.000Z', utcOffsetMinutes: 0 }), '');
    index.upsert(entry('west', { takenAtUtc: '2026-11-11T02:00:00.000Z', utcOffsetMinutes: -300 }), '');
    expect(index.query(filter())).toEqual([
      { localDate: '2026-11-10', entryIds: ['west'] },
      { localDate: '2026-11-11', entryIds: ['late'] },
      { localDate: '2026-11-10', entryIds: ['earlier'] },
    ]);
    expect(index.get('late')?.localDate).toBe('2026-11-11');
  });
});

describe('gallery index: filters', () => {
  it.each([
    [['a'], (tags: readonly string[]) => tags.includes('a')],
    [['a', 'b'], (tags: readonly string[]) => tags.includes('a') && tags.includes('b')],
    [['b', 'c'], (tags: readonly string[]) => tags.includes('b') && tags.includes('c')],
    [['missing'], () => false],
  ])('tags %j are AND-combined', (tags, predicate) => {
    const sections = filled().query(filter({ tags }));
    expect(flatIds(sections)).toEqual(
      expectedIds((item) => !item.entry.trashed && predicate(item.entry.tags)),
    );
  });

  it.each([
    ['2026-11-05', '2026-11-08'],
    ['2026-11-08', null],
    [null, '2026-11-01'],
    ['2026-11-11', '2026-11-11'],
  ])('date range %s..%s is inclusive', (fromDate, toDate) => {
    const sections = filled().query(filter({ fromDate, toDate }));
    expect(flatIds(sections)).toEqual(
      expectedIds(
        (item) =>
          !item.entry.trashed &&
          (fromDate === null || item.entry.localDate >= fromDate) &&
          (toDate === null || item.entry.localDate <= toDate),
      ),
    );
  });

  it('includes both range ends', () => {
    const sections = filled().query(filter({ fromDate: '2026-11-10', toDate: '2026-11-10' }));
    expect(sections.map((section) => section.localDate)).toEqual(['2026-11-10']);
  });

  it.each([
    ['bea sun', ['beach sunset']],
    ['sun bea', ['beach sunset']],
    ['BEACH', ['beach sunset', 'beach day']],
    ['sunset', ['beach sunset']],
    ['bea lake', []],
    ['zzz', []],
  ])('text %j prefix-matches every query token', (text, notes) => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('one', { takenAtUtc: '2026-11-11T10:00:00.000Z' }), 'beach sunset');
    index.upsert(entry('two', { takenAtUtc: '2026-11-11T09:00:00.000Z' }), 'beach day');
    index.upsert(entry('three', { takenAtUtc: '2026-11-11T08:00:00.000Z' }), 'lake walk');
    const byNote = new Map([
      ['beach sunset', 'one'],
      ['beach day', 'two'],
      ['lake walk', 'three'],
    ]);
    expect(flatIds(index.query(filter({ text })))).toEqual(notes.map((note) => byNote.get(note)));
  });

  it('combines tags, dates and text', () => {
    const sections = filled().query(
      filter({ tags: ['a'], fromDate: '2026-11-01', toDate: '2026-11-10', text: 'lak' }),
    );
    expect(flatIds(sections)).toEqual(
      expectedIds(
        (item) =>
          !item.entry.trashed &&
          item.entry.tags.includes('a') &&
          item.entry.localDate >= '2026-11-01' &&
          item.entry.localDate <= '2026-11-10' &&
          item.note.split(' ').some((word) => word.startsWith('lak')),
      ),
    );
  });

  it('trash view shows only trashed entries', () => {
    const sections = filled().query(filter({ view: 'trash' }));
    expect(flatIds(sections)).toEqual(expectedIds((item) => item.entry.trashed));
  });

  it('scope home vs collection', () => {
    const index = filled();
    index.upsert(
      entry('shared-only', { collectionIds: [SHARED], takenAtUtc: '2026-11-12T00:00:00.000Z' }),
      'shared',
    );
    expect(flatIds(index.query(filter()))).not.toContain('shared-only');
    expect(flatIds(index.query(filter({ scope: { kind: 'collection', collectionId: SHARED } })))).toEqual([
      'shared-only',
      ...expectedIds((item) => !item.entry.trashed && item.entry.collectionIds.includes(SHARED)),
    ]);
  });
});

describe('gallery index: suggestions, on this day, calendar', () => {
  it('ranks tag suggestions by count, then alphabetically, from own non-trashed entries', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('1', { tags: ['beach', 'bike'] }), '');
    index.upsert(entry('2', { tags: ['beach', 'books'] }), '');
    index.upsert(entry('3', { tags: ['bike', 'beach'] }), '');
    index.upsert(entry('4', { tags: ['books'], trashed: true }), '');
    index.upsert(entry('5', { tags: ['books', 'boat'], isOwn: false }), '');
    index.upsert(entry('6', { tags: ['city'] }), '');
    expect(index.tagSuggestions('b', 10)).toEqual(['beach', 'bike', 'books']);
    expect(index.tagSuggestions('', 2)).toEqual(['beach', 'bike']);
    expect(index.tagSuggestions('  BI ', 10)).toEqual(['bike']);
    expect(index.tagSuggestions('boat', 10)).toEqual([]);
    expect(index.tagSuggestions('', 10)).toEqual(['beach', 'bike', 'books', 'city']);
  });

  it('on this day: same MM-DD in earlier years, grouped by years ago, newest first', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('this-year', { takenAtUtc: '2026-11-11T08:00:00.000Z' }), '');
    index.upsert(entry('one-a', { takenAtUtc: '2025-11-11T08:00:00.000Z' }), '');
    index.upsert(entry('one-b', { takenAtUtc: '2025-11-11T18:00:00.000Z' }), '');
    index.upsert(entry('three', { takenAtUtc: '2023-11-11T08:00:00.000Z' }), '');
    index.upsert(entry('other-day', { takenAtUtc: '2025-11-12T08:00:00.000Z' }), '');
    index.upsert(entry('trashed', { takenAtUtc: '2024-11-11T08:00:00.000Z', trashed: true }), '');
    index.upsert(entry('shared', { takenAtUtc: '2024-11-11T08:00:00.000Z', collectionIds: [SHARED] }), '');
    expect(index.onThisDay('2026-11-11', { kind: 'home' })).toEqual([
      { yearsAgo: 1, entryIds: ['one-b', 'one-a'] },
      { yearsAgo: 3, entryIds: ['three'] },
    ]);
    expect(index.onThisDay('2026-11-11', { kind: 'collection', collectionId: SHARED })).toEqual([
      { yearsAgo: 2, entryIds: ['shared'] },
    ]);
    expect(index.onThisDay('2026-06-01', { kind: 'home' })).toEqual([]);
  });

  it('daysWithEntries maps each day of the month to its newest non-trashed entry in scope', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('early', { takenAtUtc: '2026-11-03T08:00:00.000Z' }), '');
    index.upsert(entry('late', { takenAtUtc: '2026-11-03T20:00:00.000Z' }), '');
    index.upsert(entry('trashed', { takenAtUtc: '2026-11-03T22:00:00.000Z', trashed: true }), '');
    index.upsert(entry('seventeen', { takenAtUtc: '2026-11-17T08:00:00.000Z' }), '');
    index.upsert(entry('october', { takenAtUtc: '2026-10-31T08:00:00.000Z' }), '');
    index.upsert(entry('shared', { takenAtUtc: '2026-11-05T08:00:00.000Z', collectionIds: [SHARED] }), '');
    expect([...index.daysWithEntries('2026-11', { kind: 'home' })].sort()).toEqual([
      ['2026-11-03', 'late'],
      ['2026-11-17', 'seventeen'],
    ]);
  });
});

describe('gallery index: updates and unreadable entries', () => {
  it('upsert of a changed entry removes its old tags and words', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('x', { tags: ['old'] }), 'beach');
    index.upsert(entry('x', { tags: ['new'] }), 'forest');
    expect(flatIds(index.query(filter({ tags: ['old'] })))).toEqual([]);
    expect(flatIds(index.query(filter({ tags: ['new'] })))).toEqual(['x']);
    expect(flatIds(index.query(filter({ text: 'bea' })))).toEqual([]);
    expect(flatIds(index.query(filter({ text: 'for' })))).toEqual(['x']);
    expect(index.tagSuggestions('', 10)).toEqual(['new']);
    expect(index.size()).toBe(1);
  });

  it('upsert moves an entry to its new day and month-day', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    index.upsert(entry('x', { takenAtUtc: '2025-11-11T08:00:00.000Z' }), '');
    index.upsert(entry('x', { takenAtUtc: '2025-11-12T08:00:00.000Z' }), '');
    expect(index.onThisDay('2026-11-11', { kind: 'home' })).toEqual([]);
    expect(index.onThisDay('2026-11-12', { kind: 'home' })).toEqual([{ yearsAgo: 1, entryIds: ['x'] }]);
  });

  it('remove drops the entry from every structure', () => {
    const index = filled();
    index.remove('e00');
    expect(index.get('e00')).toBeNull();
    expect(index.size()).toBe(49);
    expect(flatIds(index.query(filter()))).not.toContain('e00');
    expect(flatIds(index.query(filter({ tags: ['a'] })))).not.toContain('e00');
  });

  it('unreadable entries are excluded from tag and text queries but present unfiltered', () => {
    const index = filled();
    index.upsert(entry('locked', { unreadable: true, tags: ['a'], coverMediaId: null, mediaCount: 0 }), 'beach');
    expect(flatIds(index.query(filter()))).toContain('locked');
    expect(flatIds(index.query(filter({ tags: ['a'] })))).not.toContain('locked');
    expect(flatIds(index.query(filter({ text: 'beach' })))).not.toContain('locked');
    expect(index.tagSuggestions('a', 10)).toEqual(['a']);
  });

  it('version increments on every change and clear empties the index', () => {
    const index = createGalleryIndex({ homeCollectionId: HOME });
    const versions = [index.version()];
    index.upsert(entry('x'), '');
    versions.push(index.version());
    index.upsert(entry('x', { tags: ['t'] }), '');
    versions.push(index.version());
    index.remove('x');
    versions.push(index.version());
    index.upsert(entry('y'), '');
    index.clear();
    versions.push(index.version());
    for (let position = 1; position < versions.length; position += 1) {
      expect(versions[position]).toBeGreaterThan(versions[position - 1] ?? Number.POSITIVE_INFINITY);
    }
    expect(index.size()).toBe(0);
    expect(index.query(filter())).toEqual([]);
    expect(index.tagSuggestions('', 10)).toEqual([]);
  });
});
