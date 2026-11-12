import { randomBytes } from 'node:crypto';
import { afterAll, describe, expect, it } from 'vitest';
import { applyBucketCors } from '../../src/adapters/storage/bucket-cors.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { createS3ObjectStore } from '../../src/adapters/storage/s3-object-store.ts';
import type { Settings } from '../../src/config/settings.ts';
import { randomId } from '../../src/platform/ids.ts';
import { testSettings } from '../integration/helpers.ts';

const MIB = 1_048_576;
const FIRST_PART_BYTES = 5 * MIB;
const LAST_PART_BYTES = 100;
const SINGLE_BYTES = 1000;
const URL_TTL_SECONDS = 600;
const RANGE_START = 10;
const RANGE_END = 19;
const PARTIAL_CONTENT = 206;
const OCTET_STREAM = 'application/octet-stream';
const FORBIDDEN = 403;
const PROVIDER_OWNER = 'PPPPPPPPPPPPPPPPPPPPPP';

export type ProviderConfig = {
  readonly endpoint: string;
  readonly publicEndpoint: string;
  readonly region: string;
  readonly bucket: string;
  readonly accessKeyId: string;
  readonly secretAccessKey: string;
  readonly forcePathStyle: boolean;
  readonly origin: string;
};

function storageOf(config: ProviderConfig): Settings['storage'] {
  return testSettings({
    S3_ENDPOINT: config.endpoint,
    S3_PUBLIC_ENDPOINT: config.publicEndpoint,
    S3_REGION: config.region,
    S3_BUCKET: config.bucket,
    S3_ACCESS_KEY_ID: config.accessKeyId,
    S3_SECRET_ACCESS_KEY: config.secretAccessKey,
    S3_FORCE_PATH_STYLE: String(config.forcePathStyle),
    REQUIRE_HTTPS: 'false',
    PUBLIC_BASE_URL: 'http://localhost:8080',
  }).storage;
}

async function putTo(url: string, bytes: Uint8Array): Promise<Response> {
  return fetch(url, { method: 'PUT', headers: { 'Content-Type': OCTET_STREAM }, body: bytes });
}

async function readAll(stream: NodeJS.ReadableStream): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk as Uint8Array));
  return Buffer.concat(chunks);
}

