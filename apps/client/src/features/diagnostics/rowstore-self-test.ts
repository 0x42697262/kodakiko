import { z } from 'zod';
import type { RowStore, RowStoreTx, TableOps } from '../../adapters/rowstore/rowstore';
import { TABLES } from '../../adapters/rowstore/schema';
import { STORAGE_KEYS } from '../../keys/storage-keys';
import { SELF_TEST_CHECKS } from '../../keys/self-test-keys';
import {
  OUTBOX_EXPECTED_COUNTS,
  OUTBOX_EXPECTED_DUE_ORDER,
  OUTBOX_LIMIT,
  OUTBOX_NOW,
  OUTBOX_ROWS,
  OUTBOX_UPDATED_ATTEMPTS,
  SAMPLE_COLLECTION,
  SAMPLE_ENTRY,
  SAMPLE_MEDIA,
  SAMPLE_MEMBERSHIP,
  SAMPLE_OBJECT,
  SAMPLE_OLDER_OBJECT,
  SAMPLE_OTHER_ENTRY,
  SAMPLE_OTHER_MEMBERSHIP,
  SAMPLE_PIN,
  SAMPLE_THUMBNAIL_OBJECT,
  SELF_TEST_ACCOUNT_KEY,
  SELF_TEST_BYTES,
  SELF_TEST_JSON,
  SELF_TEST_MEMBERSHIPS_PER_ENTRY,
  SELF_TEST_OBJECTS_PER_MEDIA,
} from './self-test-constants';
import { SELF_TEST_FAILURE_MESSAGE } from '../../constants/diagnostics-constants';
import { expectCheck, sameValue } from './self-test-support';

const JSON_SCHEMA = z.object({ answer: z.number() });

async function checkKv(rowStore: RowStore): Promise<void> {
  const key = STORAGE_KEYS.diagnosticsProbe;
  await rowStore.kvSet(key, SELF_TEST_BYTES);
  expectCheck(sameValue(await rowStore.kvGet(key), SELF_TEST_BYTES), SELF_TEST_CHECKS.kvBytes);
  await rowStore.kvSetJson(key, SELF_TEST_JSON);
  expectCheck(
    sameValue(await rowStore.kvGetJson(key, JSON_SCHEMA), SELF_TEST_JSON),
    SELF_TEST_CHECKS.kvJson,
  );
  await rowStore.kvDelete(key);
  expectCheck((await rowStore.kvGet(key)) === null, SELF_TEST_CHECKS.kvDelete);
}

async function checkTable<Row, Key>(
  table: string,
  ops: TableOps<Row, Key>,
  row: Row,
  key: Key,
): Promise<void> {
  await ops.put(row);
  const stored = await ops.get(key);
  const listed = await ops.all();
  expectCheck(
    sameValue(stored, row) && listed.length === 1,
    SELF_TEST_CHECKS.tableRoundTrip(table),
  );
  await ops.delete(key);
  expectCheck(
    (await ops.get(key)) === null && (await ops.all()).length === 0,
    SELF_TEST_CHECKS.tableRoundTrip(table),
  );
}

async function checkTables(tx: RowStoreTx): Promise<void> {
  await checkTable(TABLES.collections, tx.collections, SAMPLE_COLLECTION, SAMPLE_COLLECTION.id);
  await checkTable(TABLES.entries, tx.entries, SAMPLE_ENTRY, SAMPLE_ENTRY.id);
  await checkTable(TABLES.memberships, tx.memberships, SAMPLE_MEMBERSHIP, [
    SAMPLE_MEMBERSHIP.collectionId,
    SAMPLE_MEMBERSHIP.entryId,
  ] as const);
  await checkTable(TABLES.media, tx.media, SAMPLE_MEDIA, SAMPLE_MEDIA.id);
  await checkTable(TABLES.objects, tx.objects, SAMPLE_OBJECT, SAMPLE_OBJECT.id);
  await checkTable(TABLES.pins, tx.pins, SAMPLE_PIN, SAMPLE_PIN.entryId);
}

