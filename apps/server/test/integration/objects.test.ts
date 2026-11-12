import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { errorResponseSchema, ROUTES } from '@kodakiko/contract';
import { createStreamEncryptor, STREAM_MAGIC } from '@kodakiko/core';
import { createNodeSodium } from '../../../../packages/core/test/sodium-node.ts';
import { collectObjectsJob } from '../../src/jobs/collect-objects.ts';
import { randomId } from '../../src/platform/ids.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import { call, errorCodeOf } from './auth-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import {
  PART_BYTES,
  bytesOfSize,
  confirm,
  createObjectsContext,
  download,
  fetchBytes,
  newOwner,
  objectOf,
  objectRow,
  partsOf,
  parts,
  presign,
  presignBody,
  presignOf,
  putBytes,
  uploadSingle,
  usedBytesOf,
  type ObjectsContext,
} from './objects-helpers.ts';

const SINGLE_BYTES = 1000;
const QUOTA_BYTES = 1500;
const RANGE_START = 10;
const RANGE_END = 19;
const RANGE_LENGTH = 10;
const PARTIAL_CONTENT = 206;
const MULTIPART_TAIL = 123;
const FAR_FUTURE_MS = 3_650 * 86_400_000;
const LOCALHOST_ENDPOINT = 'http://localhost:53900';
const LOOPBACK_ADDRESS = '127.0.0.1';
const SECRET_QUERY_MARKER = 'X-Amz-Signature';

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

describe('1. single upload', () => {
  it('presigns, uploads, confirms and downloads with a range', async () => {
    const { user, medias } = await newOwner(context);
    const media = medias[0]!;
    const id = randomId();
    const bytes = bytesOfSize(SINGLE_BYTES);

    const presigned = await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: media.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    expect(presigned.status).toBe(200);
    const issued = presignOf(presigned);
    if (issued.mode !== 'single') throw new Error('expected single');
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);

    const put = await putBytes(issued.url, bytes);
    expect(put.status).toBe(200);

    const confirmed = await confirm(context, user.sessionToken, id);
    expect(confirmed.status).toBe(200);
    expect(objectOf(confirmed)).toMatchObject({
      id,
      mediaId: media.mediaId,
      variant: 'original',
      sizeBytes: SINGLE_BYTES,
      state: 'uploaded',
    });
    expect((await objectRow(context.db, id))?.uploadedAt).not.toBeNull();
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);

    const link = await download(context, user.sessionToken, id);
    expect(link.status).toBe(200);
    const { url } = link.json as { url: string };
    expect((await fetchBytes(url)).bytes.equals(bytes)).toBe(true);
    const ranged = await fetchBytes(url, `bytes=${RANGE_START}-${RANGE_END}`);
    expect(ranged.status).toBe(PARTIAL_CONTENT);
    expect(ranged.bytes).toHaveLength(RANGE_LENGTH);
    expect(ranged.bytes.equals(bytes.subarray(RANGE_START, RANGE_END + 1))).toBe(true);
  });

  it('reports UPLOAD_INCOMPLETE and keeps the row pending when nothing was uploaded', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    const result = await confirm(context, user.sessionToken, id);
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('UPLOAD_INCOMPLETE');
    expect((await objectRow(context.db, id))?.state).toBe('pending');
  });

  it('does not offer a download for a pending object', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    expect((await download(context, user.sessionToken, id)).status).toBe(404);
  });
});

