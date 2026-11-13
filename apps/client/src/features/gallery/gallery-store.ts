import type { GalleryFilter } from '@kodakiko/core';
import { EMPTY_GALLERY_FILTER } from './filter-model';

export type GalleryFilterStore = {
  readonly get: () => GalleryFilter;
  readonly set: (filter: GalleryFilter) => void;
  readonly reset: () => void;
  readonly subscribe: (listener: () => void) => () => void;
};

export function createGalleryFilterStore(): GalleryFilterStore {
  let current = EMPTY_GALLERY_FILTER;
  const listeners = new Set<() => void>();
  function set(filter: GalleryFilter): void {
    current = filter;
    for (const listener of [...listeners]) listener();
  }
  return {
    get: () => current,
    set,
    reset: () => set(EMPTY_GALLERY_FILTER),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
