import type { DaySection } from '@kodakiko/core';
import { GRID_MIN_COLUMNS, GRID_TILE_MIN_PX } from '../../constants/gallery-constants';

export type GalleryItem =
  | { readonly type: 'onThisDay' }
  | { readonly type: 'header'; readonly localDate: string }
  | {
      readonly type: 'row';
      readonly localDate: string;
      readonly entryIds: readonly string[];
      readonly firstPosition: number;
    };

export function gridColumns(width: number): number {
  return Math.max(GRID_MIN_COLUMNS, Math.floor(width / GRID_TILE_MIN_PX));
}

export function buildGalleryItems(
  sections: readonly DaySection[],
  columns: number,
  showOnThisDay: boolean,
): readonly GalleryItem[] {
  const items: GalleryItem[] = showOnThisDay ? [{ type: 'onThisDay' }] : [];
  let position = 0;
  for (const section of sections) {
    items.push({ type: 'header', localDate: section.localDate });
    for (let start = 0; start < section.entryIds.length; start += columns) {
      const entryIds = section.entryIds.slice(start, start + columns);
      items.push({ type: 'row', localDate: section.localDate, entryIds, firstPosition: position });
      position += entryIds.length;
    }
  }
  return items;
}

export function displayOrder(sections: readonly DaySection[]): readonly string[] {
  return sections.flatMap((section) => [...section.entryIds]);
}
