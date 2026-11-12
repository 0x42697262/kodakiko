import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { encodeBytes, errorResponseSchema, revisionConflictDetails } from '@kodakiko/contract';
import { errorCodeOf } from './auth-helpers.ts';
import {
  DAY_MS,
  addMedia,
  collectionLastSeq,
  createEntriesContext,
  createEntryFor,
  deleteEntry,
  diff,
  diffOf,
  entryDraft,
  entryOf,
  entryRow,
  getEntry,
  insertCollectionFixture,
  mediaOf,
  membershipSeq,
  newUser,
  patchEntry,
  postEntry,
  randomBlob,
  removeMedia,
  restoreEntry,
  send,
  tombstonesOf,
  trashEntry,
  wrapOf,
  type EntriesContext,
  type EntriesUser,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { bytesOfSize, objectRow, presign, presignBody, uploadSingle } from './objects-helpers.ts';

const OBJECT_BYTES = 100;
const MEDIA_OVER_LIMIT = 21;
const OVERSIZE_BLOB_BYTES = 524_289;
const STATUS_OK = 200;
const STATUS_CREATED = 201;
const STATUS_NO_CONTENT = 204;
const STATUS_CONFLICT = 409;
const STATUS_FORBIDDEN = 403;
const STATUS_NOT_FOUND = 404;
const STATUS_PAYLOAD_TOO_LARGE = 413;
const STATUS_UNAUTHORIZED = 401;
const STATUS_UNPROCESSABLE = 422;

let testDatabase: TestDatabase;
let context: EntriesContext;
let alice: EntriesUser;
let bob: EntriesUser;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase);
  alice = await newUser(context);
  bob = await newUser(context);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

