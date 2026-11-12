import type { z } from 'zod';
import { COMPARE_AFTER, COMPARE_BEFORE, COMPARE_EQUAL } from '../../constants/storage-constants';
import { EN } from '../../text/en';
import type { StorageKey } from '../../keys/storage-keys';
import { decodeJson, emptyOutboxCounts, encodeJson } from '../rowstore/rowstore-codec';
import { OBJECT_ORIGINAL_VARIANT, OBJECT_PRESENT_STATE, OUTBOX_QUEUED_STATE } from '../rowstore/schema';
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
} from '../rowstore/rowstore';

const KEY_SEPARATOR = '\u0000';
const FIRST_OUTBOX_ID = 1;

type Tables = {
  collections: Map<string, CollectionRow>;
  entries: Map<string, EntryRow>;
  memberships: Map<string, MembershipRow>;
  media: Map<string, MediaRow>;
  objects: Map<string, ObjectRow>;
  outbox: Map<number, OutboxRow>;
  pins: Map<string, PinRow>;
  nextOutboxId: number;
};

type AccountData = { readonly kv: Map<StorageKey, Uint8Array>; tables: Tables };

function emptyTables(): Tables {
  return {
    collections: new Map(),
    entries: new Map(),
    memberships: new Map(),
    media: new Map(),
    objects: new Map(),
    outbox: new Map(),
    pins: new Map(),
    nextOutboxId: FIRST_OUTBOX_ID,
  };
}

function copyTables(tables: Tables): Tables {
  return {
    collections: new Map(tables.collections),
    entries: new Map(tables.entries),
    memberships: new Map(tables.memberships),
    media: new Map(tables.media),
    objects: new Map(tables.objects),
    outbox: new Map(tables.outbox),
    pins: new Map(tables.pins),
    nextOutboxId: tables.nextOutboxId,
  };
}

function clone<Row>(row: Row): Row {
  return structuredClone(row);
}

function byKey<Key extends string | number>(left: readonly [Key, unknown], right: readonly [Key, unknown]) {
  if (left[0] < right[0]) return COMPARE_BEFORE;
  return left[0] > right[0] ? COMPARE_AFTER : COMPARE_EQUAL;
}

function sortedRows<Key extends string | number, Row>(map: Map<Key, Row>): Row[] {
  return [...map.entries()].sort(byKey).map(([, row]) => clone(row));
}

function tableOps<Row, Key>(
  map: Map<string, Row>,
  keyOfRow: (row: Row) => string,
  keyOfKey: (key: Key) => string,
): TableOps<Row, Key> {
  return {
    get: async (key) => {
      const row = map.get(keyOfKey(key));
      return row === undefined ? null : clone(row);
    },
    put: async (row) => {
      map.set(keyOfRow(row), clone(row));
    },
    delete: async (key) => {
      map.delete(keyOfKey(key));
    },
    all: async () => sortedRows(map),
  };
}

function membershipKey(collectionId: string, entryId: string): string {
  return `${collectionId}${KEY_SEPARATOR}${entryId}`;
}

function rowsWhere<Row>(map: Map<string, Row>, matches: (row: Row) => boolean): Row[] {
  return sortedRows(map).filter(matches);
}

function outboxOps(tables: Tables): RowStoreTx['outbox'] {
  return {
    enqueue: async (row) => {
      const id = tables.nextOutboxId;
      tables.nextOutboxId += 1;
      tables.outbox.set(id, clone({ ...row, id }));
      return id;
    },
    due: async (now, limit) =>
      sortedRows(tables.outbox)
        .filter((row) => row.state === OUTBOX_QUEUED_STATE && row.nextAttemptAt <= now)
        .sort((left, right) => left.nextAttemptAt - right.nextAttemptAt || left.id - right.id)
        .slice(0, limit),
    update: async (row) => {
      tables.outbox.set(row.id, clone(row));
    },
    delete: async (id) => {
      tables.outbox.delete(id);
    },
    countByState: async () => {
      const counts = emptyOutboxCounts();
      for (const row of tables.outbox.values()) counts[row.state] += 1;
      return counts;
    },
    all: async () => sortedRows(tables.outbox),
  };
}

function createTx(tables: Tables): RowStoreTx {
  return {
    collections: tableOps<CollectionRow, string>(tables.collections, (row) => row.id, String),
    entries: {
      ...tableOps<EntryRow, string>(tables.entries, (row) => row.id, String),
      byLocalState: async (states) =>
        states.flatMap((state) => rowsWhere(tables.entries, (row) => row.localState === state)),
    },
    memberships: {
      ...tableOps<MembershipRow, readonly [string, string]>(
        tables.memberships,
        (row) => membershipKey(row.collectionId, row.entryId),
        ([collectionId, entryId]) => membershipKey(collectionId, entryId),
      ),
      byEntry: async (entryId) => rowsWhere(tables.memberships, (row) => row.entryId === entryId),
      byCollection: async (collectionId) =>
        rowsWhere(tables.memberships, (row) => row.collectionId === collectionId),
    },
    media: {
      ...tableOps<MediaRow, string>(tables.media, (row) => row.id, String),
      byEntry: async (entryId) => rowsWhere(tables.media, (row) => row.entryId === entryId),
    },
    objects: {
      ...tableOps<ObjectRow, string>(tables.objects, (row) => row.id, String),
      byMedia: async (mediaId) => rowsWhere(tables.objects, (row) => row.mediaId === mediaId),
      presentOriginalsByAccess: async () =>
        rowsWhere(
          tables.objects,
          (row) => row.localState === OBJECT_PRESENT_STATE && row.variant === OBJECT_ORIGINAL_VARIANT,
        ).sort((left, right) => left.lastAccessAt - right.lastAccessAt),
    },
    outbox: outboxOps(tables),
    pins: tableOps<PinRow, string>(tables.pins, (row) => row.entryId, String),
  };
}

export function createMemoryRowStore(): RowStore {
  const accounts = new Map<string, AccountData>();
  let current: AccountData | null = null;
  let queue: Promise<unknown> = Promise.resolve();

  function open(): AccountData {
    if (current === null) throw new Error(EN['rowstore.notOpen']);
    return current;
  }

  async function close(): Promise<void> {
    current = null;
  }

  async function runTransaction<T>(work: (tx: RowStoreTx) => Promise<T>): Promise<T> {
    const account = open();
    const draft = copyTables(account.tables);
    const result = await work(createTx(draft));
    account.tables = draft;
    return result;
  }

  return {
    open: async (accountKey) => {
      const existing = accounts.get(accountKey);
      current = existing ?? { kv: new Map(), tables: emptyTables() };
      accounts.set(accountKey, current);
    },
    close,
    destroy: async (accountKey) => {
      accounts.delete(accountKey);
      await close();
    },
    transaction: <T>(work: (tx: RowStoreTx) => Promise<T>): Promise<T> => {
      const run = queue.then(() => runTransaction(work));
      queue = run.catch(() => undefined);
      return run;
    },
    kvGet: async (key) => open().kv.get(key)?.slice() ?? null,
    kvGetJson: async <T>(key: StorageKey, schema: z.ZodType<T>) => {
      const bytes = open().kv.get(key);
      return bytes === undefined ? null : decodeJson(bytes, schema);
    },
    kvSet: async (key, value) => {
      open().kv.set(key, value.slice());
    },
    kvSetJson: async (key, value) => {
      open().kv.set(key, encodeJson(value));
    },
    kvDelete: async (key) => {
      open().kv.delete(key);
    },
  };
}
