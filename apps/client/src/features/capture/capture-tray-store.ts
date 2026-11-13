import { wipe, type GeoPoint } from '@kodakiko/core';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { ImportedMedia } from '../../services/media/import-service';

export type DeviceLocation = {
  readonly point: GeoPoint;
  readonly accuracyMeters: number | null;
};

export type CaptureTrayStore = {
  readonly items: readonly ImportedMedia[];
  readonly deviceLocation: DeviceLocation | null;
  readonly generation: number;
  readonly addImported: (item: ImportedMedia, startedGeneration: number) => Promise<boolean>;
  readonly add: (item: ImportedMedia) => void;
  readonly remove: (mediaId: string) => Promise<void>;
  readonly reorder: (from: number, to: number) => void;
  readonly take: () => readonly ImportedMedia[];
  readonly clear: (deleteFiles: boolean) => Promise<void>;
  readonly setDeviceLocation: (location: DeviceLocation | null) => void;
  readonly subscribe: (listener: () => void) => () => void;
};

export type CaptureTrayDeps = {
  readonly fileStore: FileStore;
  readonly isUnlocked?: () => boolean;
};

export function createCaptureTrayStore(deps: CaptureTrayDeps): CaptureTrayStore {
  let items: readonly ImportedMedia[] = [];
  let deviceLocation: DeviceLocation | null = null;
  let generation = 0;
  const isUnlocked = deps.isUnlocked ?? (() => true);
  const listeners = new Set<() => void>();

  function notify(): void {
    for (const listener of [...listeners]) listener();
  }

  async function deleteFiles(item: ImportedMedia): Promise<void> {
    await Promise.allSettled([
      deps.fileStore.delete(item.originalObjectId),
      deps.fileStore.delete(item.thumbnailObjectId),
      deps.fileStore.deletePlainThumbnail(item.mediaId),
    ]);
    wipe(item.mediaKey);
  }

  return {
    get items() {
      return items;
    },
    get deviceLocation() {
      return deviceLocation;
    },
    get generation() {
      return generation;
    },
    addImported: async (item, startedGeneration) => {
      if (startedGeneration !== generation || !isUnlocked()) {
        await deleteFiles(item);
        return false;
      }
      items = [...items, item];
      notify();
      return true;
    },
    add: (item) => {
      items = [...items, item];
      notify();
    },
    remove: async (mediaId) => {
      const removed = items.find((item) => item.mediaId === mediaId);
      if (removed === undefined) return;
      items = items.filter((item) => item.mediaId !== mediaId);
      notify();
      await deleteFiles(removed);
    },
    reorder: (from, to) => {
      const moved = items[from];
      if (moved === undefined || to < 0 || to >= items.length || from === to) return;
      const next = items.filter((_, index) => index !== from);
      next.splice(to, 0, moved);
      items = next;
      notify();
    },
    take: () => {
      const taken = items;
      items = [];
      notify();
      return taken;
    },
    clear: async (shouldDeleteFiles) => {
      const cleared = items;
      generation += 1;
      items = [];
      deviceLocation = null;
      notify();
      if (shouldDeleteFiles) await Promise.all(cleared.map(deleteFiles));
    },
    setDeviceLocation: (location) => {
      deviceLocation = location;
      notify();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