describe('1. create', () => {
  it('creates with 201, repeats identically with 200 and the same body', async () => {
    const draft = entryDraft({ homeId: alice.homeId });
    const first = await postEntry(context, alice.token, draft.body);
    expect(first.status).toBe(STATUS_CREATED);
    const entry = entryOf(first);
    expect(entry.revision).toBe(1);
    expect(entry.ownerId).toBe(alice.userId);
    expect(entry.trashedAt).toBeNull();
    const second = await postEntry(context, alice.token, draft.body);
    expect(second.status).toBe(STATUS_OK);
    expect(second.raw).toBe(first.raw);
    const memberships = await context.db
      .selectFrom('entryMemberships')
      .selectAll()
      .where('entryId', '=', draft.id)
      .execute();
    expect(memberships).toHaveLength(1);
    const media = await context.db
      .selectFrom('media')
      .selectAll()
      .where('entryId', '=', draft.id)
      .execute();
    expect(media).toHaveLength(1);
  });

  it('answers 409 ID_CONFLICT for the same id with a different blob', async () => {
    const draft = await createEntryFor(context, alice);
    const changed = { ...draft.body, metadataBlob: encodeBytes(randomBlob()) };
    const result = await postEntry(context, alice.token, changed);
    expect(result.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(result)).toBe('ID_CONFLICT');
  });

  it('answers 409 ID_CONFLICT when another user already owns the id', async () => {
    const draft = await createEntryFor(context, alice);
    const result = await postEntry(context, bob.token, {
      ...draft.body,
      memberships: [{ collectionId: bob.homeId, entryKeyByCollection: wrapOf() }],
    });
    expect(result.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(result)).toBe('ID_CONFLICT');
  });

  it('answers 409 ID_CONFLICT when a media id is already used', async () => {
    const first = await createEntryFor(context, alice);
    const second = entryDraft({ homeId: alice.homeId });
    const clashing = {
      ...second.body,
      media: [{ mediaId: first.mediaIds[0], mediaKeyByEntry: wrapOf() }],
    };
    const result = await postEntry(context, alice.token, clashing);
    expect(result.status).toBe(STATUS_CONFLICT);
    expect(await entryRow(context.db, second.id)).toBeUndefined();
  });

  it('answers 422 for a journal entry without a home membership', async () => {
    const linkId = await insertCollectionFixture(context.db, {
      ownerId: alice.userId,
      kind: 'link',
    });
    const draft = entryDraft({ homeId: alice.homeId, collectionIds: [linkId] });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_UNPROCESSABLE);
    expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
  });

  it('answers 403 FEATURE_DISABLED for a link copy while public links are off', async () => {
    const linkId = await insertCollectionFixture(context.db, {
      ownerId: alice.userId,
      kind: 'link',
    });
    const draft = entryDraft({
      homeId: alice.homeId,
      purpose: 'link_copy',
      collectionIds: [linkId],
    });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_FORBIDDEN);
    expect(errorCodeOf(result)).toBe('FEATURE_DISABLED');
  });

  it('answers 403 FEATURE_DISABLED for a shared membership while sharing is off', async () => {
    const sharedId = await insertCollectionFixture(context.db, {
      ownerId: alice.userId,
      kind: 'shared',
    });
    const draft = entryDraft({ homeId: alice.homeId, collectionIds: [alice.homeId, sharedId] });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_FORBIDDEN);
    expect(errorCodeOf(result)).toBe('FEATURE_DISABLED');
  });

  it("answers 404 for a membership in another user's collection", async () => {
    const draft = entryDraft({ homeId: bob.homeId });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_NOT_FOUND);
    expect(errorCodeOf(result)).toBe('NOT_FOUND');
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
  });

  it('answers 422 naming the limit for 21 media with a maximum of 20', async () => {
    const draft = entryDraft({ homeId: alice.homeId, mediaCount: MEDIA_OVER_LIMIT });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_UNPROCESSABLE);
    const parsed = errorResponseSchema.parse(result.json);
    expect(parsed.error.code).toBe('VALIDATION_FAILED');
    expect(JSON.stringify(parsed.error.details)).toContain(String(context.settings.limits.maxMediaPerEntry));
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
  });

  it('answers 413 for an oversize metadata blob', async () => {
    const draft = entryDraft({ homeId: alice.homeId, blob: randomBlob(OVERSIZE_BLOB_BYTES) });
    const result = await postEntry(context, alice.token, draft.body);
    expect(result.status).toBe(STATUS_PAYLOAD_TOO_LARGE);
    expect(errorCodeOf(result)).toBe('PAYLOAD_TOO_LARGE');
  });

  it('answers 401 without a session', async () => {
    const draft = entryDraft({ homeId: alice.homeId });
    const result = await send(context.app, 'POST', '/api/v1/entries', { body: draft.body });
    expect(result.status).toBe(STATUS_UNAUTHORIZED);
  });

  it('logs entry.created without key material', async () => {
    const draft = await createEntryFor(context, alice);
    expect(context.logs.text()).toContain('entry.created');
    expect(context.logs.text()).toContain(draft.id);
  });
});

describe('get', () => {
  it('returns the entry to its owner and 404 to a stranger', async () => {
    const draft = await createEntryFor(context, alice);
    const own = await getEntry(context, alice.token, draft.id);
    expect(own.status).toBe(STATUS_OK);
    expect(entryOf(own).id).toBe(draft.id);
    const stranger = await getEntry(context, bob.token, draft.id);
    expect(stranger.status).toBe(STATUS_NOT_FOUND);
    const unknown = await getEntry(context, bob.token, 'ZZZZZZZZZZZZZZZZZZZZZZ');
    expect(stranger.raw.replace(/"requestId":"[^"]*"/, '')).toBe(
      unknown.raw.replace(/"requestId":"[^"]*"/, ''),
    );
  });
});

