import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { StorageUnavailableError } from '../../src/adapters/storage/errors.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { AppError } from '../../src/domain/errors.ts';
import { randomId } from '../../src/platform/ids.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import { createObjectsService } from '../../src/services/objects-service.ts';
import { errorCodeOf } from './auth-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import {
  PART_BYTES,
  bytesOfSize,
  confirm,
  createObjectsContext,
  newOwner,
  objectRow,
  presign,
  presignBody,
  putBytes,
  usedBytesOf,
  type ObjectsContext,
} from './objects-helpers.ts';

const SMALL_BYTES = 100;
const MAX_PRESIGNS = 2;

let testDatabase: TestDatabase;
let context: ObjectsContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createObjectsContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

async function presignedSingle(owner: Awaited<ReturnType<typeof newOwner>>, id: string) {
  const service = createObjectsService({
    db: context.db,
    store: context.store,
    settings: context.settings,
    clock: context.clock,
    onObjectUploaded: async () => undefined,
    logger: silentLogger,
  });
  const issued = await service.presign(owner.user.userId, {
    id,
    mediaId: owner.medias[0]!.mediaId,
    variant: 'original',
    sizeBytes: SMALL_BYTES,
  });
  if (issued.mode !== 'single') throw new Error('expected single');
  return issued;
}

describe('confirm hook', () => {
  it('runs onObjectUploaded in the same transaction as markUploaded', async () => {
    const owner = await newOwner(context);
    const id = randomId();
    const issued = await presignedSingle(owner, id);
    expect((await putBytes(issued.url, bytesOfSize(SMALL_BYTES))).status).toBe(200);
    const calls: { entryId: string; stateInside: string | undefined; stateOutside: string | undefined }[] = [];
    const service = createObjectsService({
      db: context.db,
      store: context.store,
      settings: context.settings,
      clock: context.clock,
      onObjectUploaded: async (trx, entryId) => {
        const inside = await trx.selectFrom('objects').select('state').where('id', '=', id).executeTakeFirst();
        const outside = await context.db.selectFrom('objects').select('state').where('id', '=', id).executeTakeFirst();
        calls.push({ entryId, stateInside: inside?.state, stateOutside: outside?.state });
      },
      logger: silentLogger,
    });
    const result = await service.confirm(owner.user.userId, id);
    expect(result.state).toBe('uploaded');
    expect(calls).toEqual([
      { entryId: owner.medias[0]!.entryId, stateInside: 'uploaded', stateOutside: 'pending' },
    ]);
  });

  it('rolls markUploaded back when the hook fails', async () => {
    const owner = await newOwner(context);
    const id = randomId();
    const issued = await presignedSingle(owner, id);
    expect((await putBytes(issued.url, bytesOfSize(SMALL_BYTES))).status).toBe(200);
    const service = createObjectsService({
      db: context.db,
      store: context.store,
      settings: context.settings,
      clock: context.clock,
      onObjectUploaded: async () => {
        throw new Error('sequence bump failed');
      },
      logger: silentLogger,
    });
    await expect(service.confirm(owner.user.userId, id)).rejects.toThrow('sequence bump failed');
    expect((await objectRow(context.db, id))?.state).toBe('pending');
  });
});

describe('storage failures', () => {
  it('marks the row deleting and answers 503 when createMultipartUpload fails', async () => {
    const failing = await createObjectsContext(testDatabase, {
      wrapStore: (store): ObjectStore => ({
        ...store,
        createMultipartUpload: async () => {
          throw new StorageUnavailableError(new Error('down'));
        },
      }),
    });
    try {
      const owner = await newOwner(failing);
      const id = randomId();
      const result = await presign(
        failing,
        owner.user.sessionToken,
        presignBody({ id, mediaId: owner.medias[0]!.mediaId, sizeBytes: PART_BYTES + 1 }),
      );
      expect(result.status).toBe(503);
      expect(errorCodeOf(result)).toBe('STORAGE_UNAVAILABLE');
      const row = await objectRow(failing.db, id);
      expect(row?.state).toBe('deleting');
      expect(row?.deleteAfter?.getTime()).toBe(failing.clock.now().getTime());
    } finally {
      await failing.close();
    }
  });

  it('answers 503 when the bucket cannot be reached during confirm', async () => {
    const owner = await newOwner(context);
    const id = randomId();
    await presignedSingle(owner, id);
    const down = await createObjectsContext(testDatabase, {
      wrapStore: (store): ObjectStore => ({
        ...store,
        headObject: async () => {
          throw new StorageUnavailableError(new Error('down'));
        },
      }),
    });
    try {
      const result = await confirm(down, owner.user.sessionToken, id);
      expect(result.status).toBe(503);
      expect(errorCodeOf(result)).toBe('STORAGE_UNAVAILABLE');
      expect((await objectRow(down.db, id))?.state).toBe('pending');
    } finally {
      await down.close();
    }
  });

  it('maps a storage error to an AppError at the service', async () => {
    const owner = await newOwner(context);
    const id = randomId();
    await presignedSingle(owner, id);
    const service = createObjectsService({
      db: context.db,
      store: {
        ...context.store,
        headObject: async () => {
          throw new StorageUnavailableError(new Error('down'));
        },
      },
      settings: context.settings,
      clock: context.clock,
      onObjectUploaded: async () => undefined,
      logger: silentLogger,
    });
    await expect(service.confirm(owner.user.userId, id)).rejects.toBeInstanceOf(AppError);
  });
});

describe('presign rate', () => {
  it('answers RATE_LIMITED after MAX_PRESIGNS_PER_HOUR presigns in the last hour', async () => {
    const limited = await createObjectsContext(testDatabase, {
      env: { MAX_PRESIGNS_PER_HOUR: String(MAX_PRESIGNS) },
    });
    try {
      const owner = await newOwner(limited, MAX_PRESIGNS + 1);
      const statuses: number[] = [];
      for (const media of owner.medias) {
        const result = await presign(
          limited,
          owner.user.sessionToken,
          presignBody({ mediaId: media.mediaId, sizeBytes: SMALL_BYTES }),
        );
        statuses.push(result.status);
        if (result.status === 429) expect(errorCodeOf(result)).toBe('RATE_LIMITED');
      }
      expect(statuses).toEqual([200, 200, 429]);
      expect(await usedBytesOf(limited.db, owner.user.userId)).toBe(MAX_PRESIGNS * SMALL_BYTES);
    } finally {
      await limited.close();
    }
  });
});
