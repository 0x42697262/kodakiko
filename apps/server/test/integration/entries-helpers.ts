import { randomBytes } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import type { Kysely } from 'kysely';
import {
  collectionDto,
  diffPage,
  encodeBytes,
  entryDto,
  mediaDto,
  ROUTES,
  syncStateResponse,
  type CollectionDto,
  type DiffPage,
  type EntryDto,
  type MediaDto,
  type SyncStateResponse,
} from '@kodakiko/contract';
import { createFailedSignInTracker } from '../../src/adapters/memory/failed-sign-in-tracker.ts';
import { objectsRepository } from '../../src/adapters/db/objects-repository.ts';
import type { Database } from '../../src/adapters/db/schema.ts';
import type { ObjectStore } from '../../src/adapters/storage/object-store.ts';
import { createS3ObjectStore } from '../../src/adapters/storage/s3-object-store.ts';
import { buildApp } from '../../src/app.ts';
import type { CollectionKind } from '../../src/domain/enums.ts';
import { randomId } from '../../src/platform/ids.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import { createAuthService } from '../../src/services/auth-service.ts';
import { createEntriesService, type EntriesService } from '../../src/services/entries-service.ts';
import { createSequenceService } from '../../src/services/sequence-service.ts';
import { bucketCheck } from '../../src/services/readiness.ts';
import { createNodeSodium } from '../../../../packages/core/test/sodium-node.ts';
import {
  createLogCapture,
  createMutableClock,
  registerAccount,
  type AuthContext,
  type CallResult,
} from './auth-helpers.ts';
import { garageStorageEnv, testSettings, type TestDatabase } from './helpers.ts';

export const WRAP_BYTES = 73;
export const BLOB_BYTES = 64;
export const MIB = 1_048_576;
export const DAY_MS = 86_400_000;
export const SECOND_MS = 1000;
const MINUTE_MS = 60_000;
const TRACKER_MAX_ENTRIES = 100;
const USERNAME_RANDOM_BYTES = 6;
const LOG_LEVEL_INFO = 'info';
const AUTH_BEARER = 'Bearer ';

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE';

export type EntriesContext = AuthContext & {
  readonly store: ObjectStore;
  readonly entriesService: EntriesService;
};

export type EntriesContextOptions = {
  readonly env?: Readonly<Record<string, string>>;
  readonly db?: Kysely<Database>;
};

export async function createEntriesContext(
  testDatabase: TestDatabase,
  options: EntriesContextOptions = {},
): Promise<EntriesContext> {
  const settings = testSettings({
    ...garageStorageEnv(),
    RATE_LIMITS_ENABLED: 'false',
    LOG_LEVEL: LOG_LEVEL_INFO,
    ...options.env,
  });
  const db = options.db ?? testDatabase.db;
  const store = createS3ObjectStore(settings.storage);
  const clock = createMutableClock();
  const logs = createLogCapture();
  const app = await buildApp({
    settings,
    db,
    clock,
    readinessChecks: [bucketCheck(store)],
    objectStore: store,
    logStream: logs.stream,
  });
  await app.ready();
  const inviteService = createAuthService({
    db,
    settings,
    clock,
    failedSignIns: createFailedSignInTracker({
      windowMs: MINUTE_MS,
      maxEntries: TRACKER_MAX_ENTRIES,
    }),
    logger: silentLogger,
    checkOpenSignUpRate: async () => true,
  });
  const entriesService = createEntriesService({
    db,
    settings,
    clock,
    objectsRepository,
    sequenceService: createSequenceService(),
    logger: app.log,
  });
  return {
    app,
    db,
    clock,
    settings,
    sodium: await createNodeSodium(),
    logs,
    inviteService,
    store,
    entriesService,
    close: () => app.close(),
  };
}

export type EntriesUser = {
  readonly token: string;
  readonly userId: string;
  readonly homeId: string;
  readonly username: string;
};

export async function newUser(context: EntriesContext): Promise<EntriesUser> {
  const username = `u${randomBytes(USERNAME_RANDOM_BYTES).toString('hex')}`;
  const account = await registerAccount(context, username);
  return {
    token: account.token,
    userId: account.material.request.userId,
    homeId: account.material.request.homeCollection.id,
    username,
  };
}