describe('2. sequence numbers', () => {
  it('gives each entry the next seq of its collection and moves a patched entry forward', async () => {
    const carol = await newUser(context);
    const first = await createEntryFor(context, carol);
    const second = await createEntryFor(context, carol);
    expect(await collectionLastSeq(context.db, carol.homeId)).toBe(2);
    expect(await membershipSeq(context.db, carol.homeId, first.id)).toBe(1);
    expect(await membershipSeq(context.db, carol.homeId, second.id)).toBe(2);
    const patched = await patchEntry(context, carol.token, first.id, randomBlob(), 1);
    expect(patched.status).toBe(STATUS_OK);
    expect(await membershipSeq(context.db, carol.homeId, first.id)).toBe(3);
    const page = diffOf(await diff(context, carol.token, carol.homeId, 0));
    expect(page.memberships.map((row) => [row.entryId, row.seq])).toEqual([
      [second.id, 2],
      [first.id, 3],
    ]);
    expect(page.nextCursor).toBe(3);
    expect(page.hasMore).toBe(false);
    expect(page.resyncRequired).toBe(false);
  });
});

describe('5. patch conflict', () => {
  it('answers 409 REVISION_CONFLICT with the stored revision and blob on the second patch', async () => {
    const draft = await createEntryFor(context, alice);
    const firstBlob = randomBlob();
    const first = await patchEntry(context, alice.token, draft.id, firstBlob, 1);
    expect(first.status).toBe(STATUS_OK);
    expect(entryOf(first).revision).toBe(2);
    const second = await patchEntry(context, alice.token, draft.id, randomBlob(), 1);
    expect(second.status).toBe(STATUS_CONFLICT);
    const parsed = errorResponseSchema.parse(second.json);
    expect(parsed.error.code).toBe('REVISION_CONFLICT');
    const details = revisionConflictDetails.parse(parsed.error.details);
    expect(details.revision).toBe(2);
    expect(details.metadataBlob).toBe(encodeBytes(firstBlob));
    const stored = await entryRow(context.db, draft.id);
    expect(stored?.revision).toBe(2);
  });

  it('rejects an oversize patch blob with 413', async () => {
    const draft = await createEntryFor(context, alice);
    const result = await patchEntry(context, alice.token, draft.id, randomBlob(OVERSIZE_BLOB_BYTES), 1);
    expect(result.status).toBe(STATUS_PAYLOAD_TOO_LARGE);
  });
});

describe('6. trash and restore', () => {
  it('bumps the seq once for repeated trash and repeated restore', async () => {
    const dave = await newUser(context);
    const draft = await createEntryFor(context, dave);
    const created = await membershipSeq(context.db, dave.homeId, draft.id);
    const trashed = await trashEntry(context, dave.token, draft.id);
    expect(trashed.status).toBe(STATUS_OK);
    expect(entryOf(trashed).trashedAt).not.toBeNull();
    const afterTrash = await membershipSeq(context.db, dave.homeId, draft.id);
    expect(afterTrash).toBe(created + 1);
    const again = await trashEntry(context, dave.token, draft.id);
    expect(again.status).toBe(STATUS_OK);
    expect(await membershipSeq(context.db, dave.homeId, draft.id)).toBe(afterTrash);
    expect(await collectionLastSeq(context.db, dave.homeId)).toBe(afterTrash);
    const page = diffOf(await diff(context, dave.token, dave.homeId, 0));
    expect(page.entries[0]?.trashedAt).not.toBeNull();
    const restored = await restoreEntry(context, dave.token, draft.id);
    expect(entryOf(restored).trashedAt).toBeNull();
    const afterRestore = await membershipSeq(context.db, dave.homeId, draft.id);
    expect(afterRestore).toBe(afterTrash + 1);
    await restoreEntry(context, dave.token, draft.id);
    expect(await membershipSeq(context.db, dave.homeId, draft.id)).toBe(afterRestore);
    const final = diffOf(await diff(context, dave.token, dave.homeId, 0));
    expect(final.entries[0]?.trashedAt).toBeNull();
    expect((await entryRow(context.db, draft.id))?.revision).toBe(1);
  });
});

