import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  LINK_TOKEN_HEADER,
  LINK_VIEW_TOKEN_HEADER,
  ROUTES,
  encodeBytes,
  linkEntriesResponse,
  linkOpenResponse,
  publicLinkDto,
  publicLinkListResponse,
} from '@kodakiko/contract';
import { randomId } from '../../src/platform/ids.ts';
import { expirePublicLinksJob } from '../../src/jobs/expire-public-links.ts';
import { errorCodeOf, type CallResult } from './auth-helpers.ts';
import {
  BLOB_BYTES,
  DAY_MS,
  WRAP_BYTES,
  createEntriesContext,
  createEntryFor,
  getEntry,
  newUser,
  send,
  syncState,
  syncStateOf,
  type EntriesContext,
  type EntriesUser,
} from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';
import { fetchBytes, objectRow, uploadSingle, type ObjectsContext } from './objects-helpers.ts';

const STATUS_OK = 200;
const STATUS_CREATED = 201;
const STATUS_NO_CONTENT = 204;
const STATUS_FORBIDDEN = 403;
const STATUS_NOT_FOUND = 404;
const STATUS_PARTIAL = 206;
const TOKEN_BYTES = 32;
const OBJECT_BYTES = 4096;
const HOUR_MS = 3_600_000;
const VIEW_TOKEN_MINUTES = 5;
const MINUTE_MS = 60_000;

let testDatabase: TestDatabase;
let on: EntriesContext;
let proxy: EntriesContext;
let off: EntriesContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  const linkEnv = { FLAG_SHARING: 'false', FLAG_PUBLIC_LINKS: 'true', LINK_VIEW_TOKEN_MINUTES: String(VIEW_TOKEN_MINUTES) };
  on = await createEntriesContext(testDatabase, { env: linkEnv });
  proxy = await createEntriesContext(testDatabase, { env: { ...linkEnv, PUBLIC_LINK_PROXY: 'true' } });
  off = await createEntriesContext(testDatabase, { env: { FLAG_PUBLIC_LINKS: 'false' } });
});

afterAll(async () => {
  await on.close();
  await proxy.close();
  await off.close();
  await testDatabase.drop();
});

type Prepared = {
  readonly owner: EntriesUser;
  readonly collectionId: string;
  readonly entryId: string;
  readonly objectIds: readonly string[];
  readonly originalBytes: Uint8Array;
  readonly token: Uint8Array;
};

async function prepareLinkCollection(context: EntriesContext, owner?: EntriesUser): Promise<Prepared> {
  const user = owner ?? (await newUser(context));
  const collectionId = randomId();
  const created = await send(context.app, 'POST', ROUTES.collections(), {
    token: user.token,
    body: {
      id: collectionId,
      kind: 'link',
      keyByOwner: encodeBytes(randomBytes(WRAP_BYTES)),
      metadataBlob: encodeBytes(randomBytes(BLOB_BYTES)),
    },
  });
  if (created.status !== STATUS_CREATED) throw new Error(`link collection ${created.status} ${created.raw}`);
  const entry = await createEntryFor(context, user, { purpose: 'link_copy', collectionIds: [collectionId] });
  const mediaId = entry.mediaIds[0] ?? '';
  const originalBytes = randomBytes(OBJECT_BYTES);
  const objectsContext = context as unknown as ObjectsContext;
  const original = await uploadSingle(objectsContext, user.token, mediaId, originalBytes, 'original');
  const thumbnail = await uploadSingle(objectsContext, user.token, mediaId, randomBytes(OBJECT_BYTES), 'thumbnail');
  return { owner: user, collectionId, entryId: entry.id, objectIds: [original, thumbnail], originalBytes, token: randomBytes(TOKEN_BYTES) };
}

function createLink(context: EntriesContext, prepared: Prepared, expiry: string): Promise<CallResult> {
  return send(context.app, 'POST', ROUTES.publicLinks(), {
    token: prepared.owner.token,
    body: { collectionId: prepared.collectionId, accessToken: encodeBytes(prepared.token), expiry },
  });
}

async function linkFor(context: EntriesContext, expiry: string, owner?: EntriesUser): Promise<Prepared & { linkId: string }> {
  const prepared = await prepareLinkCollection(context, owner);
  const result = await createLink(context, prepared, expiry);
  if (result.status !== STATUS_CREATED) throw new Error(`link ${result.status} ${result.raw}`);
  return { ...prepared, linkId: publicLinkDto.parse(result.json).linkId };
}