export async function send(
  app: FastifyInstance,
  method: HttpMethod,
  url: string,
  options: { readonly token?: string; readonly body?: unknown } = {},
): Promise<CallResult> {
  const response = await app.inject({
    method,
    url,
    ...(options.token === undefined
      ? {}
      : { headers: { authorization: `${AUTH_BEARER}${options.token}` } }),
    ...(options.body === undefined ? {} : { payload: options.body as object }),
  });
  return {
    status: response.statusCode,
    json: response.body === '' ? null : (response.json() as unknown),
    raw: response.body,
  };
}

export function randomBlob(size: number = BLOB_BYTES): Buffer {
  return randomBytes(size);
}

export function wrapOf(): string {
  return encodeBytes(randomBytes(WRAP_BYTES));
}

export type EntryInput = {
  readonly id?: string;
  readonly homeId: string;
  readonly purpose?: 'journal' | 'link_copy';
  readonly collectionIds?: readonly string[];
  readonly mediaCount?: number;
  readonly blob?: Buffer;
};

export type EntryDraft = {
  readonly id: string;
  readonly mediaIds: readonly string[];
  readonly blob: Buffer;
  readonly body: Record<string, unknown>;
};

const DEFAULT_MEDIA_COUNT = 1;

export function entryDraft(input: EntryInput): EntryDraft {
  const id = input.id ?? randomId();
  const blob = input.blob ?? randomBlob();
  const collectionIds = input.collectionIds ?? [input.homeId];
  const mediaIds = Array.from({ length: input.mediaCount ?? DEFAULT_MEDIA_COUNT }, () =>
    randomId(),
  );
  return {
    id,
    mediaIds,
    blob,
    body: {
      id,
      purpose: input.purpose ?? 'journal',
      metadataBlob: encodeBytes(blob),
      memberships: collectionIds.map((collectionId) => ({
        collectionId,
        entryKeyByCollection: wrapOf(),
      })),
      media: mediaIds.map((mediaId) => ({ mediaId, mediaKeyByEntry: wrapOf() })),
    },
  };
}

export function postEntry(
  context: EntriesContext,
  token: string,
  body: Record<string, unknown>,
): Promise<CallResult> {
  return send(context.app, 'POST', ROUTES.entries(), { token, body });
}

export async function createEntryFor(
  context: EntriesContext,
  user: EntriesUser,
  input: Partial<EntryInput> = {},
): Promise<EntryDraft> {
  const draft = entryDraft({ homeId: user.homeId, ...input });
  const result = await postEntry(context, user.token, draft.body);
  if (result.status !== 201) throw new Error(`create failed with ${result.status}: ${result.raw}`);
  return draft;
}

export function getEntry(context: EntriesContext, token: string, id: string): Promise<CallResult> {
  return send(context.app, 'GET', ROUTES.entry(id), { token });
}

export function patchEntry(
  context: EntriesContext,
  token: string,
  id: string,
  blob: Buffer,
  baseRevision: number,
): Promise<CallResult> {
  return send(context.app, 'PATCH', ROUTES.entry(id), {
    token,
    body: { metadataBlob: encodeBytes(blob), baseRevision },
  });
}

export function trashEntry(context: EntriesContext, token: string, id: string): Promise<CallResult> {
  return send(context.app, 'POST', ROUTES.entryTrash(id), { token });
}

export function restoreEntry(
  context: EntriesContext,
  token: string,
  id: string,
): Promise<CallResult> {
  return send(context.app, 'POST', ROUTES.entryRestore(id), { token });
}

export function deleteEntry(
  context: EntriesContext,
  token: string,
  id: string,
): Promise<CallResult> {
  return send(context.app, 'DELETE', ROUTES.entry(id), { token });
}

export function addMedia(
  context: EntriesContext,
  token: string,
  entryId: string,
  body: { readonly mediaId: string; readonly mediaKeyByEntry: string },
): Promise<CallResult> {
  return send(context.app, 'POST', ROUTES.entryMedia(entryId), { token, body });
}

