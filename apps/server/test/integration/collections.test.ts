import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  collectionListResponse,
  encodeBytes,
  type CollectionDto,
} from '@kodakiko/contract';
import { errorCodeOf } from './auth-helpers.ts';
import {
  collectionOf,
  createEntriesContext,
  getCollection,
  insertCollectionFixture,
  insertSharedCollectionFixture,
  listCollections,
  newUser,
  randomBlob,
  renameCollection,
  send,
  type EntriesContext,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const STATUS_OK = 200;
const STATUS_NOT_FOUND = 404;
const STATUS_PAYLOAD_TOO_LARGE = 413;
const STATUS_UNPROCESSABLE = 422;
const EXTRA_COLLECTIONS = 4;
const PAGE_LIMIT = 2;
const OVERSIZE_BYTES = 4097;
const PAGE_MAX = 200;

let testDatabase: TestDatabase;
let context: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function listOf(result: { json: unknown }) {
  return collectionListResponse.parse(result.json);
}

describe('GET /collections', () => {
  it('lists the home collection with owner role, username and key material', async () => {
    const user = await newUser(context);
    const result = await listCollections(context, user.token);
    expect(result.status).toBe(STATUS_OK);
    const { items, nextCursor } = listOf(result);
    expect(nextCursor).toBeNull();
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({
      id: user.homeId,
      kind: 'home',
      role: 'owner',
      ownerId: user.userId,
      ownerUsername: user.username,
      lastSeq: 0,
    });
    const row = await context.db
      .selectFrom('collections')
      .selectAll()
      .where('id', '=', user.homeId)
      .executeTakeFirstOrThrow();
    expect(items[0]?.keyMaterial).toBe(encodeBytes(row.keyByOwner));
    expect(items[0]?.metadataBlob).toBe(encodeBytes(row.metadataBlob));
  });

  it('pages by id order with an opaque cursor and returns every collection once', async () => {
    const user = await newUser(context);
    for (let count = 0; count < EXTRA_COLLECTIONS; count += 1) {
      await insertCollectionFixture(context.db, { ownerId: user.userId, kind: 'shared' });
    }
    const seen: CollectionDto[] = [];
    let cursor: string | null = null;
    let pages = 0;
    do {
      const query: string =
        cursor === null ? `?limit=${PAGE_LIMIT}` : `?limit=${PAGE_LIMIT}&cursor=${cursor}`;
      const page = listOf(await listCollections(context, user.token, query));
      expect(page.items.length).toBeLessThanOrEqual(PAGE_LIMIT);
      seen.push(...page.items);
      cursor = page.nextCursor;
      pages += 1;
    } while (cursor !== null);
    expect(pages).toBe(3);
    expect(seen).toHaveLength(EXTRA_COLLECTIONS + 1);
    const ids = seen.map((collection) => collection.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([...ids].sort());
  });

  it('includes shared collections as viewer with the sealed key and hides foreign link collections', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const linkId = await insertCollectionFixture(context.db, {
      ownerId: owner.userId,
      kind: 'link',
    });
    const recipientItems = listOf(await listCollections(context, recipient.token)).items;
    const viewer = recipientItems.find((collection) => collection.id === shared.collectionId);
    expect(viewer).toMatchObject({ kind: 'shared', role: 'viewer', ownerId: owner.userId });
    expect(viewer?.keyMaterial).toBe(encodeBytes(shared.sealedKey));
    expect(viewer?.ownerUsername).toBe(owner.username);
    expect(recipientItems.map((collection) => collection.id)).not.toContain(linkId);
    expect(recipientItems.map((collection) => collection.id)).not.toContain(owner.homeId);
    const ownerIds = listOf(await listCollections(context, owner.token)).items.map(
      (collection) => collection.id,
    );
    expect(ownerIds).toContain(linkId);
  });

  it('clamps the limit to the page maximum and answers 422 for a bad cursor or limit', async () => {
    const user = await newUser(context);
    expect((await listCollections(context, user.token, `?limit=${PAGE_MAX * PAGE_MAX}`)).status).toBe(
      STATUS_OK,
    );
    const badCursor = await listCollections(context, user.token, '?cursor=not-a-cursor');
    expect(badCursor.status).toBe(STATUS_UNPROCESSABLE);
    expect(errorCodeOf(badCursor)).toBe('VALIDATION_FAILED');
    expect((await listCollections(context, user.token, '?limit=0')).status).toBe(
      STATUS_UNPROCESSABLE,
    );
  });
});

describe('GET /collections/:id', () => {
  it('returns it to the owner and to a recipient, 404 to a stranger and for an unknown id', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const stranger = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const own = await getCollection(context, owner.token, owner.homeId);
    expect(collectionOf(own)).toMatchObject({ role: 'owner', id: owner.homeId });
    const viewer = await getCollection(context, recipient.token, shared.collectionId);
    expect(collectionOf(viewer)).toMatchObject({ role: 'viewer' });
    expect((await getCollection(context, stranger.token, shared.collectionId)).status).toBe(
      STATUS_NOT_FOUND,
    );
    expect((await getCollection(context, stranger.token, owner.homeId)).status).toBe(
      STATUS_NOT_FOUND,
    );
    const unknown = await getCollection(context, stranger.token, 'ZZZZZZZZZZZZZZZZZZZZZZ');
    expect(errorCodeOf(unknown)).toBe('NOT_FOUND');
  });
});

describe('PATCH /collections/:id', () => {
  it('renames for the owner and sets meta_updated_at but not the seq', async () => {
    const user = await newUser(context);
    const before = await getCollection(context, user.token, user.homeId);
    context.clock.advance(1000);
    const blob = randomBlob();
    const renamed = await renameCollection(context, user.token, user.homeId, blob);
    expect(renamed.status).toBe(STATUS_OK);
    const dto = collectionOf(renamed);
    expect(dto.metadataBlob).toBe(encodeBytes(blob));
    expect(dto.metaUpdatedAt).not.toBe(collectionOf(before).metaUpdatedAt);
    expect(dto.lastSeq).toBe(0);
    const stored = collectionOf(await getCollection(context, user.token, user.homeId));
    expect(stored.metadataBlob).toBe(encodeBytes(blob));
  });

  it('answers 404 for a recipient and a stranger and leaves the blob unchanged', async () => {
    const owner = await newUser(context);
    const recipient = await newUser(context);
    const shared = await insertSharedCollectionFixture(context.db, {
      ownerId: owner.userId,
      recipientId: recipient.userId,
    });
    const before = collectionOf(await getCollection(context, owner.token, shared.collectionId));
    expect(
      (await renameCollection(context, recipient.token, shared.collectionId, randomBlob())).status,
    ).toBe(STATUS_NOT_FOUND);
    expect((await renameCollection(context, recipient.token, owner.homeId, randomBlob())).status).toBe(
      STATUS_NOT_FOUND,
    );
    const after = collectionOf(await getCollection(context, owner.token, shared.collectionId));
    expect(after).toEqual(before);
  });

  it('answers 413 for an oversize blob and 401 without a session', async () => {
    const user = await newUser(context);
    const oversize = await renameCollection(
      context,
      user.token,
      user.homeId,
      randomBlob(OVERSIZE_BYTES),
    );
    expect(oversize.status).toBe(STATUS_PAYLOAD_TOO_LARGE);
    const anonymous = await send(context.app, 'PATCH', `/api/v1/collections/${user.homeId}`, {
      body: { metadataBlob: encodeBytes(randomBlob()) },
    });
    expect(anonymous.status).toBe(401);
  });
});
