import { localPartsToUtc, type GalleryFilter } from '@kodakiko/core';

export type DateRangeProblem = 'filter.startAfterEnd' | 'filter.invalidDate';

const MIDNIGHT = '00:00';

export const EMPTY_GALLERY_FILTER: GalleryFilter = Object.freeze({
  scope: Object.freeze({ kind: 'home' as const }),
  view: 'journal' as const,
  tags: Object.freeze([]) as readonly string[],
  fromDate: null,
  toDate: null,
  text: '',
});

export function isFilterActive(filter: GalleryFilter): boolean {
  return (
    filter.tags.length > 0 ||
    filter.fromDate !== null ||
    filter.toDate !== null ||
    filter.text.trim() !== ''
  );
}

function validDate(value: string): boolean {
  return value === '' || localPartsToUtc(value, MIDNIGHT, 0) !== null;
}

export function validateDateRange(from: string, to: string): DateRangeProblem | null {
  if (!validDate(from) || !validDate(to)) return 'filter.invalidDate';
  if (from !== '' && to !== '' && from > to) return 'filter.startAfterEnd';
  return null;
}
