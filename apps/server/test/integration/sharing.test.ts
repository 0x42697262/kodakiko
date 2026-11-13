import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  ROUTES,
  collectionDto,
  encodeBytes,
  publicKeyResponse,
  shareListResponse,
} from '@kodakiko/contract';
import { randomId } from '../../src/platform/ids.ts';
import { errorCodeOf } from './auth-helpers.ts';
import {
  BLOB_BYTES,
  WRAP_BYTES,
  createEntriesContext,
  createEntryFor,
  diff,
  diffOf,
  getCollection,
  getEntry,
  newUser,
  patchEntry,
  restoreEntry,
  send,
  syncState,
  syncStateOf,
  tombstonesOf,
  trashEntry,
  wrapOf,
  type EntriesContext,
  type EntriesUser,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { download, uploadSingle, type ObjectsContext } from './objects-helpers.ts';

const STATUS_OK = 200;
const STATUS_CREATED = 201;
const STATUS_NO_CONTENT = 204;
const STATUS_FORBIDDEN = 403;
const STATUS_NOT_FOUND = 404;
const STATUS_CONFLICT = 409;
const STATUS_UNPROCESSABLE = 422;
const SEALED_SHARE_BYTES = 104;
const MAX_SHARED = 3;
const MAX_RECIPIENTS = 2;
const OBJECT_BYTES = 2048;

let testDatabase: TestDatabase;
let on: EntriesContext;
let off: EntriesContext;
let linksOnly: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  on = await createEntriesContext(testDatabase, {
    env: {
      FLAG_SHARING: 'true',
      FLAG_PUBLIC_LINKS: 'false',
      MAX_SHARED_COLLECTIONS_PER_USER: String(MAX_SHARED),
      MAX_RECIPIENTS_PER_COLLECTION: String(MAX_RECIPIENTS),
    },
  });
  off = await createEntriesContext(testDatabase, { env: { FLAG_SHARING: 'false', FLAG_PUBLIC_LINKS: 'false' } });
  linksOnly = await createEntriesContext(testDatabase, { env: { FLAG_SHARING: 'false', FLAG_PUBLIC_LINKS: 'true' } });
});

afterAll(async () => {
  await on.close();
  await off.close();
  await linksOnly.close();
  await testDatabase.drop();
});

type CreateBody = { id: string; kind: 'shared' | 'link'; keyByOwner: string; metadataBlob: string };

function createBody(kind: 'shared' | 'link' = 'shared'): CreateBody {
  return {
    id: randomId(),
    kind,
    keyByOwner: encodeBytes(randomBytes(WRAP_BYTES)),
    metadataBlob: encodeBytes(randomBytes(BLOB_BYTES)),
  };
}

function sealed(size: number = SEALED_SHARE_BYTES): string {
  return encodeBytes(randomBytes(size));
}

function createCollection(context: EntriesContext, user: EntriesUser, body: CreateBody) {
  return send(context.app, 'POST', ROUTES.collections(), { token: user.token, body });
}

async function newJournal(context: EntriesContext, user: EntriesUser): Promise<string> {
  const body = createBody();
  const result = await createCollection(context, user, body);
  if (result.status !== STATUS_CREATED) throw new Error(`create failed ${result.status} ${result.raw}`);
  return body.id;
}

function addEntry(context: EntriesContext, user: EntriesUser, collectionId: string, entryId: string, wrap = wrapOf()) {
  return send(context.app, 'POST', ROUTES.collectionEntries(collectionId), {
    token: user.token,
    body: { entryId, entryKeyByCollection: wrap },
  });
}

function removeEntry(context: EntriesContext, user: EntriesUser, collectionId: string, entryId: string) {
  return send(context.app, 'DELETE', ROUTES.collectionEntry(collectionId, entryId), { token: user.token });
}

function share(context: EntriesContext, user: EntriesUser, collectionId: string, recipientUserId: string, key = sealed()) {
  return send(context.app, 'POST', ROUTES.collectionShares(collectionId), {
    token: user.token,
    body: { recipientUserId, sealedCollectionKey: key },
  });
}

function unshare(context: EntriesContext, user: EntriesUser, collectionId: string, recipientUserId: string) {
  return send(context.app, 'DELETE', ROUTES.collectionShare(collectionId, recipientUserId), { token: user.token });
}

