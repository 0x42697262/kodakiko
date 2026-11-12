import {
  deleteDatabaseAsync,
  openDatabaseAsync,
  type SQLiteBindValue,
  type SQLiteDatabase,
} from 'expo-sqlite';
import { decodeJson, emptyOutboxCounts, encodeJson } from './rowstore-codec';
import type { Sodium } from '@kodakiko/core';
import type { z } from 'zod';
import { hashAccountKey } from '../account-key';
import { rowStoreFileName } from '../../keys/paths';
import type { StorageKey } from '../../keys/storage-keys';
import { EN } from '../../text/en';
import { SQLITE_MIGRATIONS } from './migrations';
import {
  OBJECT_ORIGINAL_VARIANT,
  OBJECT_PRESENT_STATE,
  OUTBOX_QUEUED_STATE,
  ROWSTORE_SCHEMA_VERSION,
  TABLES,
} from './schema';
import type {
  CollectionRow,
  EntryLocalState,
  EntryRow,
  MediaRow,
  MembershipRow,
  NewOutboxRow,
  ObjectRow,
  OutboxRow,
  OutboxState,
  PinRow,
  RowStore,
  RowStoreTx,
  TableOps,
} from './rowstore';

type Executor = Pick<SQLiteDatabase, 'runAsync' | 'getFirstAsync' | 'getAllAsync'>;
type KeyValue = string | readonly string[];

type TableSpec<Row> = {
  readonly table: string;
  readonly columns: readonly (keyof Row & string)[];
  readonly keyColumns: readonly (keyof Row & string)[];
};

const COLLECTION_SPEC: TableSpec<CollectionRow> = {
  table: TABLES.collections,
  columns: [
    'id',
    'kind',
    'ownerId',
    'ownerUsername',
    'role',
    'keyMaterial',
    'metadataBlob',
    'metaUpdatedAt',
    'serverLastSeq',
    'cursorSeq',
  ],
  keyColumns: ['id'],
};

const ENTRY_SPEC: TableSpec<EntryRow> = {
  table: TABLES.entries,
  columns: [
    'id',
    'ownerId',
    'purpose',
    'revision',
    'metadataBlob',
    'baseMetadataBlob',
    'baseRevision',
    'serverPendingBlob',
    'serverPendingRevision',
    'trashedAt',
    'createdAt',
    'updatedAt',
    'localState',
  ],
  keyColumns: ['id'],
};

const MEMBERSHIP_SPEC: TableSpec<MembershipRow> = {
  table: TABLES.memberships,
  columns: ['collectionId', 'entryId', 'entryKeyWrap', 'seq'],
  keyColumns: ['collectionId', 'entryId'],
};

const MEDIA_SPEC: TableSpec<MediaRow> = {
  table: TABLES.media,
  columns: ['id', 'entryId', 'mediaKeyWrap'],
  keyColumns: ['id'],
};

const OBJECT_SPEC: TableSpec<ObjectRow> = {
  table: TABLES.objects,
  columns: [
    'id',
    'mediaId',
    'variant',
    'sizeBytes',
    'plaintextSize',
    'localState',
    'uploadState',
    'uploadJobId',
    'lastAccessAt',
  ],
  keyColumns: ['id'],
};

const PIN_SPEC: TableSpec<PinRow> = {
  table: TABLES.pins,
  columns: ['entryId', 'pinnedAt'],
  keyColumns: ['entryId'],
};

const OUTBOX_COLUMNS = [
  'kind',
  'payload',
  'state',
  'createdAt',
  'attempts',
  'nextAttemptAt',
  'lastErrorCode',
] as const;

function keyParams(key: KeyValue): SQLiteBindValue[] {
  return typeof key === 'string' ? [key] : [...key];
}

function placeholders(count: number): string {
  return Array.from({ length: count }, () => '?').join(', ');
}

function whereKey<Row>(spec: TableSpec<Row>): string {
  return spec.keyColumns.map((column) => `${column} = ?`).join(' AND ');
}

function rowParams<Row>(spec: TableSpec<Row>, row: Row): SQLiteBindValue[] {
  return spec.columns.map((column) => row[column] as SQLiteBindValue);
}