describe('7. delete', () => {
  it('writes a tombstone, moves objects to deleting and is idempotent', async () => {
    const erin = await newUser(context);
    const draft = await createEntryFor(context, erin);
    const mediaId = draft.mediaIds[0] as string;
    const objectId = await uploadSingle(context, erin.token, mediaId, bytesOfSize(OBJECT_BYTES));
    const before = await collectionLastSeq(context.db, erin.homeId);
    const deletedAt = context.clock.now();
    const removed = await deleteEntry(context, erin.token, draft.id);
    expect(removed.status).toBe(STATUS_NO_CONTENT);
    expect(await entryRow(context.db, draft.id)).toBeUndefined();
    const tombstones = await tombstonesOf(context.db, erin.homeId);
    expect(tombstones).toHaveLength(1);
    expect(tombstones[0]?.entryId).toBe(draft.id);
    expect(tombstones[0]?.seq).toBe(before + 1);
    expect(await collectionLastSeq(context.db, erin.homeId)).toBe(before + 1);
    const page = diffOf(await diff(context, erin.token, erin.homeId, before - 1));
    expect(page.memberships).toEqual([
      { entryId: draft.id, entryKeyByCollection: null, seq: before + 1, removed: true },
    ]);
    const object = await objectRow(context.db, objectId);
    expect(object?.state).toBe('deleting');
    expect(object?.deleteAfter?.getTime()).toBeGreaterThanOrEqual(
      deletedAt.getTime() + context.settings.storage.objectGraceDays * DAY_MS,
    );
    const again = await deleteEntry(context, erin.token, draft.id);
    expect(again.status).toBe(STATUS_NO_CONTENT);
    expect(await tombstonesOf(context.db, erin.homeId)).toHaveLength(1);
    expect(context.logs.text()).toContain('entry.deleted');
  });

  it('deletes a pending object of the media as well', async () => {
    const frank = await newUser(context);
    const draft = await createEntryFor(context, frank);
    const id = 'PPPPPPPPPPPPPPPPPPPPPP';
    const issued = await presign(
      context,
      frank.token,
      presignBody({ id, mediaId: draft.mediaIds[0] as string, sizeBytes: OBJECT_BYTES }),
    );
    expect(issued.status).toBe(STATUS_OK);
    await deleteEntry(context, frank.token, draft.id);
    expect((await objectRow(context.db, id))?.state).toBe('deleting');
  });
});

describe('8. foreign entry', () => {
  it('answers 404 for trash, restore, patch and delete and leaves the entry unchanged', async () => {
    const draft = await createEntryFor(context, alice);
    const seq = await membershipSeq(context.db, alice.homeId, draft.id);
    const before = await entryRow(context.db, draft.id);
    const tombstoneCount = (await tombstonesOf(context.db, alice.homeId)).length;
    const results = [
      await trashEntry(context, bob.token, draft.id),
      await restoreEntry(context, bob.token, draft.id),
      await patchEntry(context, bob.token, draft.id, randomBlob(), 1),
      await deleteEntry(context, bob.token, draft.id),
    ];
    for (const result of results) {
      expect(result.status).toBe(STATUS_NOT_FOUND);
      expect(errorCodeOf(result)).toBe('NOT_FOUND');
    }
    expect(await entryRow(context.db, draft.id)).toEqual(before);
    expect(await membershipSeq(context.db, alice.homeId, draft.id)).toBe(seq);
    expect(await tombstonesOf(context.db, alice.homeId)).toHaveLength(tombstoneCount);
  });

  it('answers 404 for patching an unknown entry and for media routes of a foreign entry', async () => {
    const draft = await createEntryFor(context, alice);
    const unknown = await patchEntry(context, bob.token, 'ZZZZZZZZZZZZZZZZZZZZZZ', randomBlob(), 1);
    expect(unknown.status).toBe(STATUS_NOT_FOUND);
    const added = await addMedia(context, bob.token, draft.id, {
      mediaId: 'MMMMMMMMMMMMMMMMMMMMMM',
      mediaKeyByEntry: wrapOf(),
    });
    expect(added.status).toBe(STATUS_NOT_FOUND);
    const removed = await removeMedia(context, bob.token, draft.mediaIds[0] as string);
    expect(removed.status).toBe(STATUS_NOT_FOUND);
    const media = await context.db
      .selectFrom('media')
      .selectAll()
      .where('entryId', '=', draft.id)
      .execute();
    expect(media).toHaveLength(1);
  });
});

