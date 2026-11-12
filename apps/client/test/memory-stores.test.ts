import { describe, expect, it } from 'vitest';
import { rowStoreSelfTest } from '../src/features/diagnostics/rowstore-self-test';
import { fileStoreSelfTest } from '../src/features/diagnostics/filestore-self-test';
import type { EntryRow, ObjectRow, OutboxRow } from '../src/adapters/rowstore/rowstore';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import { createMemoryFileStore, createMemoryRowStore } from './support';

const ACCOUNT = 'host/user';
const ENTRY_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ENTRY_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const OBJECT_ID = 'CCCCCCCCCCCCCCCCCCCCCC';

function entry(id: string, overrides: Partial<EntryRow> = {}): EntryRow {
  return {
    id,
    ownerId: 'owner',
    purpose: 'journal',
    revision: 1,
    metadataBlob: Uint8Array.of(1),
    baseMetadataBlob: null,
    baseRevision: null,
    serverPendingBlob: null,
    serverPendingRevision: null,
    trashedAt: null,
    createdAt: '2026-11-12T10:00:00.000Z',
    updatedAt: '2026-11-12T10:00:00.000Z',
    localState: 'synced',
    ...overrides,
  };
}

function outboxRow(overrides: Partial<Omit<OutboxRow, 'id'>> = {}): Omit<OutboxRow, 'id'> {
  return {
    kind: 'createEntry',
    payload: { entryId: ENTRY_A },
    state: 'queued',
    createdAt: 0,
    attempts: 0,
    nextAttemptAt: 0,
    lastErrorCode: null,
    ...overrides,
  };
}

async function openedRowStore() {
  const rowStore = createMemoryRowStore();
  await rowStore.open(ACCOUNT);
  return rowStore;
}

describe('memory RowStore', () => {
  it('passes the same self-test the device RowStores pass', async () => {
    await expect(rowStoreSelfTest(createMemoryRowStore())).resolves.toBeUndefined();
  });

  it('commits a transaction that resolves', async () => {
    const rowStore = await openedRowStore();
    await rowStore.transaction(async (tx) => {
      await tx.entries.put(entry(ENTRY_A));
    });
    expect((await rowStore.transaction((tx) => tx.entries.get(ENTRY_A)))?.id).toBe(ENTRY_A);
  });

  it('discards every table change when the transaction throws', async () => {
    const rowStore = await openedRowStore();
    await rowStore.transaction((tx) => tx.entries.put(entry(ENTRY_A)));
    await expect(
      rowStore.transaction(async (tx) => {
        await tx.entries.put(entry(ENTRY_B));
        await tx.entries.delete(ENTRY_A);
        await tx.outbox.enqueue(outboxRow());
        throw new Error('boom');
      }),
    ).rejects.toThrow('boom');
    const state = await rowStore.transaction(async (tx) => ({
      entries: (await tx.entries.all()).map((row) => row.id),
      outbox: await tx.outbox.all(),
    }));
    expect(state.entries).toEqual([ENTRY_A]);
    expect(state.outbox).toEqual([]);
  });

  it('serializes concurrent transactions so no update is lost', async () => {
    const rowStore = await openedRowStore();
    await rowStore.transaction((tx) => tx.entries.put(entry(ENTRY_A, { revision: 0 })));
    const bump = () =>
      rowStore.transaction(async (tx) => {
        const row = await tx.entries.get(ENTRY_A);
        await Promise.resolve();
        if (row !== null) await tx.entries.put({ ...row, revision: row.revision + 1 });
      });
    await Promise.all([bump(), bump(), bump()]);
    expect((await rowStore.transaction((tx) => tx.entries.get(ENTRY_A)))?.revision).toBe(3);
  });

  it('keeps working after a failed transaction', async () => {
    const rowStore = await openedRowStore();
    await rowStore.transaction(() => Promise.reject(new Error('first'))).catch(() => undefined);
    await expect(rowStore.transaction((tx) => tx.entries.all())).resolves.toEqual([]);
  });

  it('assigns increasing outbox ids and returns due rows in due order', async () => {
    const rowStore = await openedRowStore();
    const ids = await rowStore.transaction(async (tx) => [
      await tx.outbox.enqueue(outboxRow({ nextAttemptAt: 30 })),
      await tx.outbox.enqueue(outboxRow({ nextAttemptAt: 10 })),
      await tx.outbox.enqueue(outboxRow({ nextAttemptAt: 20, state: 'dead' })),
    ]);
    expect(ids[1]).toBeGreaterThan(ids[0] ?? 0);
    const due = await rowStore.transaction((tx) => tx.outbox.due(25, 10));
    expect(due.map((row) => row.nextAttemptAt)).toEqual([10]);
  });

  it('returns copies of rows so callers cannot corrupt stored bytes', async () => {
    const rowStore = await openedRowStore();
    await rowStore.transaction((tx) => tx.entries.put(entry(ENTRY_A, { metadataBlob: Uint8Array.of(7, 8) })));
    const read = await rowStore.transaction((tx) => tx.entries.get(ENTRY_A));
    read?.metadataBlob.fill(0);
    const again = await rowStore.transaction((tx) => tx.entries.get(ENTRY_A));
    expect([...(again?.metadataBlob ?? [])]).toEqual([7, 8]);
  });

  it('keeps kv values per account and survives close and reopen', async () => {
    const rowStore = createMemoryRowStore();
    await rowStore.open(ACCOUNT);
    await rowStore.kvSetJson(STORAGE_KEYS.accountUserId, 'one');
    await rowStore.close();
    await rowStore.open('other/account');
    expect(await rowStore.kvGet(STORAGE_KEYS.accountUserId)).toBeNull();
    await rowStore.open(ACCOUNT);
    expect(await rowStore.kvGet(STORAGE_KEYS.accountUserId)).not.toBeNull();
  });

  it('finds present originals by access time', async () => {
    const rowStore = await openedRowStore();
    const object = (id: string, lastAccessAt: number): ObjectRow => ({
      id,
      mediaId: ENTRY_B,
      variant: 'original',
      sizeBytes: 100,
      plaintextSize: 60,
      localState: 'present',
      uploadState: 'uploaded',
      uploadJobId: null,
      lastAccessAt,
    });
    await rowStore.transaction(async (tx) => {
      await tx.objects.put(object(OBJECT_ID, 5));
      await tx.objects.put(object(ENTRY_A, 2));
    });
    const rows = await rowStore.transaction((tx) => tx.objects.presentOriginalsByAccess());
    expect(rows.map((row) => row.lastAccessAt)).toEqual([2, 5]);
  });
});

describe('memory FileStore', () => {
  it('passes the same self-test the device FileStores pass', async () => {
    await expect(fileStoreSelfTest(createMemoryFileStore())).resolves.toBeUndefined();
  });

  it('returns null from readBlob for a missing object', async () => {
    const fileStore = createMemoryFileStore();
    await fileStore.open(ACCOUNT);
    expect(await fileStore.readBlob(OBJECT_ID)).toBeNull();
  });
});