describe('2. multipart upload', () => {
  it('resumes through the parts route and downloads the concatenation', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const size = 2 * PART_BYTES + MULTIPART_TAIL;
    const bytes = bytesOfSize(size);
    const issued = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: size }),
      ),
    );
    if (issued.mode !== 'multipart') throw new Error('expected multipart');
    expect(issued.partSizeBytes).toBe(PART_BYTES);
    expect(issued.partUrls.map((entry) => entry.partNumber)).toEqual([1, 2, 3]);

    const first = issued.partUrls[0]!;
    expect((await putBytes(first.url, bytes.subarray(0, PART_BYTES))).status).toBe(200);

    const early = await confirm(context, user.sessionToken, id);
    expect(early.status).toBe(422);
    expect(errorCodeOf(early)).toBe('UPLOAD_INCOMPLETE');
    expect((await objectRow(context.db, id))?.state).toBe('pending');

    const listing = partsOf(await parts(context, user.sessionToken, id));
    expect(listing.partSizeBytes).toBe(PART_BYTES);
    expect(listing.uploadedParts).toEqual([{ partNumber: 1, sizeBytes: PART_BYTES }]);
    expect(listing.partUrls.map((entry) => entry.partNumber)).toEqual([2, 3]);

    const [second, third] = listing.partUrls;
    expect(
      (await putBytes(second!.url, bytes.subarray(PART_BYTES, 2 * PART_BYTES))).status,
    ).toBe(200);
    expect((await putBytes(third!.url, bytes.subarray(2 * PART_BYTES))).status).toBe(200);

    const confirmed = await confirm(context, user.sessionToken, id);
    expect(confirmed.status).toBe(200);
    expect(objectOf(confirmed).state).toBe('uploaded');

    const link = (await download(context, user.sessionToken, id)).json as { url: string };
    expect((await fetchBytes(link.url)).bytes.equals(bytes)).toBe(true);
  });

  it('answers the parts route with NOT_FOUND for a single-PUT object', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    expect((await parts(context, user.sessionToken, id)).status).toBe(404);
  });

  it('re-issues every part URL for a repeated presign without a new multipart upload', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const size = 2 * PART_BYTES + MULTIPART_TAIL;
    const body = presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: size });
    const first = presignOf(await presign(context, user.sessionToken, body));
    const uploadIdBefore = (await objectRow(context.db, id))?.multipartUploadId;
    const second = presignOf(await presign(context, user.sessionToken, body));
    if (first.mode !== 'multipart' || second.mode !== 'multipart') throw new Error('multipart');
    expect(second.partUrls.map((entry) => entry.partNumber)).toEqual([1, 2, 3]);
    expect((await objectRow(context.db, id))?.multipartUploadId).toBe(uploadIdBefore);
    expect(uploadIdBefore).toBeTruthy();
    expect(await usedBytesOf(context.db, user.userId)).toBe(size);
  });
});

describe('3. size mismatch', () => {
  it('marks the row deleting and releases the reservation after collection', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const issued = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
      ),
    );
    if (issued.mode !== 'single') throw new Error('expected single');
    const declared = await objectRow(context.db, id);
    const smallerUrl = await context.store.presignPut(declared!.objectKey, SINGLE_BYTES - 1, 60);
    expect((await putBytes(issued.url, bytesOfSize(SINGLE_BYTES - 1))).status).toBe(403);
    expect((await putBytes(smallerUrl, bytesOfSize(SINGLE_BYTES - 1))).status).toBe(200);

    const result = await confirm(context, user.sessionToken, id);
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('UPLOAD_SIZE_MISMATCH');
    const row = await objectRow(context.db, id);
    expect(row?.state).toBe('deleting');
    expect(await context.store.headObject(row!.objectKey)).not.toBeNull();

    const job = collectObjectsJob(context.settings, context.store, silentLogger);
    const now = new Date(Date.now() + FAR_FUTURE_MS);
    await job.run({ db: context.db, now, signal: new AbortController().signal });
    expect(await objectRow(context.db, id)).toBeUndefined();
    expect(await context.store.headObject(row!.objectKey)).toBeNull();
    expect(await usedBytesOf(context.db, user.userId)).toBe(0);
  });
});