function tableOps<Row, Key extends KeyValue>(
  executor: Executor,
  spec: TableSpec<Row>,
): TableOps<Row, Key> {
  return {
    get: (key) =>
      executor.getFirstAsync<Row>(
        `SELECT * FROM ${spec.table} WHERE ${whereKey(spec)}`,
        keyParams(key),
      ),
    put: async (row) => {
      await executor.runAsync(
        `INSERT OR REPLACE INTO ${spec.table} (${spec.columns.join(', ')}) VALUES (${placeholders(spec.columns.length)})`,
        rowParams(spec, row),
      );
    },
    delete: async (key) => {
      await executor.runAsync(`DELETE FROM ${spec.table} WHERE ${whereKey(spec)}`, keyParams(key));
    },
    all: () => executor.getAllAsync<Row>(`SELECT * FROM ${spec.table}`, []),
  };
}

type OutboxDbRow = Omit<OutboxRow, 'payload'> & { readonly payload: string };

function toOutboxRow(row: OutboxDbRow): OutboxRow {
  return { ...row, payload: JSON.parse(row.payload) as unknown };
}

function outboxParams(row: NewOutboxRow): SQLiteBindValue[] {
  return OUTBOX_COLUMNS.map((column) =>
    column === 'payload' ? JSON.stringify(row.payload) : (row[column] as SQLiteBindValue),
  );
}

function outboxOps(executor: Executor): RowStoreTx['outbox'] {
  return {
    enqueue: async (row) => {
      const result = await executor.runAsync(
        `INSERT INTO ${TABLES.outbox} (${OUTBOX_COLUMNS.join(', ')}) VALUES (${placeholders(OUTBOX_COLUMNS.length)})`,
        outboxParams(row),
      );
      return result.lastInsertRowId;
    },
    due: async (now, limit) => {
      const rows = await executor.getAllAsync<OutboxDbRow>(
        `SELECT * FROM ${TABLES.outbox} WHERE state = ? AND nextAttemptAt <= ? ORDER BY nextAttemptAt ASC, id ASC LIMIT ?`,
        [OUTBOX_QUEUED_STATE, now, limit],
      );
      return rows.map(toOutboxRow);
    },
    update: async (row) => {
      await executor.runAsync(
        `UPDATE ${TABLES.outbox} SET ${OUTBOX_COLUMNS.map((column) => `${column} = ?`).join(', ')} WHERE id = ?`,
        [...outboxParams(row), row.id],
      );
    },
    delete: async (id) => {
      await executor.runAsync(`DELETE FROM ${TABLES.outbox} WHERE id = ?`, [id]);
    },
    countByState: async () => {
      const rows = await executor.getAllAsync<{ state: OutboxState; count: number }>(
        `SELECT state, COUNT(*) AS count FROM ${TABLES.outbox} GROUP BY state`,
        [],
      );
      const counts = emptyOutboxCounts();
      for (const row of rows) counts[row.state] = row.count;
      return counts;
    },
    all: async () => {
      const rows = await executor.getAllAsync<OutboxDbRow>(
        `SELECT * FROM ${TABLES.outbox} ORDER BY id ASC`,
        [],
      );
      return rows.map(toOutboxRow);
    },
  };
}

