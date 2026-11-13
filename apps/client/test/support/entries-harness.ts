import type { PublicConfigDto } from '@kodakiko/contract';
import type { Sodium } from '@kodakiko/core';
import type { Clock } from '../../src/adapters/clock';
import { createMemoryMediaToolkit } from '../../src/adapters/memory/memory-media-toolkit';
import type { EntryRow, RowStore } from '../../src/adapters/rowstore/rowstore';
import { createEventBus, type AppEvents } from '../../src/composition/events';
import { createCaptureTrayStore } from '../../src/features/capture/capture-tray-store';
import { STORAGE_KEYS } from '../../src/keys/storage-keys';
import {
  createEntriesService,
  type EntriesService,
} from '../../src/services/entries/entries-service';
import { createEntryKeys } from '../../src/services/keys/entry-keys';
import { createImportService } from '../../src/services/media/import-service';
import type { DeviceSettingsValue } from '../../src/services/settings/device-settings';
import { createAccountFixture, type AccountFixture } from './entry-fixtures';
import { createMemoryFileStore } from './memory-filestore';
import { createMemoryRowStore } from './memory-rowstore';

export const FIRST_NOW_ISO = '2026-11-12T10:00:00.000Z';
export const STEP_MS = 60_000;
const JPEG_HEADER = [0xff, 0xd8, 0xff, 0xda];

export function fakeJpeg(size: number, fill = 7): Uint8Array {
  const bytes = new Uint8Array(size).fill(fill);
  bytes.set(JPEG_HEADER);
  return bytes;
}

const SETTINGS: DeviceSettingsValue = {
  encryptThumbnails: true,
  originalCacheCapBytes: 0,
  largeUploadsOnWifiOnly: true,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: false,
  autoLockMinutes: 5,
};

const DEFAULT_MAX_MEDIA = 20;
const ENQUEUE_FAILURE = 'enqueue failed in the harness';

const CONFIG: PublicConfigDto = {
  apiVersion: 1,
  signupMode: 'invite',
  sharingEnabled: false,
  publicLinksEnabled: false,
  maxMediaPerEntry: DEFAULT_MAX_MEDIA,
  maxUploadBytes: 3_758_096_384,
  defaultQuotaBytes: 0,
  trashDays: 30,
  accountDeletionGraceDays: 7,
  serverVersion: 'test',
};

const CONFIG_NONE = {
  get: (): PublicConfigDto | null => null,
  refresh: async (): Promise<PublicConfigDto | null> => null,
  accept: (): void => undefined,
  subscribe: () => () => undefined,
};

export type HarnessHooks = {
  beforeKey: (() => Promise<void>) | null;
  failEnqueue: boolean;
  readonly inFlight: Set<string>;
  maxMediaPerEntry: number;
};

export type EntriesHarness = {
  readonly hooks: HarnessHooks;
  readonly entryKeys: ReturnType<typeof createEntryKeys>;
  readonly fixture: AccountFixture;
  readonly sodium: Sodium;
  readonly service: EntriesService;
  readonly tray: ReturnType<typeof createCaptureTrayStore>;
  readonly notified: { count: number };
  readonly lock: () => void;
  readonly changed: string[][];
  readonly clock: Clock & { advance: (milliseconds: number) => void };
  readonly importIntoTray: (bytes: Uint8Array, fileName?: string) => Promise<string>;
  readonly entryRow: (entryId: string) => Promise<EntryRow>;
  readonly outboxKinds: () => Promise<string[]>;
  readonly markSynced: (entryId: string) => Promise<void>;
  readonly createEntry: (
    mediaBytes: readonly Uint8Array[],
    input?: { note?: string; tags?: readonly string[] },
  ) => Promise<string>;
};