describe('4. quota', () => {
  it('refuses a presign over the quota and logs the refusal', async () => {
    const { user, medias } = await newOwner(context, 2);
    await context.db
      .updateTable('users')
      .set({ quotaBytes: QUOTA_BYTES })
      .where('id', '=', user.userId)
      .execute();
    const accepted = await presign(
      context,
      user.sessionToken,
      presignBody({ mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
    );
    expect(accepted.status).toBe(200);
    const refused = await presign(
      context,
      user.sessionToken,
      presignBody({ mediaId: medias[1]!.mediaId, sizeBytes: 600 }),
    );
    expect(refused.status).toBe(403);
    expect(errorCodeOf(refused)).toBe('QUOTA_EXCEEDED');
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);
    expect(context.logs.text()).toContain('object.quota_refused');
  });

  it('lets exactly one of two concurrent presigns reserve the same space', async () => {
    const { user, medias } = await newOwner(context, 2);
    await context.db
      .updateTable('users')
      .set({ quotaBytes: QUOTA_BYTES })
      .where('id', '=', user.userId)
      .execute();
    const results = await Promise.all(
      medias.map((media) =>
        presign(
          context,
          user.sessionToken,
          presignBody({ mediaId: media.mediaId, sizeBytes: SINGLE_BYTES }),
        ),
      ),
    );
    expect(results.map((result) => result.status).sort()).toEqual([200, 403]);
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);
  });
});

describe('5. idempotency', () => {
  it('reserves once for a repeated presign and conflicts on a different size', async () => {
    const { user, medias } = await newOwner(context);
    const id = randomId();
    const body = presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES });
    expect((await presign(context, user.sessionToken, body)).status).toBe(200);
    expect((await presign(context, user.sessionToken, body)).status).toBe(200);
    const rows = await context.db
      .selectFrom('objects')
      .select('id')
      .where('ownerId', '=', user.userId)
      .execute();
    expect(rows).toHaveLength(1);
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);

    const different = await presign(context, user.sessionToken, { ...body, sizeBytes: 2000 });
    expect(different.status).toBe(409);
    expect(errorCodeOf(different)).toBe('ID_CONFLICT');
    expect(await usedBytesOf(context.db, user.userId)).toBe(SINGLE_BYTES);
  });

  it('conflicts on an id that is already uploaded and on another media or variant', async () => {
    const { user, medias } = await newOwner(context, 2);
    const id = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(100));
    const reuse = await presign(context, user.sessionToken, {
      id,
      mediaId: medias[0]!.mediaId,
      variant: 'original',
      sizeBytes: 100,
    });
    expect(errorCodeOf(reuse)).toBe('ID_CONFLICT');
    const pendingId = randomId();
    await presign(
      context,
      user.sessionToken,
      presignBody({ id: pendingId, mediaId: medias[1]!.mediaId, sizeBytes: 100 }),
    );
    const otherMedia = await presign(
      context,
      user.sessionToken,
      presignBody({ id: pendingId, mediaId: medias[0]!.mediaId, sizeBytes: 100 }),
    );
    expect(errorCodeOf(otherMedia)).toBe('ID_CONFLICT');
    const otherVariant = await presign(
      context,
      user.sessionToken,
      presignBody({ id: pendingId, mediaId: medias[1]!.mediaId, variant: 'thumbnail', sizeBytes: 100 }),
    );
    expect(errorCodeOf(otherVariant)).toBe('ID_CONFLICT');
  });

  it('returns the uploaded object unchanged on a second confirm', async () => {
    const { user, medias } = await newOwner(context);
    const id = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, bytesOfSize(100));
    const before = await objectRow(context.db, id);
    const again = await confirm(context, user.sessionToken, id);
    expect(again.status).toBe(200);
    expect(objectOf(again).state).toBe('uploaded');
    const after = await objectRow(context.db, id);
    expect(after?.uploadedAt?.getTime()).toBe(before?.uploadedAt?.getTime());
    expect(await usedBytesOf(context.db, user.userId)).toBe(100);
  });
});

describe('6. replacement', () => {
  it('moves the previous uploaded thumbnail to deleting when the next one is confirmed', async () => {
    const { user, medias } = await newOwner(context);
    const mediaId = medias[0]!.mediaId;
    const first = await uploadSingle(context, user.sessionToken, mediaId, bytesOfSize(100), 'thumbnail');
    const second = await uploadSingle(context, user.sessionToken, mediaId, bytesOfSize(200), 'thumbnail');
    const firstRow = await objectRow(context.db, first);
    const secondRow = await objectRow(context.db, second);
    expect(firstRow?.state).toBe('deleting');
    expect(firstRow?.deleteAfter).not.toBeNull();
    expect(secondRow?.state).toBe('uploaded');
    expect(await usedBytesOf(context.db, user.userId)).toBe(300);
  });
});

