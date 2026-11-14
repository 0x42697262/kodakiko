import { sortEntryIds, type AttributeFilters, type DaySection, type GallerySort, type IndexedEntry } from '@kodakiko/core';
import { GALLERY_COLUMNS_AUTO } from '../../constants/ui-settings-constants';
import type { DeviceSettingsValue } from '../../services/settings/device-settings';
import { gridColumns, UNGROUPED_DATE } from './gallery-items';

export { UNGROUPED_DATE };

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