function deleteCollection(context: EntriesContext, user: EntriesUser, collectionId: string) {
  return send(context.app, 'DELETE', ROUTES.collection(collectionId), { token: user.token });
}

async function visibleCollectionIds(context: EntriesContext, user: EntriesUser): Promise<string[]> {
  return syncStateOf(await syncState(context, user.token)).collections.map((collection) => collection.id);
}

describe('flag off', () => {
  it('answers 403 FEATURE_DISABLED on every sharing route', async () => {
    const owner = await newUser(off);
    const other = await newUser(off);
    const entry = await createEntryFor(off, owner);
    const journalId = randomId();
    const results = [
      await createCollection(off, owner, { ...createBody(), id: journalId }),
      await deleteCollection(off, owner, journalId),
      await addEntry(off, owner, journalId, entry.id),
      await removeEntry(off, owner, journalId, entry.id),
      await share(off, owner, journalId, other.userId),
      await send(off.app, 'GET', ROUTES.collectionShares(journalId), { token: owner.token }),
      await unshare(off, owner, journalId, other.userId),
      await send(off.app, 'GET', ROUTES.userPublicKey(other.username), { token: owner.token }),
    ];
    for (const result of results) {
      expect(result.status).toBe(STATUS_FORBIDDEN);
      expect(errorCodeOf(result)).toBe('FEATURE_DISABLED');
    }
  });

  it('creates a link collection with sharing off and public links on', async () => {
    const owner = await newUser(linksOnly);
    const result = await createCollection(linksOnly, owner, createBody('link'));
    expect(result.status).toBe(STATUS_CREATED);
  });
});

