import { beforeAll, describe, expect, it } from 'vitest';
import { KDF_CONTEXTS, KDF_SUBKEY_IDS, type Sodium } from '@kodakiko/core';
import { encodeBytes } from '@kodakiko/contract';
import { createNodeSodium } from '../../../packages/core/test/sodium-node.ts';
import { ApiError, NetworkError, createHttpClient } from '../src/http.ts';
import { LinkFetchError, createLinkSession, type LinkSession } from '../src/link-session.ts';
import { BUCKET_ORIGIN, ORIGIN, VIEW_TOKEN, createFakeLinkServer, type FakeEntry, type FakeLinkOptions, type FakeLinkServer } from './support/fake-link-server.ts';

const KEY_BYTES = 32;
const MIB = 1_048_576;

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function bytes(length: number, seed: number): Uint8Array {
  return Uint8Array.from({ length }, (_, index) => (index * 31 + seed) % 251);
}

function photo(seed: number): FakeEntry['media'][number] {
  return { kind: 'photo', mimeType: 'image/jpeg', original: bytes(5000 + seed, seed), thumbnail: bytes(300 + seed, seed + 1) };
}

function video(seed: number, length = 4000): FakeEntry['media'][number] {
  return { kind: 'video', mimeType: 'video/mp4', original: bytes(length, seed), thumbnail: bytes(200, seed + 2) };
}

const OLDER: FakeEntry = { takenAtUtc: '2026-05-01T10:00:00.000Z', utcOffsetMinutes: 120, note: 'older note', media: [photo(1)] };
const NEWER: FakeEntry = { takenAtUtc: '2026-05-03T09:00:00.000Z', utcOffsetMinutes: -300, note: 'newer note', media: [photo(2), video(3)] };

function serve(options: Partial<FakeLinkOptions> = {}): FakeLinkServer {
  return createFakeLinkServer(sodium, { title: 'Holiday', entries: [OLDER, NEWER], ...options });
}

function sessionFor(server: FakeLinkServer, secret: Uint8Array = server.secret, linkId: string = server.linkId): LinkSession {
  const http = createHttpClient({ baseUrl: ORIGIN, fetcher: server.fetcher, sleep: async () => undefined, random: () => 0 });
  return createLinkSession({ sodium, http, linkId, secret, origin: ORIGIN, fetcher: server.fetcher });
}

function lowerKeys(record: Readonly<Record<string, string | null>>): Record<string, string | null> {
  return Object.fromEntries(Object.entries(record).map(([name, value]) => [name.toLowerCase(), value]));
}

