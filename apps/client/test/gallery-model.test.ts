import { describe, expect, it } from 'vitest';
import {
  EMPTY_GALLERY_FILTER,
  isFilterActive,
  validateDateRange,
} from '../src/features/gallery/filter-model';
import { createGalleryFilterStore } from '../src/features/gallery/gallery-store';
import { buildGalleryItems, displayOrder, gridColumns } from '../src/features/gallery/gallery-items';
import { selectRange, toggleSelected } from '../src/features/gallery/selection';
import { EN } from '../src/text/en';

const SECTIONS = [
  { localDate: '2026-11-11', entryIds: ['a', 'b', 'c', 'd'] },
  { localDate: '2026-11-10', entryIds: ['e'] },
];

describe('gridColumns', () => {
  it.each([
    [200, 3],
    [329, 3],
    [330, 3],
    [440, 4],
    [1280, 11],
  ])('width %i → %i columns', (width, columns) => {
    expect(gridColumns(width)).toBe(columns);
  });
});

describe('buildGalleryItems', () => {
  it('flattens day sections into headers and rows of up to `columns` ids with list positions', () => {
    expect(buildGalleryItems(SECTIONS, 3, false)).toEqual([
      { type: 'header', localDate: '2026-11-11' },
      { type: 'row', localDate: '2026-11-11', entryIds: ['a', 'b', 'c'], firstPosition: 0 },
      { type: 'row', localDate: '2026-11-11', entryIds: ['d'], firstPosition: 3 },
      { type: 'header', localDate: '2026-11-10' },
      { type: 'row', localDate: '2026-11-10', entryIds: ['e'], firstPosition: 4 },
    ]);
  });

  it('puts the on-this-day card first when shown', () => {
    expect(buildGalleryItems(SECTIONS, 5, true)[0]).toEqual({ type: 'onThisDay' });
    expect(buildGalleryItems([], 3, true)).toEqual([{ type: 'onThisDay' }]);
  });

  it('lists ids in display order', () => {
    expect(displayOrder(SECTIONS)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });
});

describe('selection', () => {
  it('toggles an id', () => {
    expect([...toggleSelected(new Set(['a']), 'b')]).toEqual(['a', 'b']);
    expect([...toggleSelected(new Set(['a', 'b']), 'a')]).toEqual(['b']);
  });

  it('adds the range between the anchor and the target in list order, both directions', () => {
    const order = ['a', 'b', 'c', 'd', 'e'];
    expect([...selectRange(new Set(['a']), order, 'b', 'd')].sort()).toEqual(['a', 'b', 'c', 'd']);
    expect([...selectRange(new Set(), order, 'e', 'c')].sort()).toEqual(['c', 'd', 'e']);
    expect([...selectRange(new Set(), order, null, 'c')]).toEqual(['c']);
    expect([...selectRange(new Set(), order, 'gone', 'c')]).toEqual(['c']);
  });
});

describe('filter model', () => {
  it('detects active filters', () => {
    expect(isFilterActive(EMPTY_GALLERY_FILTER)).toBe(false);
    expect(isFilterActive({ ...EMPTY_GALLERY_FILTER, tags: ['a'] })).toBe(true);
    expect(isFilterActive({ ...EMPTY_GALLERY_FILTER, fromDate: '2026-01-01' })).toBe(true);
    expect(isFilterActive({ ...EMPTY_GALLERY_FILTER, toDate: '2026-01-01' })).toBe(true);
    expect(isFilterActive({ ...EMPTY_GALLERY_FILTER, text: 'lake' })).toBe(true);
    expect(isFilterActive({ ...EMPTY_GALLERY_FILTER, text: '   ' })).toBe(false);
  });

  it.each([
    ['', '', null],
    ['2026-01-01', '', null],
    ['', '2026-01-01', null],
    ['2026-01-01', '2026-01-01', null],
    ['2026-01-01', '2026-02-01', null],
    ['2026-02-01', '2026-01-01', 'filter.startAfterEnd'],
    ['2026-02-30', '', 'filter.invalidDate'],
    ['', '26-01-01', 'filter.invalidDate'],
    ['2026-1-1', '', 'filter.invalidDate'],
  ] as const)('from %j to %j → %s', (from, to, expected) => {
    expect(validateDateRange(from, to)).toBe(expected);
  });
});

describe('gallery filter store', () => {
  it('keeps the filter, notifies subscribers and resets', () => {
    const store = createGalleryFilterStore();
    const seen: number[] = [];
    const unsubscribe = store.subscribe(() => seen.push(store.get().tags.length));
    expect(store.get()).toEqual(EMPTY_GALLERY_FILTER);
    store.set({ ...store.get(), tags: ['a', 'b'] });
    store.reset();
    unsubscribe();
    store.set({ ...store.get(), tags: ['c'] });
    expect(seen).toEqual([2, 0]);
    expect(store.get().tags).toEqual(['c']);
  });
});

describe('onThisDay.yearsAgo text', () => {
  it.each([
    [1, '1 year ago'],
    [2, '2 years ago'],
  ])('%i → %s', (years, label) => {
    expect(EN['onThisDay.yearsAgo'](years)).toBe(label);
  });
});