describe('7. access', () => {
  it('answers 404 to another user and to an unknown id with the same body', async () => {
    const owner = await newOwner(context);
    const stranger = await newOwner(context);
    const id = await uploadSingle(
      context,
      owner.user.sessionToken,
      owner.medias[0]!.mediaId,
      bytesOfSize(100),
    );

    const foreignPresign = await presign(
      context,
      stranger.user.sessionToken,
      presignBody({ mediaId: owner.medias[0]!.mediaId, sizeBytes: 100 }),
    );
    expect(foreignPresign.status).toBe(404);

    const foreignDownload = await download(context, stranger.user.sessionToken, id);
    const unknownDownload = await download(context, stranger.user.sessionToken, randomId());
    expect(foreignDownload.status).toBe(404);
    expect(unknownDownload.status).toBe(404);
    const strip = (result: { json: unknown }) => {
      const { error } = errorResponseSchema.parse(result.json);
      return { code: error.code, message: error.message, details: error.details };
    };
    expect(strip(foreignDownload)).toEqual(strip(unknownDownload));
    expect(strip(foreignPresign)).toEqual(strip(unknownDownload));

    expect((await confirm(context, stranger.user.sessionToken, id)).status).toBe(404);
    expect((await parts(context, stranger.user.sessionToken, id)).status).toBe(404);
    expect((await confirm(context, stranger.user.sessionToken, randomId())).status).toBe(404);
  });

  it('lets a share recipient download but not confirm', async () => {
    const owner = await newOwner(context);
    const recipient = await newOwner(context);
    const id = await uploadSingle(
      context,
      owner.user.sessionToken,
      owner.medias[0]!.mediaId,
      bytesOfSize(100),
    );
    const shared = randomId();
    const now = new Date();
    await context.db
      .insertInto('collections')
      .values({
        id: shared,
        ownerId: owner.user.userId,
        kind: 'shared',
        keyByOwner: randomBytes(73),
        metadataBlob: randomBytes(64),
        createdAt: now,
        updatedAt: now,
        metaUpdatedAt: now,
      })
      .execute();
    await context.db
      .insertInto('entryMemberships')
      .values({
        collectionId: shared,
        entryId: owner.medias[0]!.entryId,
        entryKeyByCollection: randomBytes(73),
        seq: 1,
        addedAt: now,
      })
      .execute();
    expect((await download(context, recipient.user.sessionToken, id)).status).toBe(404);
    await context.db
      .insertInto('collectionShares')
      .values({
        collectionId: shared,
        recipientId: recipient.user.userId,
        sealedCollectionKey: randomBytes(73),
        createdAt: now,
      })
      .execute();
    expect((await download(context, recipient.user.sessionToken, id)).status).toBe(200);
    expect((await confirm(context, recipient.user.sessionToken, id)).status).toBe(404);
  });

  it('requires a session', async () => {
    const result = await call(context.app, 'POST', ROUTES.objectsPresign(), {
      body: presignBody({ mediaId: randomId(), sizeBytes: SINGLE_BYTES }),
    });
    expect(result.status).toBe(401);
  });

  it('rejects a malformed presign body and a too-small size', async () => {
    const { user, medias } = await newOwner(context);
    const small = await presign(
      context,
      user.sessionToken,
      presignBody({ mediaId: medias[0]!.mediaId, sizeBytes: 39 }),
    );
    expect(small.status).toBe(422);
    const malformed = await presign(context, user.sessionToken, { id: 'x' });
    expect(errorCodeOf(malformed)).toBe('VALIDATION_FAILED');
  });
});

describe('8. oversize', () => {
  it('answers 413 before any other check and creates no row', async () => {
    const { user, medias } = await newOwner(context);
    const stranger = await newOwner(context);
    const sizeBytes = context.settings.limits.maxUploadBytes + 1;
    const own = await presign(
      context,
      user.sessionToken,
      presignBody({ mediaId: medias[0]!.mediaId, sizeBytes }),
    );
    expect(own.status).toBe(413);
    expect(errorCodeOf(own)).toBe('PAYLOAD_TOO_LARGE');
    const foreign = await presign(
      context,
      stranger.user.sessionToken,
      presignBody({ mediaId: medias[0]!.mediaId, sizeBytes }),
    );
    expect(foreign.status).toBe(413);
    const rows = await context.db.selectFrom('objects').select('id').where('ownerId', 'in', [user.userId, stranger.user.userId]).execute();
    expect(rows).toHaveLength(0);
  });
});

