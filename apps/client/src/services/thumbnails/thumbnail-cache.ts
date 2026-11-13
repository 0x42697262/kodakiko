import { wipe, type Sodium } from '@kodakiko/core';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { ObjectRow, RowStore } from '../../adapters/rowstore/rowstore';
import { createObservable, type Observable } from '../../composition/account-state';
import type { Downloader } from '../downloads/downloader';
import type { EntryKeys } from '../keys/entry-keys';
import { decryptWhole, type ThumbnailRef, type ThumbnailSource } from './thumbnail-source';

export type ThumbnailModeSwitch = { readonly running: boolean; readonly done: number; readonly total: number };

export interface ThumbnailCacheService extends ThumbnailSource {
  readonly afterUpload: (objectId: string) => Promise<void>;
  readonly prefetchAll: () => Promise<void>;
  readonly switchMode: (encrypt: boolean) => Promise<void>;
  readonly modeSwitch: Observable<ThumbnailModeSwitch>;
}

export type CachingThumbnailSourceDeps = {
  readonly local: ThumbnailSource;
  readonly downloader: Downloader;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly sodium: Sodium;
  readonly settings: () => { readonly encryptThumbnails: boolean };
};

type ThumbnailTarget = { readonly entryId: string; readonly object: ObjectRow };

const IDLE: ThumbnailModeSwitch = Object.freeze({ running: false, done: 0, total: 0 });

export function createCachingThumbnailSource(deps: CachingThumbnailSourceDeps): ThumbnailCacheService {
  const { local, downloader, rowStore, fileStore } = deps;
  const inFlight = new Map<string, Promise<void>>();
  const modeSwitch = createObservable<ThumbnailModeSwitch>(IDLE);

  async function thumbnailRow(mediaId: string): Promise<ObjectRow | null> {
    const rows = await rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    return rows.find((row) => row.variant === 'thumbnail') ?? null;
  }

  async function markMissing(objectId: string): Promise<void> {
    await rowStore.transaction(async (tx) => {
      const row = await tx.objects.get(objectId);
      if (row !== null) await tx.objects.put({ ...row, localState: 'missing' });
    });
  }

  async function toPlain(entryId: string, object: ObjectRow): Promise<void> {
    const ciphertext = await fileStore.readRange(object.id, 0, object.sizeBytes);
    const key = await deps.entryKeys.variantKey(entryId, object.mediaId, 'thumbnail');
    try {
      await fileStore.writePlainThumbnail(object.mediaId, decryptWhole(deps.sodium, key, ciphertext, object.plaintextSize));
    } finally {
      wipe(key);
    }
    await fileStore.delete(object.id);
    await markMissing(object.id);
  }

  async function settle(entryId: string, objectId: string): Promise<void> {
    if (deps.settings().encryptThumbnails) return;
    const row = await rowStore.transaction((tx) => tx.objects.get(objectId));
    if (row !== null && row.localState === 'present') await toPlain(entryId, row);
  }

  function fetchAndSettle(target: ThumbnailTarget, priority: 'visible' | 'background'): Promise<void> {
    const key = target.object.mediaId;
    const existing = inFlight.get(key);
    if (existing !== undefined) return existing;
    const work = downloader
      .fetchObject(target.object.id, priority)
      .then(() => settle(target.entryId, target.object.id))
      .finally(() => inFlight.delete(key));
    inFlight.set(key, work);
    return work;
  }

  function downloadable(object: ObjectRow | null): object is ObjectRow {
    return object !== null && object.uploadState === 'uploaded' && object.localState === 'missing';
  }

  async function get(entryId: string, mediaId: string): Promise<ThumbnailRef | null> {
    const found = await local.get(entryId, mediaId);
    if (found !== null) return found;
    const object = await thumbnailRow(mediaId);
    if (!downloadable(object)) return null;
    try {
      await fetchAndSettle({ entryId, object }, 'visible');
    } catch {
      return null;
    }
    return local.get(entryId, mediaId);
  }

  async function targets(): Promise<readonly ThumbnailTarget[]> {
    return rowStore.transaction(async (tx) => {
      const found: ThumbnailTarget[] = [];
      for (const entry of await tx.entries.all()) {
        if (entry.trashedAt !== null) continue;
        for (const media of await tx.media.byEntry(entry.id)) {
          const object = (await tx.objects.byMedia(media.id)).find((row) => row.variant === 'thumbnail');
          if (object !== undefined) found.push({ entryId: entry.id, object });
        }
      }
      return found;
    });
  }

  async function prefetchAll(): Promise<void> {
    const work = (await targets())
      .filter(({ object }) => downloadable(object) && fileStore.plainThumbnailUri(object.mediaId) === null)
      .map((target) => fetchAndSettle(target, 'background').catch(() => undefined));
    await Promise.all(work);
  }

  async function afterUpload(objectId: string): Promise<void> {
    if (deps.settings().encryptThumbnails) return;
    const object = await rowStore.transaction((tx) => tx.objects.get(objectId));
    if (object === null || object.localState !== 'present') return;
    if (fileStore.plainThumbnailUri(object.mediaId) === null) {
      const media = await rowStore.transaction((tx) => tx.media.get(object.mediaId));
      if (media === null) return;
      await toPlain(media.entryId, object);
      return;
    }
    await fileStore.delete(objectId);
    await markMissing(objectId);
  }

  async function switchMode(encrypt: boolean): Promise<void> {
    const all = await targets();
    const work = encrypt
      ? all.filter(({ object }) => fileStore.plainThumbnailUri(object.mediaId) !== null)
      : all.filter(({ object }) => object.localState === 'present');
    modeSwitch.set({ running: true, done: 0, total: work.length });
    local.clear();
    let done = 0;
    for (const target of work) {
      try {
        if (encrypt) {
          await fileStore.deletePlainThumbnail(target.object.mediaId);
          await markMissing(target.object.id);
        } else {
          await toPlain(target.entryId, target.object);
        }
      } catch {
        continue;
      } finally {
        done += 1;
        modeSwitch.set({ running: true, done, total: work.length });
      }
    }
    local.clear();
    if (encrypt) await prefetchAll();
    modeSwitch.set(IDLE);
  }

  return {
    get,
    clear: () => {
      local.clear();
      inFlight.clear();
    },
    afterUpload,
    prefetchAll,
    switchMode,
    modeSwitch,
  };
}
