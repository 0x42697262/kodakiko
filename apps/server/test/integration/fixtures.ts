import { randomBytes } from 'node:crypto';
import type { Kysely } from 'kysely';
import { SESSION_TOKEN_PREFIX } from '@kodakiko/contract';
import type { Database } from '../../src/adapters/db/schema.ts';
import { sha256OfText } from '../../src/platform/hash.ts';
import { randomId, randomToken } from '../../src/platform/ids.ts';

const USERNAME_RANDOM_BYTES = 8;
const HASH_BYTES = 32;
const SALT_BYTES = 16;
const WRAPPED_KEY_BYTES = 73;
const BLOB_BYTES = 64;
const KDF_OPS = 32;
const KDF_MEM_BYTES = 134_217_728;
const TOKEN_RANDOM_BYTES = 32;
const SESSION_DAYS = 30;
const DAY_MS = 86_400_000;
const FIXTURE_DEVICE = 'Fixture';

export type UserFixture = {
  readonly userId: string;
  readonly homeCollectionId: string;
  readonly sessionToken: string;
};

export type MediaFixture = { readonly entryId: string; readonly mediaId: string };

export async function insertUserFixture(db: Kysely<Database>): Promise<UserFixture> {
  const now = new Date();
  const userId = randomId();
  const homeCollectionId = randomId();
  const sessionToken = `${SESSION_TOKEN_PREFIX}${randomToken(TOKEN_RANDOM_BYTES)}`;
  await db
    .insertInto('users')
    .values({
      id: userId,
      username: `u${randomBytes(USERNAME_RANDOM_BYTES).toString('hex')}`,
      role: 'member',
      status: 'active',
      loginKeyHash: randomBytes(HASH_BYTES),
      recoveryLoginKeyHash: randomBytes(HASH_BYTES),
      kdfSalt: randomBytes(SALT_BYTES),
      kdfOpsLimit: KDF_OPS,
      kdfMemLimitBytes: KDF_MEM_BYTES,
      masterKeyByKek: randomBytes(WRAPPED_KEY_BYTES),
      masterKeyByRecovery: randomBytes(WRAPPED_KEY_BYTES),
      recoveryKeyByMaster: randomBytes(WRAPPED_KEY_BYTES),
      publicKey: randomBytes(HASH_BYTES),
      privateKeyByMaster: randomBytes(WRAPPED_KEY_BYTES),
      quotaBytes: null,
      deletionDueAt: null,
      createdAt: now,
      updatedAt: now,
    })
    .execute();
  await db
    .insertInto('collections')
    .values({
      id: homeCollectionId,
      ownerId: userId,
      kind: 'home',
      keyByOwner: randomBytes(WRAPPED_KEY_BYTES),
      metadataBlob: randomBytes(BLOB_BYTES),
      createdAt: now,
      updatedAt: now,
      metaUpdatedAt: now,
    })
    .execute();
  await db
    .insertInto('sessions')
    .values({
      id: randomId(),
      userId,
      tokenHash: sha256OfText(sessionToken),
      deviceLabel: FIXTURE_DEVICE,
      createdAt: now,
      lastUsedAt: now,
      expiresAt: new Date(now.getTime() + SESSION_DAYS * DAY_MS),
    })
    .execute();
  return { userId, homeCollectionId, sessionToken };
}

export async function insertMediaFixture(
  db: Kysely<Database>,
  options: { readonly ownerId: string; readonly homeCollectionId: string },
): Promise<MediaFixture> {
  const now = new Date();
  const entryId = randomId();
  const mediaId = randomId();
  await db
    .insertInto('entries')
    .values({
      id: entryId,
      ownerId: options.ownerId,
      purpose: 'journal',
      revision: 1,
      metadataBlob: randomBytes(BLOB_BYTES),
      createdAt: now,
      updatedAt: now,
    })
    .execute();
  const collection = await db
    .updateTable('collections')
    .set((builder) => ({ lastSeq: builder('lastSeq', '+', 1), updatedAt: now }))
    .where('id', '=', options.homeCollectionId)
    .returning('lastSeq')
    .executeTakeFirstOrThrow();
  await db
    .insertInto('entryMemberships')
    .values({
      collectionId: options.homeCollectionId,
      entryId,
      entryKeyByCollection: randomBytes(WRAPPED_KEY_BYTES),
      seq: collection.lastSeq,
      addedAt: now,
    })
    .execute();
  await db
    .insertInto('media')
    .values({
      id: mediaId,
      entryId,
      mediaKeyByEntry: randomBytes(WRAPPED_KEY_BYTES),
      createdAt: now,
    })
    .execute();
  return { entryId, mediaId };
}
