import type { FileStore } from '../../adapters/filestore/filestore';
import type { ObjectRow, RowStore } from '../../adapters/rowstore/rowstore';
import type { Downloader } from '../downloads/downloader';
import type { OriginalRef, OriginalSource } from './original-source';

export type CacheUsage = {
  readonly originalsBytes: number;
  readonly thumbnailsBytes: number;
  readonly pinnedBytes: number;
  readonly pendingUploadBytes: number;
};

export interface OriginalsService extends OriginalSource {
  readonly originalObjectId: (mediaId: string) => Promise<string | null>;
  readonly pinEntry: (entryId: string) => Promise<void>;
  readonly unpinEntry: (entryId: string) => Promise<void>;
  readonly isPinned: (entryId: string) => Promise<boolean>;
  readonly downloadPinned: () => Promise<void>;
  readonly enforceCap: () => Promise<void>;
  readonly usage: () => Promise<CacheUsage>;
  readonly clearOriginals: () => Promise<void>;
}

export type DownloadingOriginalSourceDeps = {
  readonly local: OriginalSource;
  readonly downloader: Downloader;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly settings: () => { readonly originalCacheCapBytes: number };
  readonly isWifi: () => Promise<boolean>;
  readonly now?: () => number;
  readonly localOnly?: () => boolean;
};

const NO_LIMIT = 0;

export function createDownloadingOriginalSource(deps: DownloadingOriginalSourceDeps): OriginalsService {
  const { local, downloader, rowStore, fileStore } = deps;
  const openCounts = new Map<string, number>();

  async function originalRow(mediaId: string): Promise<ObjectRow | null> {
    const rows = await rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    return rows.find((row) => row.variant === 'original') ?? null;
  }

  function track(objectId: string, ref: OriginalRef): OriginalRef {
    openCounts.set(objectId, (openCounts.get(objectId) ?? 0) + 1);
    let released = false;
    return {
      ...ref,
      release: async () => {
        if (released) return;
        released = true;
        const remaining = (openCounts.get(objectId) ?? 1) - 1;
        if (remaining <= 0) openCounts.delete(objectId);
        else openCounts.set(objectId, remaining);
        await ref.release();
      },
    };
  }

  async function open(entryId: string, mediaId: string): Promise<OriginalRef | 'missing'> {
    const object = await originalRow(mediaId);
    if (object === null) return 'missing';
    const found = await local.open(entryId, mediaId);
    if (found !== 'missing') return track(object.id, found);
    if (object.uploadState !== 'uploaded' || object.localState === 'unavailable') return 'missing';
    await downloader.fetchObject(object.id, 'opened');
    const downloaded = await local.open(entryId, mediaId);
    if (downloaded === 'missing') return 'missing';
    const tracked = track(object.id, downloaded);
    await enforceCap();
    return tracked;
  }

  async function pinnedEntryIds(): Promise<ReadonlySet<string>> {
    return new Set((await rowStore.transaction((tx) => tx.pins.all())).map((pin) => pin.entryId));
  }

  async function entryOf(objectRow: ObjectRow): Promise<string | null> {
    return (await rowStore.transaction((tx) => tx.media.get(objectRow.mediaId)))?.entryId ?? null;
  }

  async function evictable(row: ObjectRow, pinned: ReadonlySet<string>): Promise<boolean> {
    if (row.uploadState !== 'uploaded' || openCounts.has(row.id)) return false;
    const entryId = await entryOf(row);
    return entryId === null || !pinned.has(entryId);
  }

  async function evict(row: ObjectRow): Promise<void> {
    await fileStore.delete(row.id);
    await rowStore.transaction(async (tx) => {
      const current = await tx.objects.get(row.id);
      if (current !== null) await tx.objects.put({ ...current, localState: 'missing' });
    });
  }

  async function enforceCap(): Promise<void> {
    const cap = deps.settings().originalCacheCapBytes;
    if (cap === NO_LIMIT || deps.localOnly?.() === true) return;
    const present = await rowStore.transaction((tx) => tx.objects.presentOriginalsByAccess());
    let total = present.reduce((sum, row) => sum + row.sizeBytes, 0);
    const pinned = await pinnedEntryIds();
    for (const row of present) {
      if (total <= cap) break;
      if (!(await evictable(row, pinned))) continue;
      await evict(row);
      total -= row.sizeBytes;
    }
  }

  async function clearOriginals(): Promise<void> {
    if (deps.localOnly?.() === true) return;
    const present = await rowStore.transaction((tx) => tx.objects.presentOriginalsByAccess());
    const pinned = await pinnedEntryIds();
    for (const row of present) if (await evictable(row, pinned)) await evict(row);
  }

  async function entryOriginals(entryId: string): Promise<readonly ObjectRow[]> {
    return rowStore.transaction(async (tx) => {
      const rows: ObjectRow[] = [];
      for (const media of await tx.media.byEntry(entryId)) {
        rows.push(...(await tx.objects.byMedia(media.id)).filter((row) => row.variant === 'original'));
      }
      return rows;
    });
  }

  async function downloadEntry(entryId: string): Promise<void> {
    const rows = await entryOriginals(entryId);
    await Promise.all(
      rows
        .filter((row) => row.uploadState === 'uploaded' && row.localState === 'missing')
        .map((row) => downloader.fetchObject(row.id, 'background').catch(() => undefined)),
    );
  }

  async function downloadPinned(): Promise<void> {
    if (!(await deps.isWifi())) return;
    for (const entryId of await pinnedEntryIds()) await downloadEntry(entryId);
  }

  async function usage(): Promise<CacheUsage> {
    const sizes = await fileStore.listSizes();
    const pinned = await pinnedEntryIds();
    const rows = await rowStore.transaction(async (tx) => {
      const all: { readonly row: ObjectRow; readonly entryId: string }[] = [];
      for (const media of await tx.media.all()) {
        for (const row of await tx.objects.byMedia(media.id)) all.push({ row, entryId: media.entryId });
      }
      return all;
    });
    let originalsBytes = 0;
    let thumbnailsBytes = 0;
    let pinnedBytes = 0;
    let pendingUploadBytes = 0;
    for (const { row, entryId } of rows) {
      const size = sizes.get(row.id) ?? 0;
      if (row.uploadState !== 'uploaded') pendingUploadBytes += size;
      if (row.variant === 'thumbnail') thumbnailsBytes += size;
      else originalsBytes += size;
      if (row.variant === 'original' && pinned.has(entryId)) pinnedBytes += size;
    }
    return { originalsBytes, thumbnailsBytes, pinnedBytes, pendingUploadBytes };
  }

  return {
    open,
    clearAll: async () => {
      openCounts.clear();
      await local.clearAll();
    },
    originalObjectId: async (mediaId) => (await originalRow(mediaId))?.id ?? null,
    pinEntry: async (entryId) => {
      await rowStore.transaction((tx) => tx.pins.put({ entryId, pinnedAt: (deps.now ?? Date.now)() }));
      if (await deps.isWifi()) downloadEntry(entryId).catch(() => undefined);
    },
    unpinEntry: (entryId) => rowStore.transaction((tx) => tx.pins.delete(entryId)),
    isPinned: async (entryId) => (await rowStore.transaction((tx) => tx.pins.get(entryId))) !== null,
    downloadPinned,
    enforceCap,
    usage,
    clearOriginals,
  };
}
