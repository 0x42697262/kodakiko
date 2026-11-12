import { deleteDB, openDB, type IDBPDatabase, type IDBPObjectStore } from 'idb';
import type { Sodium } from '@kodakiko/core';
import type { z } from 'zod';
import { hashAccountKey } from '../account-key';
import { rowStoreDatabaseName } from '../../keys/paths';
import type { StorageKey } from '../../keys/storage-keys';
import { EN } from '../../text/en';
import { INDEXEDDB_MIGRATIONS } from './migrations';
import {
  INDEXES,
  OBJECT_ORIGINAL_VARIANT,
  OBJECT_PRESENT_STATE,
  OUTBOX_QUEUED_STATE,
  OUTBOX_STATES,
  ROWSTORE_SCHEMA_VERSION,
  TABLES,
} from './schema';
import { decodeJson, emptyOutboxCounts, encodeJson } from './rowstore-codec';
import type {
  CollectionRow,
  EntryRow,
  MediaRow,
  MembershipRow,
  ObjectRow,
  OutboxRow,
  PinRow,
  RowStore,
  RowStoreTx,
  TableOps,
} from './rowstore';

type Store = IDBPObjectStore<unknown, string[], string, 'readwrite'>;
type Transaction = { readonly objectStore: (name: string) => Store };

const ALL_STORES: string[] = Object.values(TABLES);

function stateRange(state: string): IDBKeyRange {
  return IDBKeyRange.bound([state, -Infinity], [state, Infinity]);
}

function abortQuietly(transaction: { readonly abort: () => void }): void {
  try {
    transaction.abort();
  } catch {
    return;
  }
}

function tableOps<Row, Key>(store: Store): TableOps<Row, Key> {
  return {
    get: async (key) => ((await store.get(key as IDBValidKey)) as Row | undefined) ?? null,
    put: async (row) => {
      await store.put(row);
    },
    delete: async (key) => {
      await store.delete(key as IDBValidKey);
    },
    all: async () => (await store.getAll()) as Row[],
  };
}

function outboxOps(store: Store): RowStoreTx['outbox'] {
  const dueIndex = () => store.index(INDEXES.outboxByStateDue);
  return {
    enqueue: async (row) => Number(await store.add(row)),
    due: async (now, limit) =>
      (await dueIndex().getAll(
        IDBKeyRange.bound([OUTBOX_QUEUED_STATE, -Infinity], [OUTBOX_QUEUED_STATE, now]),
        limit,
      )) as OutboxRow[],
    update: async (row) => {
      await store.put(row);
    },
    delete: async (id) => {
      await store.delete(id);
    },
    countByState: async () => {
      const counts = emptyOutboxCounts();
      for (const state of OUTBOX_STATES) counts[state] = await dueIndex().count(stateRange(state));
      return counts;
    },
    all: async () => (await store.getAll()) as OutboxRow[],
  };
}

function createTx(transaction: Transaction): RowStoreTx {
  const store = (name: string): Store => transaction.objectStore(name);
  return {
    collections: tableOps<CollectionRow, string>(store(TABLES.collections)),
    entries: {
      ...tableOps<EntryRow, string>(store(TABLES.entries)),
      byLocalState: async (states) => {
        const rows: EntryRow[] = [];
        for (const state of states) {
          const matches = await store(TABLES.entries)
            .index(INDEXES.entriesByLocalState)
            .getAll(state);
          rows.push(...(matches as EntryRow[]));
        }
        return rows;
      },
    },
    memberships: {
      ...tableOps<MembershipRow, readonly [string, string]>(store(TABLES.memberships)),
      byEntry: async (entryId) =>
        (await store(TABLES.memberships)
          .index(INDEXES.membershipsByEntry)
          .getAll(entryId)) as MembershipRow[],
      byCollection: async (collectionId) =>
        (await store(TABLES.memberships).getAll(
          IDBKeyRange.bound([collectionId], [collectionId, []]),
        )) as MembershipRow[],
    },
    media: {
      ...tableOps<MediaRow, string>(store(TABLES.media)),
      byEntry: async (entryId) =>
        (await store(TABLES.media).index(INDEXES.mediaByEntry).getAll(entryId)) as MediaRow[],
    },
    objects: {
      ...tableOps<ObjectRow, string>(store(TABLES.objects)),
      byMedia: async (mediaId) =>
        (await store(TABLES.objects).index(INDEXES.objectsByMedia).getAll(mediaId)) as ObjectRow[],
      presentOriginalsByAccess: async () => {
        const present = (await store(TABLES.objects)
          .index(INDEXES.objectsByLocalStateAccess)
          .getAll(stateRange(OBJECT_PRESENT_STATE))) as ObjectRow[];
        return present.filter((row) => row.variant === OBJECT_ORIGINAL_VARIANT);
      },
    },
    outbox: outboxOps(store(TABLES.outbox)),
    pins: tableOps<PinRow, string>(store(TABLES.pins)),
  };
}

export function createRowStore(deps: { readonly sodium: Sodium }): RowStore {
  let database: IDBPDatabase | null = null;

  function requireDatabase(): IDBPDatabase {
    if (database === null) throw new Error(EN['rowstore.notOpen']);
    return database;
  }

  async function close(): Promise<void> {
    database?.close();
    database = null;
  }

  return {
    open: async (accountKey) => {
      await close();
      database = await openDB(
        rowStoreDatabaseName(hashAccountKey(deps.sodium, accountKey)),
        ROWSTORE_SCHEMA_VERSION,
        {
          blocking: () => {
            database?.close();
            database = null;
          },
          terminated: () => {
            database = null;
          },
          upgrade: (upgrading, oldVersion) => {
            for (let step = oldVersion; step < INDEXEDDB_MIGRATIONS.length; step += 1) {
              INDEXEDDB_MIGRATIONS[step]?.(upgrading);
            }
          },
        },
      );
    },
    close,
    destroy: async (accountKey) => {
      await close();
      await new Promise<void>((resolve, reject) => {
        deleteDB(rowStoreDatabaseName(hashAccountKey(deps.sodium, accountKey)), {
          blocked: () => reject(new Error(EN['rowstore.deleteBlocked'])),
        }).then(() => resolve(), reject);
      });
    },
    transaction: async <T>(work: (tx: RowStoreTx) => Promise<T>): Promise<T> => {
      const transaction = requireDatabase().transaction(ALL_STORES, 'readwrite');
      const finished = transaction.done;
      finished.catch(() => undefined);
      try {
        const result = await work(createTx(transaction as unknown as Transaction));
        await finished;
        return result;
      } catch (error) {
        abortQuietly(transaction);
        await finished.catch(() => undefined);
        throw error;
      }
    },
    kvGet: async (key: StorageKey) =>
      ((await requireDatabase().get(TABLES.kv, key)) as Uint8Array | undefined) ?? null,
    kvGetJson: async <T>(key: StorageKey, schema: z.ZodType<T>) => {
      const value = (await requireDatabase().get(TABLES.kv, key)) as Uint8Array | undefined;
      return value === undefined ? null : decodeJson(value, schema);
    },
    kvSet: async (key, value) => {
      await requireDatabase().put(TABLES.kv, value, key);
    },
    kvSetJson: async (key, value) => {
      await requireDatabase().put(TABLES.kv, encodeJson(value), key);
    },
    kvDelete: async (key) => {
      await requireDatabase().delete(TABLES.kv, key);
    },
  };
}