function createTx(executor: Executor): RowStoreTx {
  return {
    collections: tableOps<CollectionRow, string>(executor, COLLECTION_SPEC),
    entries: {
      ...tableOps<EntryRow, string>(executor, ENTRY_SPEC),
      byLocalState: async (states: readonly EntryLocalState[]) =>
        states.length === 0
          ? []
          : executor.getAllAsync<EntryRow>(
              `SELECT * FROM ${TABLES.entries} WHERE localState IN (${placeholders(states.length)})`,
              [...states],
            ),
    },
    memberships: {
      ...tableOps<MembershipRow, readonly [string, string]>(executor, MEMBERSHIP_SPEC),
      byEntry: (entryId) =>
        executor.getAllAsync<MembershipRow>(
          `SELECT * FROM ${TABLES.memberships} WHERE entryId = ?`,
          [entryId],
        ),
      byCollection: (collectionId) =>
        executor.getAllAsync<MembershipRow>(
          `SELECT * FROM ${TABLES.memberships} WHERE collectionId = ?`,
          [collectionId],
        ),
    },
    media: {
      ...tableOps<MediaRow, string>(executor, MEDIA_SPEC),
      byEntry: (entryId) =>
        executor.getAllAsync<MediaRow>(`SELECT * FROM ${TABLES.media} WHERE entryId = ?`, [
          entryId,
        ]),
    },
    objects: {
      ...tableOps<ObjectRow, string>(executor, OBJECT_SPEC),
      byMedia: (mediaId) =>
        executor.getAllAsync<ObjectRow>(`SELECT * FROM ${TABLES.objects} WHERE mediaId = ?`, [
          mediaId,
        ]),
      presentOriginalsByAccess: () =>
        executor.getAllAsync<ObjectRow>(
          `SELECT * FROM ${TABLES.objects} WHERE localState = ? AND variant = ? ORDER BY lastAccessAt ASC, id ASC`,
          [OBJECT_PRESENT_STATE, OBJECT_ORIGINAL_VARIANT],
        ),
    },
    outbox: outboxOps(executor),
    pins: tableOps<PinRow, string>(executor, PIN_SPEC),
  };
}

async function migrate(database: SQLiteDatabase): Promise<void> {
  const current = await database.getFirstAsync<{ user_version: number }>('PRAGMA user_version', []);
  const version = current?.user_version ?? 0;
  if (version > ROWSTORE_SCHEMA_VERSION) throw new Error(EN['rowstore.newerSchema']);
  for (let step = version; step < SQLITE_MIGRATIONS.length; step += 1) {
    await database.withTransactionAsync(async () => {
      await database.execAsync(SQLITE_MIGRATIONS[step] ?? '');
      await database.execAsync(`PRAGMA user_version = ${step + 1}`);
    });
  }
}

export function createRowStore(deps: { readonly sodium: Sodium }): RowStore {
  let database: SQLiteDatabase | null = null;

  function requireDatabase(): SQLiteDatabase {
    if (database === null) throw new Error(EN['rowstore.notOpen']);
    return database;
  }

  async function close(): Promise<void> {
    const open = database;
    database = null;
    await open?.closeAsync();
  }

  return {
    open: async (accountKey) => {
      await close();
      const opened = await openDatabaseAsync(
        rowStoreFileName(hashAccountKey(deps.sodium, accountKey)),
      );
      await opened.execAsync('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');
      await migrate(opened);
      database = opened;
    },
    close,
    destroy: async (accountKey) => {
      await close();
      await deleteDatabaseAsync(rowStoreFileName(hashAccountKey(deps.sodium, accountKey)));
    },
    transaction: async <T>(work: (tx: RowStoreTx) => Promise<T>): Promise<T> => {
      let result: T | undefined;
      await requireDatabase().withExclusiveTransactionAsync(async (transaction) => {
        result = await work(createTx(transaction));
      });
      return result as T;
    },
    kvGet: async (key: StorageKey) => {
      const row = await requireDatabase().getFirstAsync<{ value: Uint8Array }>(
        `SELECT value FROM ${TABLES.kv} WHERE key = ?`,
        [key],
      );
      return row === null ? null : new Uint8Array(row.value);
    },
    kvGetJson: async <T>(key: StorageKey, schema: z.ZodType<T>) => {
      const row = await requireDatabase().getFirstAsync<{ value: Uint8Array }>(
        `SELECT value FROM ${TABLES.kv} WHERE key = ?`,
        [key],
      );
      return row === null ? null : decodeJson(row.value, schema);
    },
    kvSet: async (key, value) => {
      await requireDatabase().runAsync(
        `INSERT OR REPLACE INTO ${TABLES.kv} (key, value) VALUES (?, ?)`,
        [key, value],
      );
    },
    kvSetJson: async (key, value) => {
      await requireDatabase().runAsync(
        `INSERT OR REPLACE INTO ${TABLES.kv} (key, value) VALUES (?, ?)`,
        [key, encodeJson(value)],
      );
    },
    kvDelete: async (key) => {
      await requireDatabase().runAsync(`DELETE FROM ${TABLES.kv} WHERE key = ?`, [key]);
    },
  };
}