async function viewer(
  context: EntriesContext,
  method: 'GET' | 'POST',
  url: string,
  headers: Record<string, string>,
): Promise<CallResult & { headers: Record<string, unknown> }> {
  const response = await context.app.inject({ method, url, headers });
  return {
    status: response.statusCode,
    json: response.body === '' ? null : (() => {
      try {
        return response.json() as unknown;
      } catch {
        return null;
      }
    })(),
    raw: response.body,
    headers: response.headers,
  };
}

function open(context: EntriesContext, linkId: string, token: Uint8Array) {
  return viewer(context, 'POST', ROUTES.publicLinkOpen(linkId), { [LINK_TOKEN_HEADER]: encodeBytes(token) });
}

function entries(context: EntriesContext, linkId: string, token: Uint8Array, viewToken: string | null) {
  return viewer(context, 'GET', ROUTES.publicLinkEntries(linkId), {
    [LINK_TOKEN_HEADER]: encodeBytes(token),
    ...(viewToken === null ? {} : { [LINK_VIEW_TOKEN_HEADER]: viewToken }),
  });
}

describe('flag off', () => {
  it('answers 403 FEATURE_DISABLED on owner and viewer routes', async () => {
    const owner = await newUser(off);
    const linkId = randomId();
    const results = [
      await send(off.app, 'POST', ROUTES.publicLinks(), { token: owner.token, body: { collectionId: randomId(), accessToken: encodeBytes(randomBytes(TOKEN_BYTES)), expiry: '7d' } }),
      await send(off.app, 'GET', ROUTES.publicLinks(), { token: owner.token }),
      await send(off.app, 'DELETE', ROUTES.publicLink(linkId), { token: owner.token }),
      await open(off, linkId, randomBytes(TOKEN_BYTES)),
      await entries(off, linkId, randomBytes(TOKEN_BYTES), 'x.y'),
    ];
    for (const result of results) {
      expect(result.status).toBe(STATUS_FORBIDDEN);
      expect(errorCodeOf(result)).toBe('FEATURE_DISABLED');
    }
  });
});

describe('owner routes', () => {
  it('creates a link, lists it, and refuses a second link for the same collection', async () => {
    const prepared = await prepareLinkCollection(on);
    const created = await createLink(on, prepared, '7d');
    expect(created.status).toBe(STATUS_CREATED);
    const dto = publicLinkDto.parse(created.json);
    expect(dto).toMatchObject({ collectionId: prepared.collectionId, maxViews: null, viewCount: 0 });
    expect(Date.parse(dto.expiresAt ?? '') - Date.now()).toBeGreaterThan(6 * DAY_MS);
    const row = await on.db.selectFrom('publicLinks').selectAll().where('id', '=', dto.linkId).executeTakeFirstOrThrow();
    expect(row.accessTokenHash.length).toBe(TOKEN_BYTES);
    expect(row.accessTokenHash.equals(Buffer.from(prepared.token))).toBe(false);
    const listed = publicLinkListResponse.parse((await send(on.app, 'GET', ROUTES.publicLinks(), { token: prepared.owner.token })).json);
    expect(listed.items.map((item) => item.linkId)).toEqual([dto.linkId]);
    const again = await createLink(on, prepared, '1d');
    expect(errorCodeOf(again)).toBe('ID_CONFLICT');
  });

  it('refuses a link whose objects are not all uploaded and a collection that is not owned', async () => {
    const owner = await newUser(on);
    const collectionId = randomId();
    await send(on.app, 'POST', ROUTES.collections(), {
      token: owner.token,
      body: { id: collectionId, kind: 'link', keyByOwner: encodeBytes(randomBytes(WRAP_BYTES)), metadataBlob: encodeBytes(randomBytes(BLOB_BYTES)) },
    });
    await createEntryFor(on, owner, { purpose: 'link_copy', collectionIds: [collectionId] });
    const incomplete = await send(on.app, 'POST', ROUTES.publicLinks(), {
      token: owner.token,
      body: { collectionId, accessToken: encodeBytes(randomBytes(TOKEN_BYTES)), expiry: '7d' },
    });
    expect(errorCodeOf(incomplete)).toBe('UPLOAD_INCOMPLETE');
    const stranger = await newUser(on);
    const foreign = await send(on.app, 'POST', ROUTES.publicLinks(), {
      token: stranger.token,
      body: { collectionId, accessToken: encodeBytes(randomBytes(TOKEN_BYTES)), expiry: '7d' },
    });
    expect(foreign.status).toBe(STATUS_NOT_FOUND);
  });

  it('revoke removes the link, its collection and copies, and marks objects deleting; the owner home is untouched', async () => {
    const owner = await newUser(on);
    const ownEntry = await createEntryFor(on, owner);
    const link = await linkFor(on, '7d', owner);
    expect((await send(on.app, 'DELETE', ROUTES.publicLink(link.linkId), { token: owner.token })).status).toBe(STATUS_NO_CONTENT);
    expect(await on.db.selectFrom('publicLinks').selectAll().where('id', '=', link.linkId).executeTakeFirst()).toBeUndefined();
    expect(await on.db.selectFrom('collections').selectAll().where('id', '=', link.collectionId).executeTakeFirst()).toBeUndefined();
    expect(await on.db.selectFrom('entries').selectAll().where('id', '=', link.entryId).executeTakeFirst()).toBeUndefined();
    for (const objectId of link.objectIds) expect((await objectRow(on.db, objectId))?.state).toBe('deleting');
    expect((await getEntry(on, owner.token, ownEntry.id)).status).toBe(STATUS_OK);
    expect((await open(on, link.linkId, link.token)).status).toBe(STATUS_FORBIDDEN);
  });

  it('lists the link collection in the owner sync state', async () => {
    const link = await linkFor(on, '7d');
    const state = syncStateOf(await syncState(on, link.owner.token));
    expect(state.collections.find((collection) => collection.id === link.collectionId)?.kind).toBe('link');
  });
});