describe('shared journals', () => {
  it('creates a journal; an identical repeat answers 200 and a different body 409', async () => {
    const owner = await newUser(on);
    const body = createBody();
    const created = await createCollection(on, owner, body);
    expect(created.status).toBe(STATUS_CREATED);
    expect(collectionDto.parse(created.json)).toMatchObject({ id: body.id, kind: 'shared', role: 'owner', recipientCount: 0 });
    expect((await createCollection(on, owner, body)).status).toBe(STATUS_OK);
    const different = await createCollection(on, owner, { ...body, metadataBlob: encodeBytes(randomBytes(BLOB_BYTES)) });
    expect(different.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(different)).toBe('ID_CONFLICT');
  });

  it('rejects a key of the wrong length and enforces the per-user journal limit', async () => {
    const owner = await newUser(on);
    const badKey = await createCollection(on, owner, { ...createBody(), keyByOwner: encodeBytes(randomBytes(WRAP_BYTES - 1)) });
    expect(badKey.status).toBe(STATUS_UNPROCESSABLE);
    for (let count = 0; count < MAX_SHARED; count += 1) await newJournal(on, owner);
    const over = await createCollection(on, owner, createBody());
    expect(over.status).toBe(STATUS_UNPROCESSABLE);
    expect(errorCodeOf(over)).toBe('VALIDATION_FAILED');
  });

  it('adds an entry once; repeats are 200, a different wrap 409, foreign or trashed entries 404', async () => {
    const owner = await newUser(on);
    const stranger = await newUser(on);
    const journalId = await newJournal(on, owner);
    const entry = await createEntryFor(on, owner);
    const wrap = wrapOf();
    expect((await addEntry(on, owner, journalId, entry.id, wrap)).status).toBe(STATUS_CREATED);
    expect((await addEntry(on, owner, journalId, entry.id, wrap)).status).toBe(STATUS_OK);
    const conflict = await addEntry(on, owner, journalId, entry.id);
    expect(conflict.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(conflict)).toBe('ID_CONFLICT');
    const foreign = await createEntryFor(on, stranger);
    expect((await addEntry(on, owner, journalId, foreign.id)).status).toBe(STATUS_NOT_FOUND);
    const trashed = await createEntryFor(on, owner);
    await trashEntry(on, owner.token, trashed.id);
    expect((await addEntry(on, owner, journalId, trashed.id)).status).toBe(STATUS_NOT_FOUND);
    expect((await addEntry(on, owner, owner.homeId, entry.id)).status).toBe(STATUS_NOT_FOUND);
  });

  it('validates shares: not to yourself, exact sealed length, identical repeat 200, different 409, limit', async () => {
    const owner = await newUser(on);
    const first = await newUser(on);
    const second = await newUser(on);
    const third = await newUser(on);
    const journalId = await newJournal(on, owner);
    expect((await share(on, owner, journalId, owner.userId)).status).toBe(STATUS_UNPROCESSABLE);
    expect((await share(on, owner, journalId, first.userId, sealed(SEALED_SHARE_BYTES - 1))).status).toBe(STATUS_UNPROCESSABLE);
    const key = sealed();
    expect((await share(on, owner, journalId, first.userId, key)).status).toBe(STATUS_CREATED);
    expect((await share(on, owner, journalId, first.userId, key)).status).toBe(STATUS_OK);
    const different = await share(on, owner, journalId, first.userId);
    expect(different.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(different)).toBe('ALREADY_SHARED');
    expect((await share(on, owner, journalId, second.userId)).status).toBe(STATUS_CREATED);
    const over = await share(on, owner, journalId, third.userId);
    expect(over.status).toBe(STATUS_UNPROCESSABLE);
    const row = await on.db
      .selectFrom('collectionShares')
      .selectAll()
      .where('collectionId', '=', journalId)
      .where('recipientId', '=', first.userId)
      .executeTakeFirstOrThrow();
    expect(row.sealedCollectionKey.length).toBe(SEALED_SHARE_BYTES);
    const listed = shareListResponse.parse((await send(on.app, 'GET', ROUTES.collectionShares(journalId), { token: owner.token })).json);
    expect(listed.items.map((item) => item.recipientUsername).sort()).toEqual([first.username, second.username].sort());
    const owned = collectionDto.parse((await getCollection(on, owner.token, journalId)).json);
    expect(owned.recipientCount).toBe(2);
  });

  it('gives the recipient read-only access to the journal, its entries and their objects', async () => {
    const owner = await newUser(on);
    const recipient = await newUser(on);
    const journalId = await newJournal(on, owner);
    const entry = await createEntryFor(on, owner);
    const mediaId = entry.mediaIds[0] ?? '';
    const objectId = await uploadSingle(on as unknown as ObjectsContext, owner.token, mediaId, randomBytes(OBJECT_BYTES));
    await addEntry(on, owner, journalId, entry.id);
    await share(on, owner, journalId, recipient.userId);

    const state = syncStateOf(await syncState(on, recipient.token));
    expect(state.collections.find((collection) => collection.id === journalId)).toMatchObject({ role: 'viewer' });
    const viewed = collectionDto.parse((await getCollection(on, recipient.token, journalId)).json);
    expect(viewed).toMatchObject({ ownerUsername: owner.username, recipientCount: null });
    expect(diffOf(await diff(on, recipient.token, journalId, 0)).memberships.map((item) => item.entryId)).toEqual([entry.id]);
    expect((await download(on as unknown as ObjectsContext, recipient.token, objectId)).status).toBe(STATUS_OK);

    expect((await patchEntry(on, recipient.token, entry.id, randomBytes(BLOB_BYTES), 1)).status).toBe(STATUS_NOT_FOUND);
    expect((await trashEntry(on, recipient.token, entry.id)).status).toBe(STATUS_NOT_FOUND);
    const recipientEntry = await createEntryFor(on, recipient);
    expect((await addEntry(on, recipient, journalId, recipientEntry.id)).status).toBe(STATUS_NOT_FOUND);
    expect((await share(on, recipient, journalId, owner.userId)).status).toBe(STATUS_NOT_FOUND);
  });

  it('removing an entry leaves a tombstone and the recipient diff reports it removed', async () => {
    const owner = await newUser(on);
    const recipient = await newUser(on);
    const journalId = await newJournal(on, owner);
    const entry = await createEntryFor(on, owner);
    await addEntry(on, owner, journalId, entry.id);
    await share(on, owner, journalId, recipient.userId);
    const before = diffOf(await diff(on, recipient.token, journalId, 0));
    const seen = Math.max(...before.memberships.map((item) => item.seq));
    expect((await removeEntry(on, owner, journalId, entry.id)).status).toBe(STATUS_NO_CONTENT);
    expect((await removeEntry(on, owner, journalId, entry.id)).status).toBe(STATUS_NO_CONTENT);
    expect(await tombstonesOf(on.db, journalId)).toHaveLength(1);
    const after = diffOf(await diff(on, recipient.token, journalId, seen));
    expect(after.memberships).toMatchObject([{ entryId: entry.id, removed: true, entryKeyByCollection: null }]);
    expect((await getEntry(on, owner.token, entry.id)).status).toBe(STATUS_OK);
  });

  it('a trashed entry disappears for the recipient and comes back after restore', async () => {
    const owner = await newUser(on);
    const recipient = await newUser(on);
    const journalId = await newJournal(on, owner);
    const entry = await createEntryFor(on, owner);
    await addEntry(on, owner, journalId, entry.id);
    await share(on, owner, journalId, recipient.userId);
    await trashEntry(on, owner.token, entry.id);
    expect((await getEntry(on, recipient.token, entry.id)).status).toBe(STATUS_NOT_FOUND);
    await restoreEntry(on, owner.token, entry.id);
    expect((await getEntry(on, recipient.token, entry.id)).status).toBe(STATUS_OK);
  });

  it('the recipient can leave, the owner can unshare, a third party cannot', async () => {
    const owner = await newUser(on);
    const leaver = await newUser(on);
    const removed = await newUser(on);
    const stranger = await newUser(on);
    const journalId = await newJournal(on, owner);
    await share(on, owner, journalId, leaver.userId);
    await share(on, owner, journalId, removed.userId);
    expect((await unshare(on, stranger, journalId, removed.userId)).status).toBe(STATUS_NOT_FOUND);
    expect((await unshare(on, leaver, journalId, leaver.userId)).status).toBe(STATUS_NO_CONTENT);
    expect(await visibleCollectionIds(on, leaver)).not.toContain(journalId);
    expect((await unshare(on, owner, journalId, removed.userId)).status).toBe(STATUS_NO_CONTENT);
    expect(await visibleCollectionIds(on, removed)).not.toContain(journalId);
    expect((await unshare(on, owner, journalId, removed.userId)).status).toBe(STATUS_NO_CONTENT);
  });

  it('deleting a journal removes it for everyone and keeps the entry in the owner home', async () => {
    const owner = await newUser(on);
    const recipient = await newUser(on);
    const journalId = await newJournal(on, owner);
    const entry = await createEntryFor(on, owner);
    await addEntry(on, owner, journalId, entry.id);
    await share(on, owner, journalId, recipient.userId);
    expect((await deleteCollection(on, recipient, journalId)).status).toBe(STATUS_NOT_FOUND);
    expect((await deleteCollection(on, owner, journalId)).status).toBe(STATUS_NO_CONTENT);
    expect(await visibleCollectionIds(on, owner)).not.toContain(journalId);
    expect(await visibleCollectionIds(on, recipient)).not.toContain(journalId);
    expect((await getEntry(on, owner.token, entry.id)).status).toBe(STATUS_OK);
    const home = diffOf(await diff(on, owner.token, owner.homeId, 0));
    expect(home.memberships.map((item) => item.entryId)).toContain(entry.id);
  });

  it('refuses to delete the home collection', async () => {
    const owner = await newUser(on);
    const result = await deleteCollection(on, owner, owner.homeId);
    expect(result.status).toBe(STATUS_FORBIDDEN);
    expect(errorCodeOf(result)).toBe('FORBIDDEN');
  });
});

describe('public key lookup', () => {
  it('returns the key of an active user and 404 for unknown or pending-deletion users', async () => {
    const asker = await newUser(on);
    const target = await newUser(on);
    const found = await send(on.app, 'GET', ROUTES.userPublicKey(target.username), { token: asker.token });
    expect(found.status).toBe(STATUS_OK);
    const parsed = publicKeyResponse.parse(found.json);
    expect(parsed).toMatchObject({ userId: target.userId, username: target.username });
    const row = await on.db.selectFrom('users').select('publicKey').where('id', '=', target.userId).executeTakeFirstOrThrow();
    expect(parsed.publicKey).toBe(encodeBytes(row.publicKey));
    expect((await send(on.app, 'GET', ROUTES.userPublicKey('nobody-here'), { token: asker.token })).status).toBe(STATUS_NOT_FOUND);
    await on.db.updateTable('users').set({ status: 'pending_deletion', deletionDueAt: new Date(Date.now() + 86_400_000) }).where('id', '=', target.userId).execute();
    expect((await send(on.app, 'GET', ROUTES.userPublicKey(target.username), { token: asker.token })).status).toBe(STATUS_NOT_FOUND);
  });
});
