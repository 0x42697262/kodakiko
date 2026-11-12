import { randomBytes } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Kysely } from 'kysely';
import {
  objectDto,
  partsResponse,
  presignResponse,
  ROUTES,
  type ObjectDto,
  type PartsResponse,
  type PresignResponse,
} from '@kodakiko/contract';
import type { Database } from '../../src/adapters/db/schema.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { createS3ObjectStore } from '../../src/adapters/storage/s3-object-store.ts';
import { buildApp } from '../../src/app.ts';
import type { Settings } from '../../src/config/settings.ts';
import type { ObjectVariant } from '../../src/domain/enums.ts';
import { randomId } from '../../src/platform/ids.ts';
import { bucketCheck } from '../../src/services/readiness.ts';
import {
  call,
  createLogCapture,
  createMutableClock,
  type CallResult,
  type LogCapture,
  type MutableClock,
} from './auth-helpers.ts';
import { insertMediaFixture, insertUserFixture, type UserFixture } from './fixtures.ts';
import { garageStorageEnv, testSettings, type TestDatabase } from './helpers.ts';

export const MIB = 1_048_576;
export const PART_BYTES = 5 * MIB;
export const OCTET_STREAM = 'application/octet-stream';
const LOG_LEVEL_INFO = 'info';

export type ObjectsContext = {
  readonly app: FastifyInstance;
  readonly db: Kysely<Database>;
  readonly clock: MutableClock;
  readonly settings: Settings;
  readonly store: ObjectStore;
  readonly logs: LogCapture;
  close(): Promise<void>;
};

export type ObjectsContextOptions = {
  readonly env?: Readonly<Record<string, string>>;
  readonly wrapStore?: (store: ObjectStore) => ObjectStore;
};

export async function createObjectsContext(
  testDatabase: TestDatabase,
  options: ObjectsContextOptions = {},
): Promise<ObjectsContext> {
  const settings = testSettings({
    ...garageStorageEnv(),
    RATE_LIMITS_ENABLED: 'false',
    LOG_LEVEL: LOG_LEVEL_INFO,
    SINGLE_PART_MAX_BYTES: String(PART_BYTES),
    MULTIPART_PART_BYTES: String(PART_BYTES),
    ...options.env,
  });
  const created = createS3ObjectStore(settings.storage);
  const store = options.wrapStore === undefined ? created : options.wrapStore(created);
  const clock = createMutableClock();
  const logs = createLogCapture();
  const app = await buildApp({
    settings,
    db: testDatabase.db,
    clock,
    readinessChecks: [bucketCheck(store)],
    objectStore: store,
    logStream: logs.stream,
  });
  await app.ready();
  return { app, db: testDatabase.db, clock, settings, store, logs, close: () => app.close() };
}

export type OwnerWithMedia = {
  readonly user: UserFixture;
  readonly medias: readonly { readonly entryId: string; readonly mediaId: string }[];
};

export async function newOwner(
  context: ObjectsContext,
  mediaCount: number = 1,
): Promise<OwnerWithMedia> {
  const user = await insertUserFixture(context.db);
  const medias = [];
  for (let position = 0; position < mediaCount; position += 1) {
    medias.push(
      await insertMediaFixture(context.db, {
        ownerId: user.userId,
        homeCollectionId: user.homeCollectionId,
      }),
    );
  }
  return { user, medias };
}

export type PresignInput = {
  readonly id?: string;
  readonly mediaId: string;
  readonly variant?: ObjectVariant;
  readonly sizeBytes: number;
};

export function presignBody(input: PresignInput): Record<string, unknown> {
  return {
    id: input.id ?? randomId(),
    mediaId: input.mediaId,
    variant: input.variant ?? 'original',
    sizeBytes: input.sizeBytes,
  };
}

export function presign(
  context: ObjectsContext,
  token: string,
  body: Record<string, unknown>,
): Promise<CallResult> {
  return call(context.app, 'POST', ROUTES.objectsPresign(), { token, body });
}

export function confirm(context: ObjectsContext, token: string, id: string): Promise<CallResult> {
  return call(context.app, 'POST', ROUTES.objectConfirm(id), { token });
}

export function parts(context: ObjectsContext, token: string, id: string): Promise<CallResult> {
  return call(context.app, 'GET', ROUTES.objectParts(id), { token });
}

export function download(context: ObjectsContext, token: string, id: string): Promise<CallResult> {
  return call(context.app, 'GET', ROUTES.objectDownload(id), { token });
}

export function presignOf(result: CallResult): PresignResponse {
  return presignResponse.parse(result.json);
}

export function partsOf(result: CallResult): PartsResponse {
  return partsResponse.parse(result.json);
}

export function objectOf(result: CallResult): ObjectDto {
  return objectDto.parse(result.json);
}

export function bytesOfSize(size: number): Buffer {
  return randomBytes(size);
}

export async function putBytes(url: string, bytes: Uint8Array): Promise<Response> {
  return fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': OCTET_STREAM },
    body: bytes,
  });
}

export async function fetchBytes(url: string, range?: string): Promise<{ status: number; bytes: Buffer }> {
  const response = await fetch(url, range === undefined ? {} : { headers: { Range: range } });
  return { status: response.status, bytes: Buffer.from(await response.arrayBuffer()) };
}

export async function usedBytesOf(db: Kysely<Database>, userId: string): Promise<number> {
  const row = await db
    .selectFrom('users')
    .select('usedBytes')
    .where('id', '=', userId)
    .executeTakeFirstOrThrow();
  return row.usedBytes;
}

export function objectRow(db: Kysely<Database>, id: string) {
  return db.selectFrom('objects').selectAll().where('id', '=', id).executeTakeFirst();
}

export async function uploadSingle(
  context: ObjectsContext,
  token: string,
  mediaId: string,
  bytes: Uint8Array,
  variant: ObjectVariant = 'original',
): Promise<string> {
  const id = randomId();
  const issued = presignOf(
    await presign(context, token, presignBody({ id, mediaId, variant, sizeBytes: bytes.length })),
  );
  if (issued.mode !== 'single') throw new Error('expected a single upload');
  const put = await putBytes(issued.url, bytes);
  if (!put.ok) throw new Error(`PUT failed with ${put.status}`);
  const confirmed = await confirm(context, token, id);
  if (confirmed.status !== 200) throw new Error(`confirm failed with ${confirmed.status}`);
  return id;
}