export async function createEntriesHarness(sodium: Sodium): Promise<EntriesHarness> {
  const rowStore = createMemoryRowStore();
  const fileStore = createMemoryFileStore();
  const fixture = await createAccountFixture(sodium, rowStore, fileStore);
  await rowStore.kvSetJson(STORAGE_KEYS.accountHomeCollectionId, fixture.homeCollectionId);
  let nowMs = Date.parse(FIRST_NOW_ISO);
  const clock = {
    now: () => nowMs,
    nowIso: () => new Date(nowMs).toISOString(),
    advance: (milliseconds: number) => {
      nowMs += milliseconds;
    },
  };
  let locked = false;
  const hooks: HarnessHooks = {
    beforeKey: null,
    failEnqueue: false,
    inFlight: new Set<string>(),
    maxMediaPerEntry: DEFAULT_MAX_MEDIA,
  };
  const entryKeys = createEntryKeys({
    sodium,
    rowStore,
    account: () => (locked ? null : fixture.keys),
  });
  const hookedKeys: ReturnType<typeof createEntryKeys> = {
    ...entryKeys,
    collectionKey: async (collectionId) => {
      await hooks.beforeKey?.();
      return entryKeys.collectionKey(collectionId);
    },
    entryKey: async (entryId) => {
      await hooks.beforeKey?.();
      return entryKeys.entryKey(entryId);
    },
  };
  const hookedRowStore: RowStore = {
    ...rowStore,
    transaction: (work) =>
      rowStore.transaction((tx) =>
        work({
          ...tx,
          outbox: {
            ...tx.outbox,
            enqueue: async (row) => {
              if (hooks.failEnqueue) throw new Error(ENQUEUE_FAILURE);
              return tx.outbox.enqueue(row);
            },
          },
        }),
      ),
  };
  const tray = createCaptureTrayStore({ fileStore });
  const events = createEventBus<AppEvents>();
  const changed: string[][] = [];
  events.on('entriesChanged', (ids) => changed.push([...ids]));
  const notified = { count: 0 };
  const importer = createImportService({
    sodium,
    fileStore,
    toolkit: createMemoryMediaToolkit(),
    clock,
    config: CONFIG_NONE,
    deviceSettings: {
      get: () => SETTINGS,
      loaded: () => true,
      update: async () => undefined,
      subscribe: () => () => undefined,
    },
    randomId: fixture.randomId,
  });
  const service = createEntriesService({
    sodium,
    rowStore: hookedRowStore,
    fileStore,
    entryKeys: hookedKeys,
    captureTray: tray,
    config: {
      ...CONFIG_NONE,
      get: () => ({ ...CONFIG, maxMediaPerEntry: hooks.maxMediaPerEntry }),
    },
    sync: {
      notifyLocalWrite: () => {
        notified.count += 1;
      },
      isEntryInFlight: (entryId) => hooks.inFlight.has(entryId),
    },
    events,
    clock,
    randomId: fixture.randomId,
    account: () => (locked ? null : { userId: fixture.userId }),
  });
  async function importIntoTray(bytes: Uint8Array, fileName = 'photo.jpg'): Promise<string> {
    const outcome = await importer.importOne(
      { origin: 'picker', handle: { blob: new Blob([bytes.slice()]) }, fileName, pickerExif: null },
      { used: tray.items.length, limit: 20 },
    );
    if (!outcome.ok) throw new Error(`import refused: ${outcome.reason}`);
    tray.add(outcome.media);
    return outcome.media.mediaId;
  }
  async function entryRow(entryId: string): Promise<EntryRow> {
    const row = await rowStore.transaction((tx) => tx.entries.get(entryId));
    if (row === null) throw new Error(`no entry ${entryId}`);
    return row;
  }
  async function markSynced(entryId: string): Promise<void> {
    await rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entryId);
      if (row === null) throw new Error(`no entry ${entryId}`);
      await tx.entries.put({
        ...row,
        localState: 'synced',
        baseRevision: 1,
        baseMetadataBlob: row.metadataBlob,
      });
      for (const item of await tx.outbox.all()) await tx.outbox.delete(item.id);
    });
  }
  async function createEntry(
    mediaBytes: readonly Uint8Array[],
    input: { note?: string; tags?: readonly string[] } = {},
  ): Promise<string> {
    for (const bytes of mediaBytes) await importIntoTray(bytes);
    return service.createFromTray({
      note: input.note ?? 'note',
      tags: input.tags ?? [],
      time: null,
    });
  }
  return {
    hooks,
    entryKeys,
    fixture,
    sodium,
    service,
    tray,
    notified,
    lock: () => {
      locked = true;
      entryKeys.clear();
    },
    changed,
    clock,
    importIntoTray,
    entryRow,
    outboxKinds: async () =>
      (await rowStore.transaction((tx) => tx.outbox.all())).map((row) => row.kind),
    markSynced,
    createEntry,
  };
}