describe('viewer routes', () => {
  it('opens with the right token and returns entries with presigned downloads', async () => {
    const link = await linkFor(on, '7d');
    const opened = await open(on, link.linkId, link.token);
    expect(opened.status).toBe(STATUS_OK);
    expect(opened.headers['x-robots-tag']).toBe('noindex');
    const body = linkOpenResponse.parse(opened.json);
    expect(body.collectionId).toBe(link.collectionId);
    const withoutView = await entries(on, link.linkId, link.token, null);
    expect(withoutView.status).toBe(STATUS_FORBIDDEN);
    expect(errorCodeOf(withoutView)).toBe('LINK_VIEW_EXPIRED');
    const listed = await entries(on, link.linkId, link.token, body.viewToken);
    expect(listed.status).toBe(STATUS_OK);
    const page = linkEntriesResponse.parse(listed.json);
    expect(page.items.map((item) => item.entry.id)).toEqual([link.entryId]);
    const original = page.items[0]?.objects.find((object) => object.variant === 'original');
    expect(original?.download.kind).toBe('presigned');
    if (original?.download.kind !== 'presigned') return;
    const fetched = await fetchBytes(original.download.url);
    expect(Buffer.from(fetched.bytes).equals(Buffer.from(link.originalBytes))).toBe(true);
    const content = await viewer(on, 'GET', ROUTES.publicLinkObjectContent(link.linkId, original.id), {
      [LINK_TOKEN_HEADER]: encodeBytes(link.token),
      [LINK_VIEW_TOKEN_HEADER]: body.viewToken,
    });
    expect(content.status).toBe(STATUS_NOT_FOUND);
  });

  it('answers the same 403 LINK_TOKEN_INVALID body for a wrong token and an unknown link', async () => {
    const link = await linkFor(on, '7d');
    const wrong = await open(on, link.linkId, randomBytes(TOKEN_BYTES));
    const unknown = await open(on, randomId(), randomBytes(TOKEN_BYTES));
    expect(wrong.status).toBe(STATUS_FORBIDDEN);
    expect(errorCodeOf(wrong)).toBe('LINK_TOKEN_INVALID');
    const strip = (result: CallResult) => {
      const value = result.json as { error: Record<string, unknown> };
      return { ...value.error, requestId: null };
    };
    expect(strip(unknown)).toEqual(strip(wrong));
  });

  it('rejects an expired view token', async () => {
    const link = await linkFor(on, '7d');
    const { viewToken } = linkOpenResponse.parse((await open(on, link.linkId, link.token)).json);
    on.clock.advance(VIEW_TOKEN_MINUTES * MINUTE_MS + 1);
    const late = await entries(on, link.linkId, link.token, viewToken);
    expect(errorCodeOf(late)).toBe('LINK_VIEW_EXPIRED');
    const forged = await entries(on, link.linkId, link.token, `${viewToken.split('.')[0]}.AAAA`);
    expect(errorCodeOf(forged)).toBe('LINK_VIEW_EXPIRED');
  });

  it('one_view: of two parallel opens exactly one succeeds and its entries keep working', async () => {
    const link = await linkFor(on, 'one_view');
    const [first, second] = await Promise.all([open(on, link.linkId, link.token), open(on, link.linkId, link.token)]);
    const statuses = [first.status, second.status].sort();
    expect(statuses).toEqual([STATUS_OK, STATUS_FORBIDDEN]);
    const loser = first.status === STATUS_OK ? second : first;
    const winner = first.status === STATUS_OK ? first : second;
    expect(errorCodeOf(loser)).toBe('LINK_EXPIRED');
    const { viewToken } = linkOpenResponse.parse(winner.json);
    expect((await entries(on, link.linkId, link.token, viewToken)).status).toBe(STATUS_OK);
  });

  it('time expiry turns opens into LINK_EXPIRED', async () => {
    const link = await linkFor(on, '1h');
    on.clock.advance(HOUR_MS + 1);
    const late = await open(on, link.linkId, link.token);
    expect(errorCodeOf(late)).toBe('LINK_EXPIRED');
  });

  it('proxy mode serves the ciphertext through the content route, with ranges', async () => {
    const link = await linkFor(proxy, '7d');
    const { viewToken } = linkOpenResponse.parse((await open(proxy, link.linkId, link.token)).json);
    const page = linkEntriesResponse.parse((await entries(proxy, link.linkId, link.token, viewToken)).json);
    const original = page.items[0]?.objects.find((object) => object.variant === 'original');
    expect(original?.download).toEqual({ kind: 'proxy', path: ROUTES.publicLinkObjectContent(link.linkId, original?.id ?? '') });
    const headers = { [LINK_TOKEN_HEADER]: encodeBytes(link.token), [LINK_VIEW_TOKEN_HEADER]: viewToken };
    const full = await proxy.app.inject({ method: 'GET', url: ROUTES.publicLinkObjectContent(link.linkId, original?.id ?? ''), headers });
    expect(full.statusCode).toBe(STATUS_OK);
    expect(full.rawPayload.equals(Buffer.from(link.originalBytes))).toBe(true);
    const ranged = await proxy.app.inject({
      method: 'GET',
      url: ROUTES.publicLinkObjectContent(link.linkId, original?.id ?? ''),
      headers: { ...headers, range: 'bytes=10-19' },
    });
    expect(ranged.statusCode).toBe(STATUS_PARTIAL);
    expect(ranged.headers['content-range']).toBe(`bytes 10-19/${OBJECT_BYTES}`);
    expect(ranged.rawPayload.equals(Buffer.from(link.originalBytes.slice(10, 20)))).toBe(true);
    const foreign = await linkFor(proxy, '7d');
    const notMine = await proxy.app.inject({ method: 'GET', url: ROUTES.publicLinkObjectContent(link.linkId, foreign.objectIds[0] ?? ''), headers });
    expect(notMine.statusCode).toBe(STATUS_NOT_FOUND);
  });
});

describe('expire-public-links job', () => {
  it('removes time-expired links and old opened one-view links', async () => {
    const timed = await linkFor(on, '1h');
    const oneView = await linkFor(on, 'one_view');
    const fresh = await linkFor(on, '30d');
    await open(on, oneView.linkId, oneView.token);
    on.clock.advance(2 * DAY_MS);
    const job = expirePublicLinksJob(on.settings, on.store, on.app.log);
    await job.run({ db: on.db, now: on.clock.now(), signal: new AbortController().signal });
    const remaining = (await on.db.selectFrom('publicLinks').select('id').execute()).map((row) => row.id);
    expect(remaining).not.toContain(timed.linkId);
    expect(remaining).not.toContain(oneView.linkId);
    expect(remaining).toContain(fresh.linkId);
  });
});
