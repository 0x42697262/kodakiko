import { sortEntryIds, type AttributeFilters, type DaySection, type GallerySort, type IndexedEntry } from '@kodakiko/core';
import { GALLERY_COLUMNS_AUTO } from '../../constants/ui-settings-constants';
import type { DeviceSettingsValue } from '../../services/settings/device-settings';
import { gridColumns, UNGROUPED_DATE } from './gallery-items';

export { UNGROUPED_DATE };

export type GroupBy = 'day' | 'month' | 'year' | 'tag' | 'none';
export const MONTH_GROUP_PREFIX = 'm:';
export const YEAR_GROUP_PREFIX = 'y:';
export const TAG_GROUP_PREFIX = 't:';
export const UNTAGGED_GROUP = 'n:';
const MONTH_CHARS = 7;
const BEFORE = -1;
const AFTER = 1;
const YEAR_CHARS = 4;

function bucket(ids: readonly string[], keyOf: (id: string) => readonly string[]): DaySection[] {
  const groups = new Map<string, string[]>();
  for (const id of ids) {
    for (const key of keyOf(id)) {
      const list = groups.get(key) ?? [];
      list.push(id);
      groups.set(key, list);
    }
  }
  return [...groups.entries()].map(([localDate, entryIds]) => ({ localDate, entryIds }));
}

export function groupSections(
  sections: readonly DaySection[],
  get: (id: string) => IndexedEntry | null,
  sort: GallerySort,
  groupBy: GroupBy,
  seed: number,
): readonly DaySection[] {
  if (groupBy === 'day') return arrangeSections(sections, get, sort, true, seed);
  const flat = arrangeSections(sections, get, sort, false, seed);
  const ids = flat.flatMap((section) => section.entryIds);
  if (groupBy === 'none' || ids.length === 0) return flat;
  if (groupBy === 'tag') {
    const groups = bucket(ids, (id) => {
      const tags = get(id)?.tags ?? [];
      return tags.length === 0 ? [UNTAGGED_GROUP] : tags.map((tag) => `${TAG_GROUP_PREFIX}${tag}`);
    });
    return groups.sort((left, right) => {
      if (left.localDate === UNTAGGED_GROUP) return AFTER;
      if (right.localDate === UNTAGGED_GROUP) return BEFORE;
      return left.localDate.localeCompare(right.localDate);
    });
  }
  const prefix = groupBy === 'month' ? MONTH_GROUP_PREFIX : YEAR_GROUP_PREFIX;
  const length = groupBy === 'month' ? MONTH_CHARS : YEAR_CHARS;
  const groups = bucket(ids, (id) => [`${prefix}${(get(id)?.localDate ?? '').slice(0, length)}`]);
  if (sort.key !== 'takenAt') return groups;
  return groups.sort((left, right) => (left.localDate < right.localDate ? AFTER : BEFORE) * (sort.descending ? AFTER : BEFORE));
}

export function arrangeSections(
  sections: readonly DaySection[],
  get: (id: string) => IndexedEntry | null,
  sort: GallerySort,
  groupByDay: boolean,
  seed: number,
): readonly DaySection[] {
  if (groupByDay && sort.key === 'takenAt') {
    if (sort.descending) return sections;
    return [...sections].reverse().map((section) => ({ localDate: section.localDate, entryIds: [...section.entryIds].reverse() }));
  }
  const ids = sections.flatMap((section) => section.entryIds);
  if (ids.length === 0) return [];
  return [{ localDate: UNGROUPED_DATE, entryIds: sortEntryIds(ids, get, sort, seed) }];
}

export function galleryAttributes(filters: DeviceSettingsValue['galleryFilters'], hideShared: boolean): AttributeFilters {
  return {
    video: filters.video,
    note: filters.note,
    multiple: filters.multiple,
    shared: hideShared ? 'exclude' : filters.shared,
    pending: filters.pending,
    favorite: filters.favorite,
  };
}

export function columnsFor(width: number, height: number, portrait: number, landscape: number): number {
  const chosen = width > height ? landscape : portrait;
  return chosen === GALLERY_COLUMNS_AUTO ? gridColumns(width) : chosen;
}