describe('createLinkSession.open (twin of apps/client link-session)', () => {
  it('decrypts the title and lists every media item, newest entry first, media order kept inside an entry', async () => {
    const server = serve({ maxViews: 1 });
    const opened = await sessionFor(server).open();
    expect(opened.title).toBe('Holiday');
    expect(opened.maxViews).toBe(1);
    const [olderId, newerId] = server.entryIds;
    const [olderMedia, newerMedia] = server.mediaIds;
    expect(
      opened.items.map((item) => ({
        entryId: item.entryId,
        position: item.position,
        mediaId: item.descriptor.mediaId,
        kind: item.descriptor.kind,
        mimeType: item.descriptor.mimeType,
        takenAtUtc: item.takenAtUtc,
        utcOffsetMinutes: item.utcOffsetMinutes,
        note: item.note,
      })),
    ).toEqual([
      { entryId: newerId, position: 0, mediaId: newerMedia?.[0], kind: 'photo', mimeType: 'image/jpeg', takenAtUtc: NEWER.takenAtUtc, utcOffsetMinutes: -300, note: 'newer note' },
      { entryId: newerId, position: 1, mediaId: newerMedia?.[1], kind: 'video', mimeType: 'video/mp4', takenAtUtc: NEWER.takenAtUtc, utcOffsetMinutes: -300, note: 'newer note' },
      { entryId: olderId, position: 2, mediaId: olderMedia?.[0], kind: 'photo', mimeType: 'image/jpeg', takenAtUtc: OLDER.takenAtUtc, utcOffsetMinutes: 120, note: 'older note' },
    ]);
  });

  it('unwraps each media key through the entry key and the link collection key', async () => {
    const server = serve();
    const opened = await sessionFor(server).open();
    for (const item of opened.items) expect(item.mediaKey).toEqual(server.mediaKeys.get(item.descriptor.mediaId));
  });

  it('reports a null maxViews for links without a view limit', async () => {
    expect((await sessionFor(serve()).open()).maxViews).toBeNull();
  });

  it('skips media whose original or thumbnail object is missing and keeps positions contiguous', async () => {
    const server = serve({
      entries: [{ ...NEWER, media: [{ ...photo(4), missing: 'original' }, video(5), { ...photo(6), missing: 'thumbnail' }, photo(7)] }],
    });
    const opened = await sessionFor(server).open();
    const ids = server.mediaIds[0] ?? [];
    expect(opened.items.map((item) => [item.position, item.descriptor.mediaId])).toEqual([
      [0, ids[1]],
      [1, ids[3]],
    ]);
  });

  it('sends the access token derived from the secret and never sends the secret or the link key', async () => {
    const server = serve();
    await sessionFor(server).open();
    const expectedToken = encodeBytes(sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, server.secret));
    const linkKey = encodeBytes(sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, server.secret));
    expect(server.accessToken).toBe(expectedToken);
    const apiRequests = server.requests.filter((request) => request.url.startsWith(ORIGIN));
    expect(apiRequests.length).toBeGreaterThanOrEqual(2);
    for (const request of apiRequests) expect(request.headers.get('x-kodakiko-link-token')).toBe(expectedToken);
    for (const request of server.requests) {
      const visible = `${request.url} ${JSON.stringify([...request.headers.entries()])}`;
      expect(visible).not.toContain(encodeBytes(server.secret));
      expect(visible).not.toContain(linkKey);
    }
  });

  it('opens once with POST and then lists entries with the view token', async () => {
    const server = serve();
    await sessionFor(server).open();
    const api = server.requests.filter((request) => request.url.startsWith(ORIGIN));
    expect(api.map((request) => [request.method, new URL(request.url).pathname])).toEqual([
      ['POST', `/api/v1/public-links/${server.linkId}/open`],
      ['GET', `/api/v1/public-links/${server.linkId}/entries`],
    ]);
    expect(api[0]?.headers.get('x-kodakiko-view-token')).toBeNull();
    expect(api[1]?.headers.get('x-kodakiko-view-token')).toBe(VIEW_TOKEN);
  });

  it('follows the entries cursor across pages', async () => {
    const third: FakeEntry = { takenAtUtc: '2026-04-20T08:00:00.000Z', utcOffsetMinutes: 0, note: '', media: [photo(8)] };
    const server = serve({ entries: [OLDER, NEWER, third], pageSize: 1 });
    const opened = await sessionFor(server).open();
    expect(opened.items).toHaveLength(4);
    const listings = server.requests.filter((request) => request.url.includes('/entries'));
    expect(listings.map((request) => new URL(request.url).searchParams.get('cursor'))).toEqual([null, '1', '2']);
  });

  it('keeps working after the caller wipes its copy of the secret', async () => {
    const server = serve();
    const secret = server.secret.slice();
    const http = createHttpClient({ baseUrl: ORIGIN, fetcher: server.fetcher, sleep: async () => undefined, random: () => 0 });
    const session = createLinkSession({ sodium, http, linkId: server.linkId, secret, origin: ORIGIN, fetcher: server.fetcher });
    secret.fill(0);
    const opened = await session.open();
    expect(opened.items).toHaveLength(3);
    expect(await session.thumbnail(opened.items[0]!)).toEqual(NEWER.media[0]?.thumbnail);
  });
});