export function removeMedia(
  context: EntriesContext,
  token: string,
  mediaId: string,
): Promise<CallResult> {
  return send(context.app, 'DELETE', ROUTES.media(mediaId), { token });
}

export function diff(
  context: EntriesContext,
  token: string,
  collectionId: string,
  since: number,
  limit?: number,
): Promise<CallResult> {
  const query = limit === undefined ? `since=${since}` : `since=${since}&limit=${limit}`;
  return send(context.app, 'GET', `${ROUTES.collectionDiff(collectionId)}?${query}`, { token });
}

export function syncState(context: EntriesContext, token: string): Promise<CallResult> {
  return send(context.app, 'GET', ROUTES.syncState(), { token });
}

export function listCollections(
  context: EntriesContext,
  token: string,
  query: string = '',
): Promise<CallResult> {
  return send(context.app, 'GET', `${ROUTES.collections()}${query}`, { token });
}

export function getCollection(
  context: EntriesContext,
  token: string,
  id: string,
): Promise<CallResult> {
  return send(context.app, 'GET', ROUTES.collection(id), { token });
}

export function renameCollection(
  context: EntriesContext,
  token: string,
  id: string,
  blob: Buffer,
): Promise<CallResult> {
  return send(context.app, 'PATCH', ROUTES.collection(id), {
    token,
    body: { metadataBlob: encodeBytes(blob) },
  });
}

export function entryOf(result: CallResult): EntryDto {
  return entryDto.parse(result.json);
}

export function mediaOf(result: CallResult): MediaDto {
  return mediaDto.parse(result.json);
}

export function diffOf(result: CallResult): DiffPage {
  return diffPage.parse(result.json);
}

export function collectionOf(result: CallResult): CollectionDto {
  return collectionDto.parse(result.json);
}

export function syncStateOf(result: CallResult): SyncStateResponse {
  return syncStateResponse.parse(result.json);
}

export async function membershipSeq(
  db: Kysely<Database>,
  collectionId: string,
  entryId: string,
): Promise<number> {
  const row = await db
    .selectFrom('entryMemberships')
    .select('seq')
    .where('collectionId', '=', collectionId)
    .where('entryId', '=', entryId)
    .executeTakeFirstOrThrow();
  return row.seq;
}

export async function collectionLastSeq(db: Kysely<Database>, id: string): Promise<number> {
  const row = await db
    .selectFrom('collections')
    .select('lastSeq')
    .where('id', '=', id)
    .executeTakeFirstOrThrow();
  return row.lastSeq;
}

export function entryRow(db: Kysely<Database>, id: string) {
  return db.selectFrom('entries').selectAll().where('id', '=', id).executeTakeFirst();
}

export function tombstonesOf(db: Kysely<Database>, collectionId: string) {
  return db
    .selectFrom('membershipTombstones')
    .selectAll()
    .where('collectionId', '=', collectionId)
    .orderBy('seq')
    .execute();
}

export async function insertCollectionFixture(
  db: Kysely<Database>,
  options: { readonly ownerId: string; readonly kind: CollectionKind },
): Promise<string> {
  const now = new Date();
  const id = randomId();
  await db
    .insertInto('collections')
    .values({
      id,
      ownerId: options.ownerId,
      kind: options.kind,
      keyByOwner: randomBytes(WRAP_BYTES),
      metadataBlob: randomBytes(BLOB_BYTES),
      createdAt: now,
      updatedAt: now,
      metaUpdatedAt: now,
    })
    .execute();
  return id;
}

export type SharedCollectionFixture = {
  readonly collectionId: string;
  readonly sealedKey: Buffer;
};

export async function insertSharedCollectionFixture(
  db: Kysely<Database>,
  options: { readonly ownerId: string; readonly recipientId: string },
): Promise<SharedCollectionFixture> {
  const collectionId = await insertCollectionFixture(db, {
    ownerId: options.ownerId,
    kind: 'shared',
  });
  const sealedKey = randomBytes(WRAP_BYTES);
  await db
    .insertInto('collectionShares')
    .values({
      collectionId,
      recipientId: options.recipientId,
      sealedCollectionKey: sealedKey,
      createdAt: new Date(),
    })
    .execute();
  return { collectionId, sealedKey };
}