describe('12. presigned URL host', () => {
  it('starts every URL with S3_PUBLIC_ENDPOINT', async () => {
    const { user, medias } = await newOwner(context, 2);
    const single = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
      ),
    );
    const multipart = presignOf(
      await presign(
        context,
        user.sessionToken,
        presignBody({ mediaId: medias[1]!.mediaId, sizeBytes: PART_BYTES + 1 }),
      ),
    );
    const prefix = context.settings.storage.publicEndpoint;
    if (single.mode !== 'single' || multipart.mode !== 'multipart') throw new Error('modes');
    expect(single.url.startsWith(prefix)).toBe(true);
    for (const entry of multipart.partUrls) expect(entry.url.startsWith(prefix)).toBe(true);
  });

  it('never returns the internal endpoint when the two endpoints differ', async () => {
    const separate = await createObjectsContext(testDatabase, {
      env: { S3_PUBLIC_ENDPOINT: LOCALHOST_ENDPOINT },
    });
    try {
      const { user, medias } = await newOwner(separate);
      const id = randomId();
      const bytes = bytesOfSize(SINGLE_BYTES);
      const issued = presignOf(
        await presign(
          separate,
          user.sessionToken,
          presignBody({ id, mediaId: medias[0]!.mediaId, sizeBytes: SINGLE_BYTES }),
        ),
      );
      if (issued.mode !== 'single') throw new Error('expected single');
      expect(issued.url.startsWith(LOCALHOST_ENDPOINT)).toBe(true);
      expect(issued.url).not.toContain(LOOPBACK_ADDRESS);
      expect((await putBytes(issued.url, bytes)).status).toBe(200);
      expect((await confirm(separate, user.sessionToken, id)).status).toBe(200);
      const link = (await download(separate, user.sessionToken, id)).json as { url: string };
      expect(link.url.startsWith(LOCALHOST_ENDPOINT)).toBe(true);
      expect(link.url).not.toContain(LOOPBACK_ADDRESS);
      expect((await fetchBytes(link.url)).bytes.equals(bytes)).toBe(true);
    } finally {
      await separate.close();
    }
  });
});

describe('B-064 bucket objects are STREAM ciphertext', () => {
  it('stores an object that starts with KDKS and not the plaintext', async () => {
    const sodium = await createNodeSodium();
    const plaintext = Buffer.from('a private journal photo that must not be readable');
    const key = randomBytes(32);
    const encryptor = createStreamEncryptor(sodium, key, plaintext.length);
    const ciphertext = Buffer.concat([encryptor.header, encryptor.encryptNext(plaintext)]);
    expect(ciphertext).toHaveLength(encryptor.layout.ciphertextSize);

    const { user, medias } = await newOwner(context);
    const id = await uploadSingle(context, user.sessionToken, medias[0]!.mediaId, ciphertext);
    const row = await objectRow(context.db, id);
    const stream = await context.store.getObjectStream(row!.objectKey, null);
    const chunks: Buffer[] = [];
    for await (const chunk of stream.body) chunks.push(Buffer.from(chunk as Uint8Array));
    const stored = Buffer.concat(chunks);
    expect([...stored.subarray(0, STREAM_MAGIC.length)]).toEqual([...STREAM_MAGIC]);
    expect(stored.equals(ciphertext)).toBe(true);
    expect(stored.includes(plaintext)).toBe(false);
  });
});

describe('logs', () => {
  it('names the decisions without secrets or presigned URL queries', async () => {
    const text = context.logs.text();
    expect(text).toContain('object.presigned');
    expect(text).toContain('object.confirmed');
    expect(text).not.toContain(SECRET_QUERY_MARKER);
    expect(text).not.toContain(context.settings.storage.secretAccessKey);
  });
});