describe('createLinkSession errors', () => {
  it('rejects a wrong secret with the server denial LINK_TOKEN_INVALID', async () => {
    const server = serve();
    const failure = await sessionFor(server, sodium.randomBytes(KEY_BYTES)).open().catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ code: 'LINK_TOKEN_INVALID' });
  });

  it('rejects an unknown link id with LINK_TOKEN_INVALID', async () => {
    const server = serve();
    await expect(sessionFor(server, server.secret, 'U'.repeat(22)).open()).rejects.toMatchObject({ code: 'LINK_TOKEN_INVALID' });
  });

  it('passes LINK_EXPIRED through for expired, already viewed and revoked-by-expiry links', async () => {
    const server = serve();
    server.control.openError = { status: 403, code: 'LINK_EXPIRED' };
    await expect(sessionFor(server).open()).rejects.toMatchObject({ code: 'LINK_EXPIRED' });
  });

  it('reports NetworkError when offline, after exactly one open attempt', async () => {
    const server = serve();
    server.control.offline = true;
    await expect(sessionFor(server).open()).rejects.toBeInstanceOf(NetworkError);
    expect(server.requests).toHaveLength(1);
  });

  it('fails with a non-API error when the title does not decrypt with the link key', async () => {
    const server = serve({ tamperTitle: true });
    const failure = await sessionFor(server).open().catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(Error);
    expect(failure).not.toBeInstanceOf(ApiError);
    expect(failure).not.toBeInstanceOf(NetworkError);
  });

  it('passes LINK_VIEW_EXPIRED through when the entries listing is refused', async () => {
    const server = serve();
    server.control.entriesError = { status: 403, code: 'LINK_VIEW_EXPIRED' };
    await expect(sessionFor(server).open()).rejects.toMatchObject({ code: 'LINK_VIEW_EXPIRED' });
  });
});

describe('createLinkSession media', () => {
  it('decrypts thumbnails and originals from presigned bucket URLs without link headers', async () => {
    const server = serve();
    const session = sessionFor(server);
    const opened = await session.open();
    const [first, second, third] = opened.items;
    expect(await session.thumbnail(first!)).toEqual(NEWER.media[0]?.thumbnail);
    expect(await session.original(first!)).toEqual(NEWER.media[0]?.original);
    expect(await session.original(second!)).toEqual(NEWER.media[1]?.original);
    expect(await session.thumbnail(third!)).toEqual(OLDER.media[0]?.thumbnail);
    const bucketRequests = server.requests.filter((request) => request.url.startsWith(BUCKET_ORIGIN));
    expect(bucketRequests).toHaveLength(4);
    for (const request of bucketRequests) {
      expect(request.headers.get('x-kodakiko-link-token')).toBeNull();
      expect(request.headers.get('x-kodakiko-view-token')).toBeNull();
    }
  });

  it('decrypts a multi-chunk original', async () => {
    const big = video(9, 2 * MIB + 333);
    const server = serve({ entries: [{ ...OLDER, media: [big] }] });
    const session = sessionFor(server);
    const [item] = (await session.open()).items;
    expect(await session.original(item!)).toEqual(big.original);
  });

  it('downloads through the API proxy with the link and view tokens when the server proxies objects', async () => {
    const server = serve({ download: 'proxy' });
    const session = sessionFor(server);
    const [item] = (await session.open()).items;
    expect(await session.original(item!)).toEqual(NEWER.media[0]?.original);
    const proxied = server.requests.filter((request) => request.url.includes('/objects/'));
    expect(proxied).toHaveLength(1);
    expect(proxied[0]?.url).toBe(`${ORIGIN}${server.proxyPath(item!.original.id)}`);
    expect(proxied[0]?.headers.get('x-kodakiko-link-token')).toBe(server.accessToken);
    expect(proxied[0]?.headers.get('x-kodakiko-view-token')).toBe(VIEW_TOKEN);
  });

  it('refreshes an expired presigned URL once through the entries listing and retries', async () => {
    const server = serve();
    const session = sessionFor(server);
    const [item] = (await session.open()).items;
    server.control.urlVersion = 2;
    expect(await session.original(item!)).toEqual(NEWER.media[0]?.original);
    const bucket = server.requests.filter((request) => request.url.startsWith(BUCKET_ORIGIN)).map((request) => request.url);
    expect(bucket).toEqual([`${BUCKET_ORIGIN}/${item!.original.id}?v=1`, `${BUCKET_ORIGIN}/${item!.original.id}?v=2`]);
    expect(server.requests.filter((request) => request.url.includes('/entries'))).toHaveLength(2);
  });

  it('gives up with LinkFetchError(403) when the refreshed URL is refused too', async () => {
    const server = serve();
    const session = sessionFor(server);
    const [item] = (await session.open()).items;
    server.control.bucketStatus = 403;
    const failure = await session.original(item!).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(LinkFetchError);
    expect(failure).toMatchObject({ status: 403 });
    expect(server.requests.filter((request) => request.url.startsWith(BUCKET_ORIGIN))).toHaveLength(2);
  });

  it('throws LinkFetchError with the status for other failed downloads, without refreshing', async () => {
    const server = serve();
    const session = sessionFor(server);
    const [item] = (await session.open()).items;
    server.control.bucketStatus = 500;
    await expect(session.thumbnail(item!)).rejects.toMatchObject({ status: 500 });
    expect(server.requests.filter((request) => request.url.includes('/entries'))).toHaveLength(1);
  });

  it('fails a download whose ciphertext does not decrypt', async () => {
    const server = serve();
    const session = sessionFor(server);
    const [first, second] = (await session.open()).items;
    await expect(session.original({ ...first!, mediaKey: second!.mediaKey })).rejects.toThrow();
  });
});

