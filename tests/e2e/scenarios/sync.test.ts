import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  LATER_EDIT_UTC,
  LOST_RESPONSE_COUNT,
  MULTIPART_MEDIA_BYTES,
  PATCH_METHOD,
  QUOTA_EXCEEDED_CODE,
  ROLLBACK_REJECTED_EVENT,
  SECOND_REVISION,
  FIRST_REVISION,
  SMALL_MEDIA_BYTES,
  TINY_QUOTA_BYTES,
} from '../support/constants.ts';
import {
  createEntryLocally,
  deleteEntryLocally,
  downloadAndDecrypt,
  editEntryLocally,
  entrySnapshots,
  homeCollectionId,
  outboxRows,
  readEntry,
  readMetadata,
  sameBytes,
  syncUntilIdle,
  trashEntryLocally,
} from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, mediaIdAt, startPair } from '../support/scenario.ts';
import { rewindEntry, serverEntry, setQuota } from '../support/server-state.ts';

const PART_NUMBER_QUERY = 'partNumber=';
const UPLOAD_METHOD = 'PUT';
const POST_METHOD = 'POST';
const smallEntry = () => ({
  mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)],
  note: 'base note',
  tags: ['family'],
});

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

describe('sync between two devices of one account', () => {
  it('scenario 1: an entry with two media, one multipart, reaches device B and decrypts there', async () => {
    const { a, b } = await startPair(harness);
    const big = randomMedia(MULTIPART_MEDIA_BYTES);
    const small = randomMedia(SMALL_MEDIA_BYTES);
    const entry = await createEntryLocally(a, {
      mediaBytes: [big, small],
      note: 'two photos',
      tags: ['family'],
    });

    await syncUntilIdle(a);

    const onA = await readEntry(a, entry.entryId);
    expect(onA).toMatchObject({ localState: 'synced', revision: FIRST_REVISION });
    const multipartParts = a.fetcher
      .requests()
      .filter((request) => request.startsWith(UPLOAD_METHOD) && request.includes(PART_NUMBER_QUERY));
    expect(multipartParts.length).toBeGreaterThanOrEqual(2);

    await syncUntilIdle(b);

    const onB = await readEntry(b, entry.entryId);
    expect(onB).toMatchObject({ localState: 'synced', revision: FIRST_REVISION });
    const { media, objects } = await b.context.rowStore.transaction(async (tx) => {
      const mediaRows = await tx.media.byEntry(entry.entryId);
      const objectRows = (
        await Promise.all(mediaRows.map((row) => tx.objects.byMedia(row.id)))
      ).flat();
      return { media: mediaRows, objects: objectRows };
    });
    expect(media.map((row) => row.id).sort()).toEqual([...entry.mediaIds].sort());
    expect(objects).toHaveLength(entry.mediaIds.length * 2);
    expect(objects.every((object) => object.uploadState === 'uploaded')).toBe(true);
    expect(objects.every((object) => object.localState === 'missing')).toBe(true);

    const bigPlaintext = await downloadAndDecrypt(b, entry.entryId, mediaIdAt(entry, 0), 'original');
    const smallPlaintext = await downloadAndDecrypt(b, entry.entryId, mediaIdAt(entry, 1), 'original');
    expect(sameBytes(bigPlaintext, big)).toBe(true);
    expect(sameBytes(smallPlaintext, small)).toBe(true);
  });

  it('scenario 3: a dropped PATCH response completes through the equivalent-merge path without a second revision bump', async () => {
    const { a } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await editEntryLocally(
      a,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'edited once' }),
      LATER_EDIT_UTC,
    );
    a.fetcher.dropNextResponse(PATCH_METHOD);

    await syncUntilIdle(a);

    expect(a.fetcher.droppedCount()).toBe(1);
    expect(await readEntry(a, entry.entryId)).toMatchObject({
      localState: 'synced',
      revision: SECOND_REVISION,
    });
    expect((await serverEntry(harness, entry.entryId)).revision).toBe(SECOND_REVISION);
    expect(await outboxRows(a)).toHaveLength(0);
  });

  it('scenario 4: a trash on A and an edit on B end trashed on both with the edit applied', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);

    await trashEntryLocally(a, entry.entryId);
    await syncUntilIdle(a);
    await editEntryLocally(
      b,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'edited on B' }),
      LATER_EDIT_UTC,
    );
    await syncUntilIdle(b);
    await syncUntilIdle(a);

    for (const device of [a, b]) {
      const row = await readEntry(device, entry.entryId);
      expect(row?.trashedAt).not.toBeNull();
      expect((await readMetadata(device, entry.entryId)).note).toBe('edited on B');
    }
  });

  it('scenario 5: a delete on A and an edit on B drop the update item and remove the entry on B', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);

    await deleteEntryLocally(a, entry.entryId);
    await syncUntilIdle(a);
    await editEntryLocally(
      b,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'edited after delete' }),
      LATER_EDIT_UTC,
    );
    await syncUntilIdle(b);

    expect(await readEntry(a, entry.entryId)).toBeNull();
    expect(await readEntry(b, entry.entryId)).toBeNull();
    expect(await outboxRows(b)).toHaveLength(0);
    expect(b.context.sync.status().dead).toBe(0);
  });

  it('scenario 6: a full quota ends the create item dead with QUOTA_EXCEEDED and Retry finishes it after the quota is raised', async () => {
    const { a, b } = await startPair(harness);
    await setQuota(harness, a.userId, TINY_QUOTA_BYTES);
    const entry = await createEntryLocally(a, smallEntry());

    await syncUntilIdle(a);

    const dead = await outboxRows(a);
    expect(dead).toHaveLength(1);
    expect(dead[0]).toMatchObject({
      kind: 'createEntry',
      state: 'dead',
      lastErrorCode: QUOTA_EXCEEDED_CODE,
    });
    expect(a.context.sync.status().dead).toBe(1);

    await setQuota(harness, a.userId, null);
    await a.context.sync.retryDead();
    await syncUntilIdle(a);

    expect(await outboxRows(a)).toHaveLength(0);
    expect(await readEntry(a, entry.entryId)).toMatchObject({ localState: 'synced' });
    await syncUntilIdle(b);
    expect(await readEntry(b, entry.entryId)).not.toBeNull();
  });

  it('scenario 7: a pulled revision older than the local one is rejected and logged', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    const older = await serverEntry(harness, entry.entryId);
    await editEntryLocally(
      a,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'newer note' }),
      LATER_EDIT_UTC,
    );
    await syncUntilIdle(a);
    await syncUntilIdle(b);
    const before = await entrySnapshots(b);
    expect(before.get(entry.entryId)?.revision).toBe(SECOND_REVISION);

    await rewindEntry(harness, entry.entryId, await homeCollectionId(a), older);
    await syncUntilIdle(b);

    expect(b.logs().some((record) => record.event === ROLLBACK_REJECTED_EVENT)).toBe(true);
    expect(await entrySnapshots(b)).toEqual(before);
    expect((await readMetadata(b, entry.entryId)).note).toBe('newer note');
  });

  it('scenario 9: a dropped createEntry response followed by an edit still ends synced (pulled server copy)', async () => {
    const { a } = await startPair(harness);
    const entry = await createEntryLocally(a, smallEntry());
    a.fetcher.dropNextResponse(POST_METHOD, LOST_RESPONSE_COUNT);

    await a.context.sync.syncNow();
    expect(a.fetcher.droppedCount()).toBe(LOST_RESPONSE_COUNT);
    await editEntryLocally(
      a,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'edited after the lost response' }),
      LATER_EDIT_UTC,
    );
    await syncUntilIdle(a);

    expect(await readEntry(a, entry.entryId)).toMatchObject({
      localState: 'synced',
      revision: SECOND_REVISION,
    });
    expect((await serverEntry(harness, entry.entryId)).revision).toBe(SECOND_REVISION);
    expect((await readMetadata(a, entry.entryId)).note).toBe('edited after the lost response');
    expect(await outboxRows(a)).toHaveLength(0);
  });

  it('scenario 9b: the same lost response resolves through 409 ID_CONFLICT when no server copy was pulled', async () => {
    const { a } = await startPair(harness);
    const entry = await createEntryLocally(a, smallEntry());
    a.fetcher.dropNextResponse(POST_METHOD, LOST_RESPONSE_COUNT);

    await a.context.sync.syncNow();
    await a.context.rowStore.transaction(async (tx) => {
      const row = await tx.entries.get(entry.entryId);
      if (row !== null) {
        await tx.entries.put({ ...row, serverPendingBlob: null, serverPendingRevision: null });
      }
    });
    await editEntryLocally(
      a,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'edited after the lost response' }),
      LATER_EDIT_UTC,
    );
    await syncUntilIdle(a);

    expect(
      a.fetcher.requests().filter((request) => request.startsWith(`GET ${harness.baseUrl}/api/v1/entries/`)),
    ).not.toHaveLength(0);
    expect(await readEntry(a, entry.entryId)).toMatchObject({
      localState: 'synced',
      revision: SECOND_REVISION,
    });
    expect((await serverEntry(harness, entry.entryId)).revision).toBe(SECOND_REVISION);
    expect(await outboxRows(a)).toHaveLength(0);
  });
});