async function checkQueries(tx: RowStoreTx): Promise<void> {
  await tx.entries.put(SAMPLE_ENTRY);
  await tx.entries.put(SAMPLE_OTHER_ENTRY);
  const pending = await tx.entries.byLocalState([SAMPLE_ENTRY.localState]);
  expectCheck(
    pending.length === 1 && pending[0]?.id === SAMPLE_ENTRY.id,
    SELF_TEST_CHECKS.entriesByLocalState,
  );

  await tx.memberships.put(SAMPLE_MEMBERSHIP);
  await tx.memberships.put(SAMPLE_OTHER_MEMBERSHIP);
  expectCheck(
    (await tx.memberships.byEntry(SAMPLE_MEMBERSHIP.entryId)).length ===
      SELF_TEST_MEMBERSHIPS_PER_ENTRY,
    SELF_TEST_CHECKS.membershipsByEntry,
  );
  const inCollection = await tx.memberships.byCollection(SAMPLE_MEMBERSHIP.collectionId);
  expectCheck(
    inCollection.length === 1 && sameValue(inCollection[0], SAMPLE_MEMBERSHIP),
    SELF_TEST_CHECKS.membershipsByCollection,
  );

  await tx.media.put(SAMPLE_MEDIA);
  expectCheck(
    (await tx.media.byEntry(SAMPLE_MEDIA.entryId)).length === 1,
    SELF_TEST_CHECKS.mediaByEntry,
  );

  await tx.objects.put(SAMPLE_OBJECT);
  await tx.objects.put(SAMPLE_OLDER_OBJECT);
  await tx.objects.put(SAMPLE_THUMBNAIL_OBJECT);
  expectCheck(
    (await tx.objects.byMedia(SAMPLE_OBJECT.mediaId)).length === SELF_TEST_OBJECTS_PER_MEDIA,
    SELF_TEST_CHECKS.objectsByMedia,
  );
  const originals = await tx.objects.presentOriginalsByAccess();
  expectCheck(
    originals.map((row) => row.id).join() === [SAMPLE_OLDER_OBJECT.id, SAMPLE_OBJECT.id].join(),
    SELF_TEST_CHECKS.objectsPresentOriginals,
  );
}

async function checkRollback(rowStore: RowStore): Promise<void> {
  const failure = await rowStore
    .transaction(async (tx) => {
      await tx.collections.put(SAMPLE_COLLECTION);
      throw new Error(SELF_TEST_FAILURE_MESSAGE);
    })
    .catch((error: unknown) => error);
  const stored = await rowStore.transaction((tx) => tx.collections.get(SAMPLE_COLLECTION.id));
  expectCheck(failure instanceof Error && stored === null, SELF_TEST_CHECKS.rollback);
}

async function checkOutbox(tx: RowStoreTx): Promise<void> {
  for (const row of OUTBOX_ROWS) await tx.outbox.enqueue(row);
  const due = await tx.outbox.due(OUTBOX_NOW, OUTBOX_LIMIT);
  expectCheck(
    due.map((row) => row.nextAttemptAt).join() === OUTBOX_EXPECTED_DUE_ORDER.join(),
    SELF_TEST_CHECKS.outboxDue,
  );
  expectCheck(
    sameValue(await tx.outbox.countByState(), OUTBOX_EXPECTED_COUNTS),
    SELF_TEST_CHECKS.outboxCounts,
  );
  const [first] = due;
  if (first === undefined) return expectCheck(false, SELF_TEST_CHECKS.outboxUpdateDelete);
  await tx.outbox.update({ ...first, attempts: OUTBOX_UPDATED_ATTEMPTS });
  const updated = (await tx.outbox.all()).find((row) => row.id === first.id);
  await tx.outbox.delete(first.id);
  const remaining = await tx.outbox.all();
  expectCheck(
    updated?.attempts === OUTBOX_UPDATED_ATTEMPTS &&
      sameValue(updated.payload, first.payload) &&
      remaining.every((row) => row.id !== first.id),
    SELF_TEST_CHECKS.outboxUpdateDelete,
  );
}

export async function rowStoreSelfTest(rowStore: RowStore): Promise<void> {
  await rowStore.open(SELF_TEST_ACCOUNT_KEY);
  try {
    await checkKv(rowStore);
    await rowStore.transaction(checkTables);
    await rowStore.transaction(checkQueries);
    await checkRollback(rowStore);
    await rowStore.transaction(checkOutbox);
  } finally {
    await rowStore.destroy(SELF_TEST_ACCOUNT_KEY);
  }
}