export function describeProviderConformance(config: ProviderConfig): void {
  const storage = storageOf(config);
  const store: ObjectStore = createS3ObjectStore(storage);
  const created: string[] = [];

  function newKey(): string {
    const key = `v1/${PROVIDER_OWNER}/${randomId()}`;
    created.push(key);
    return key;
  }

  afterAll(async () => {
    for (const key of created) await store.deleteObject(key);
  });

  describe('provider conformance', () => {
    it('reaches the bucket', async () => {
      expect(await store.headBucket()).toBe(true);
    });

    it('uploads through a presigned PUT and reads the size with HeadObject', async () => {
      const key = newKey();
      const bytes = randomBytes(SINGLE_BYTES);
      const url = await store.presignPut(key, SINGLE_BYTES, URL_TTL_SECONDS);
      expect(url.startsWith(config.publicEndpoint)).toBe(true);
      expect((await putTo(url, bytes)).status).toBe(200);
      const head = await store.headObject(key);
      expect(head?.sizeBytes).toBe(SINGLE_BYTES);
      expect(await store.headObject(`${key}-missing`)).toBeNull();
    });

    it('rejects a PUT whose size differs from the signed size', async () => {
      const key = newKey();
      const url = await store.presignPut(key, SINGLE_BYTES, URL_TTL_SECONDS);
      expect((await putTo(url, randomBytes(SINGLE_BYTES - 1))).status).toBe(FORBIDDEN);
      expect((await putTo(url, randomBytes(SINGLE_BYTES + 1))).status).toBe(FORBIDDEN);
    });

    it('serves a Range GET through a presigned URL', async () => {
      const key = newKey();
      const bytes = randomBytes(SINGLE_BYTES);
      await putTo(await store.presignPut(key, SINGLE_BYTES, URL_TTL_SECONDS), bytes);
      const response = await fetch(await store.presignGet(key, URL_TTL_SECONDS), {
        headers: { Range: `bytes=${RANGE_START}-${RANGE_END}` },
      });
      expect(response.status).toBe(PARTIAL_CONTENT);
      const body = Buffer.from(await response.arrayBuffer());
      expect(body.equals(bytes.subarray(RANGE_START, RANGE_END + 1))).toBe(true);
    });

    it('completes a presigned multipart upload after ListParts', async () => {
      const key = newKey();
      const bytes = randomBytes(FIRST_PART_BYTES + LAST_PART_BYTES);
      const uploadId = await store.createMultipartUpload(key);
      const first = await putTo(await store.presignPart(key, uploadId, 1, FIRST_PART_BYTES, URL_TTL_SECONDS), bytes.subarray(0, FIRST_PART_BYTES));
      expect(first.status).toBe(200);
      expect(await store.listParts(key, uploadId)).toMatchObject([{ partNumber: 1, sizeBytes: FIRST_PART_BYTES }]);
      const second = await putTo(await store.presignPart(key, uploadId, 2, LAST_PART_BYTES, URL_TTL_SECONDS), bytes.subarray(FIRST_PART_BYTES));
      expect(second.status).toBe(200);
      const listed = await store.listParts(key, uploadId);
      expect(listed.map((part) => part.partNumber)).toEqual([1, 2]);
      await store.completeMultipartUpload(
        key,
        uploadId,
        listed.map((part) => ({ partNumber: part.partNumber, etag: part.etag })),
      );
      expect((await store.headObject(key))?.sizeBytes).toBe(bytes.length);
    });

    it('streams an object and a range with getObjectStream', async () => {
      const key = newKey();
      const bytes = randomBytes(SINGLE_BYTES);
      await putTo(await store.presignPut(key, SINGLE_BYTES, URL_TTL_SECONDS), bytes);
      const whole = await store.getObjectStream(key, null);
      expect(whole.sizeBytes).toBe(SINGLE_BYTES);
      expect(whole.totalSizeBytes).toBe(SINGLE_BYTES);
      expect((await readAll(whole.body)).equals(bytes)).toBe(true);
      const ranged = await store.getObjectStream(key, { start: RANGE_START, end: RANGE_END });
      expect(ranged.sizeBytes).toBe(RANGE_END - RANGE_START + 1);
      expect(ranged.totalSizeBytes).toBe(SINGLE_BYTES);
      expect((await readAll(ranged.body)).equals(bytes.subarray(RANGE_START, RANGE_END + 1))).toBe(true);
    });

    it('maps a missing key to ObjectMissingError', async () => {
      await expect(store.getObjectStream(`${newKey()}-missing`, null)).rejects.toMatchObject({
        name: 'ObjectMissingError',
      });
    });

    it('answers a CORS preflight for Range after PutBucketCors', async () => {
      await applyBucketCors(storage, config.origin);
      const key = newKey();
      const url = await store.presignGet(key, URL_TTL_SECONDS);
      const response = await fetch(url, {
        method: 'OPTIONS',
        headers: {
          Origin: config.origin,
          'Access-Control-Request-Method': 'GET',
          'Access-Control-Request-Headers': 'range',
        },
      });
      expect(response.status).toBeLessThan(300);
      expect(response.headers.get('access-control-allow-origin')).toBe(config.origin);
      expect(response.headers.get('access-control-allow-headers')?.toLowerCase()).toContain('range');
    });

    it('deletes an object and treats a second delete as success', async () => {
      const key = newKey();
      await putTo(await store.presignPut(key, SINGLE_BYTES, URL_TTL_SECONDS), randomBytes(SINGLE_BYTES));
      await store.deleteObject(key);
      expect(await store.headObject(key)).toBeNull();
      await expect(store.deleteObject(key)).resolves.toBeUndefined();
    });
  });
}
