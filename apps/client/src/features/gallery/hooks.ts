import { deviceOffsetMinutes, localDateOf, type DaySection, type GalleryFilter, type GalleryIndex, type IndexedEntry, type OnThisDayGroup } from '@kodakiko/core';
import { useMemo, useSyncExternalStore } from 'react';
import { useAppContext } from '../../composition/app-context';
import type { IndexStatus } from '../../services/index/index-service';

const NO_INDEX_ID = 0;
const indexIds = new WeakMap<GalleryIndex, number>();
let nextIndexId = 0;

function identityOf(index: GalleryIndex | null): number {
  if (index === null) return NO_INDEX_ID;
  let id = indexIds.get(index);
  if (id === undefined) {
    nextIndexId += 1;
    id = nextIndexId;
    indexIds.set(index, id);
  }
  return id;
}

export function useIndexStatus(): IndexStatus {
  const { services } = useAppContext();
  return useSyncExternalStore(services.index.subscribe, services.index.status);
}

export function useIndexVersion(): string {
  const { services } = useAppContext();
  const snapshot = () => {
    const index = services.index.index();
    return `${identityOf(index)}:${index?.version() ?? 0}`;
  };
  return useSyncExternalStore(services.index.subscribe, snapshot);
}

export function useGallerySections(filter: GalleryFilter): readonly DaySection[] {
  const { services } = useAppContext();
  const version = useIndexVersion();
  return useMemo(
    () => services.index.index()?.query(filter) ?? [],
    [services.index, version, filter],
  );
}

export function useIndexedEntry(entryId: string): IndexedEntry | null {
  const { services } = useAppContext();
  const version = useIndexVersion();
  return useMemo(() => services.index.index()?.get(entryId) ?? null, [services.index, version, entryId]);
}

export function todayLocalDate(nowMs: number): string {
  const now = new Date(nowMs);
  return localDateOf(now.toISOString(), deviceOffsetMinutes(now));
}

export function useOnThisDay(): readonly OnThisDayGroup[] {
  const { services, clock } = useAppContext();
  const version = useIndexVersion();
  const today = todayLocalDate(clock.now());
  return useMemo(
    () => services.index.index()?.onThisDay(today, { kind: 'home' }) ?? [],
    [services.index, version, today],
  );
}

export function useGalleryFilter(): GalleryFilter {
  const { services } = useAppContext();
  return useSyncExternalStore(services.galleryFilter.subscribe, services.galleryFilter.get);
}
