import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { objectsRepository } from '../../src/adapters/db/objects-repository.ts';
import type { ObjectState } from '../../src/domain/enums.ts';
import { objectKey } from '../../src/domain/objects.ts';
import { randomId } from '../../src/platform/ids.ts';
import { insertMediaFixture, insertUserFixture } from './fixtures.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const SIZE_BYTES = 1000;
const UPLOAD_ONE = 'upload-one';
const UPLOAD_TWO = 'upload-two';

let testDatabase: TestDatabase;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
});

afterAll(async () => {
  await testDatabase.drop();
});

async function insertRow(state: ObjectState, overrides: { multipartUploadId?: string } = {}) {
  const user = await insertUserFixture(testDatabase.db);
  const media = await insertMediaFixture(testDatabase.db, {
    ownerId: user.userId,
    homeCollectionId: user.homeCollectionId,
  });
  const id = randomId();
  const now = new Date();
  await testDatabase.db
    .insertInto('objects')
    .values({
      id,
      ownerId: user.userId,
      mediaId: media.mediaId,
      variant: 'original',
      objectKey: objectKey(user.userId, id),
      sizeBytes: SIZE_BYTES,
      state,
      multipartUploadId: overrides.multipartUploadId ?? null,
      createdAt: now,
      uploadedAt: state === 'uploaded' ? now : null,
      deleteAfter: state === 'deleting' ? now : null,
    })
    .execute();
  return { id, userId: user.userId };
}

async function stateOf(id: string): Promise<ObjectState | undefined> {
  return (await objectsRepository.findById(testDatabase.db, id))?.state;
}

describe('objectsRepository.setMultipartUploadId', () => {
  it('sets the id once and reports false when one is already stored', async () => {
    const { id } = await insertRow('pending');
    expect(await objectsRepository.setMultipartUploadId(testDatabase.db, id, UPLOAD_ONE)).toBe(true);
    expect(await objectsRepository.setMultipartUploadId(testDatabase.db, id, UPLOAD_TWO)).toBe(false);
    const row = await objectsRepository.findById(testDatabase.db, id);
    expect(row?.multipartUploadId).toBe(UPLOAD_ONE);
  });
});

describe('objectsRepository.markPendingDeleting', () => {
  it('moves a pending row to deleting and reports true', async () => {
    const { id } = await insertRow('pending');
    const deleteAfter = new Date(Date.now() + SIZE_BYTES);
    const moved = await testDatabase.db
      .transaction()
      .execute((trx) => objectsRepository.markPendingDeleting(trx, id, deleteAfter));
    expect(moved).toBe(true);
    const row = await objectsRepository.findById(testDatabase.db, id);
    expect(row?.state).toBe('deleting');
    expect(row?.deleteAfter?.getTime()).toBe(deleteAfter.getTime());
  });

  it('leaves uploaded and deleting rows alone and reports false', async () => {
    for (const state of ['uploaded', 'deleting'] as const) {
      const { id } = await insertRow(state);
      const moved = await testDatabase.db
        .transaction()
        .execute((trx) => objectsRepository.markPendingDeleting(trx, id, new Date()));
      expect(moved).toBe(false);
      expect(await stateOf(id)).toBe(state);
    }
  });
});

describe('objectsRepository.deleteRow', () => {
  it('returns the size of the deleted deleting row and nothing the second time', async () => {
    const { id } = await insertRow('deleting');
    expect(await objectsRepository.deleteRow(testDatabase.db, id)).toBe(SIZE_BYTES);
    expect(await objectsRepository.deleteRow(testDatabase.db, id)).toBeUndefined();
  });

  it('does not delete a row that is not deleting', async () => {
    const { id } = await insertRow('uploaded');
    expect(await objectsRepository.deleteRow(testDatabase.db, id)).toBeUndefined();
    expect(await stateOf(id)).toBe('uploaded');
  });
});

describe('objectsRepository.sumSizeOfOwner', () => {
  it('sums the sizes of every state for one owner', async () => {
    const { id, userId } = await insertRow('uploaded');
    expect(id).toBeTruthy();
    expect(await objectsRepository.sumSizeOfOwner(testDatabase.db, userId)).toBe(SIZE_BYTES);
    const empty = await insertUserFixture(testDatabase.db);
    expect(await objectsRepository.sumSizeOfOwner(testDatabase.db, empty.userId)).toBe(0);
  });
});