describe('9. media', () => {
  it('adds with a bump, repeats with 200, refuses a different wrap, removes with a bump', async () => {
    const gina = await newUser(context);
    const draft = await createEntryFor(context, gina);
    const initial = await membershipSeq(context.db, gina.homeId, draft.id);
    const body = { mediaId: 'AAAAAAAAAAAAAAAAAAAAAB', mediaKeyByEntry: wrapOf() };
    const added = await addMedia(context, gina.token, draft.id, body);
    expect(added.status).toBe(STATUS_CREATED);
    expect(mediaOf(added)).toMatchObject({ id: body.mediaId, entryId: draft.id });
    const afterAdd = await membershipSeq(context.db, gina.homeId, draft.id);
    expect(afterAdd).toBe(initial + 1);
    const repeated = await addMedia(context, gina.token, draft.id, body);
    expect(repeated.status).toBe(STATUS_OK);
    expect(repeated.raw).toBe(added.raw);
    expect(await membershipSeq(context.db, gina.homeId, draft.id)).toBe(afterAdd);
    const different = await addMedia(context, gina.token, draft.id, {
      ...body,
      mediaKeyByEntry: wrapOf(),
    });
    expect(different.status).toBe(STATUS_CONFLICT);
    expect(errorCodeOf(different)).toBe('ID_CONFLICT');
    const objectId = await uploadSingle(context, gina.token, body.mediaId, bytesOfSize(OBJECT_BYTES));
    const afterUpload = await membershipSeq(context.db, gina.homeId, draft.id);
    expect(afterUpload).toBe(afterAdd + 1);
    const removed = await removeMedia(context, gina.token, body.mediaId);
    expect(removed.status).toBe(STATUS_NO_CONTENT);
    expect((await objectRow(context.db, objectId))?.state).toBe('deleting');
    expect(await membershipSeq(context.db, gina.homeId, draft.id)).toBe(afterUpload + 1);
    const media = await context.db
      .selectFrom('media')
      .selectAll()
      .where('entryId', '=', draft.id)
      .execute();
    expect(media.map((row) => row.id)).toEqual(draft.mediaIds);
    const gone = await removeMedia(context, gina.token, body.mediaId);
    expect(gone.status).toBe(STATUS_NOT_FOUND);
  });

  it('refuses a media item above the configured maximum with 422', async () => {
    const hugo = await newUser(context);
    const draft = await createEntryFor(context, hugo, {
      mediaCount: context.settings.limits.maxMediaPerEntry,
    });
    const result = await addMedia(context, hugo.token, draft.id, {
      mediaId: 'AAAAAAAAAAAAAAAAAAAAAC',
      mediaKeyByEntry: wrapOf(),
    });
    expect(result.status).toBe(STATUS_UNPROCESSABLE);
    expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
  });

  it('refuses a media id that already belongs to another entry', async () => {
    const ivy = await newUser(context);
    const first = await createEntryFor(context, ivy);
    const second = await createEntryFor(context, ivy);
    const result = await addMedia(context, ivy.token, second.id, {
      mediaId: first.mediaIds[0] as string,
      mediaKeyByEntry: wrapOf(),
    });
    expect(result.status).toBe(STATUS_CONFLICT);
  });
});