describe('createLinkSession video registration and URL refresh', () => {
  it('registers a presigned video for the media service worker with the original variant key and no extra headers', async () => {
    const server = serve();
    const session = sessionFor(server);
    const items = (await session.open()).items;
    const item = items.find((candidate) => candidate.descriptor.kind === 'video')!;
    const registration = session.video(item);
    const mediaKey = server.mediaKeys.get(item.descriptor.mediaId)!;
    expect(registration.key).toEqual(sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey));
    expect(registration.objectId).toBe(item.original.id);
    expect(registration.downloadUrl).toBe(server.bucketUrl(item.original.id));
    expect(registration.extraHeaders).toEqual({});
    expect(registration.plaintextSize).toBe(NEWER.media[1]?.original.length);
    expect(registration.ciphertextSize).toBe(server.objects.get(item.original.id)?.ciphertext.length);
    expect(registration.mimeType).toBe('video/mp4');
  });

  it('registers a proxied video with the origin URL and the link and view token headers', async () => {
    const server = serve({ download: 'proxy' });
    const session = sessionFor(server);
    const item = (await session.open()).items.find((candidate) => candidate.descriptor.kind === 'video')!;
    const registration = session.video(item);
    expect(registration.downloadUrl).toBe(`${ORIGIN}${server.proxyPath(item.original.id)}`);
    expect(lowerKeys(registration.extraHeaders)).toEqual({
      'x-kodakiko-link-token': server.accessToken,
      'x-kodakiko-view-token': VIEW_TOKEN,
    });
  });

  it('refreshUrl re-lists the entries and returns the fresh URL, or null for an unknown object', async () => {
    const server = serve();
    const session = sessionFor(server);
    const item = (await session.open()).items.find((candidate) => candidate.descriptor.kind === 'video')!;
    server.control.urlVersion = 5;
    expect(await session.refreshUrl(item.original.id)).toBe(`${BUCKET_ORIGIN}/${item.original.id}?v=5`);
    expect(session.video(item).downloadUrl).toBe(`${BUCKET_ORIGIN}/${item.original.id}?v=5`);
    expect(await session.refreshUrl('Z'.repeat(22))).toBeNull();
  });
});
