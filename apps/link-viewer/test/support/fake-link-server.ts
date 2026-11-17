import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  concatBytes,
  createStreamEncryptor,
  sealJson,
  wrapKey,
  type Sodium,
} from '@kodakiko/core';
import { ROUTES, encodeBytes, linkEntriesResponse, linkOpenResponse, type ErrorCode } from '@kodakiko/contract';

export const ORIGIN = 'https://journal.example.test';
export const BUCKET_ORIGIN = 'https://bucket.example.test';
export const VIEW_TOKEN = 'view-token-1';
const NOW = '2026-06-01T10:00:00.000Z';
const URL_EXPIRES = '2026-06-01T11:00:00.000Z';
const KEY_BYTES = 32;
const ID_BYTES = 16;
const HTTP_OK = 200;
const HTTP_FORBIDDEN = 403;
const HTTP_NOT_FOUND = 404;

export type FakeMedia = {
  readonly kind: 'photo' | 'video';
  readonly mimeType: string;
  readonly original: Uint8Array;
  readonly thumbnail: Uint8Array;
  readonly missing?: 'original' | 'thumbnail';
};

export type FakeEntry = {
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly note: string;
  readonly media: readonly FakeMedia[];
};

export type FakeLinkOptions = {
  readonly title: string;
  readonly entries: readonly FakeEntry[];
  readonly maxViews?: number | null;
  readonly download?: 'presigned' | 'proxy';
  readonly pageSize?: number;
  readonly tamperTitle?: boolean;
};

export type FakeRequest = { readonly method: string; readonly url: string; readonly headers: Headers };

export type FakeObject = {
  readonly id: string;
  readonly mediaId: string;
  readonly variant: 'original' | 'thumbnail';
  readonly ciphertext: Uint8Array;
};

export type FakeLinkServer = {
  readonly linkId: string;
  readonly secret: Uint8Array;
  readonly accessToken: string;
  readonly collectionId: string;
  readonly entryIds: readonly string[];
  readonly mediaIds: readonly (readonly string[])[];
  readonly mediaKeys: ReadonlyMap<string, Uint8Array>;
  readonly objects: ReadonlyMap<string, FakeObject>;
  readonly requests: FakeRequest[];
  readonly fetcher: typeof fetch;
  readonly control: {
    offline: boolean;
    openError: { readonly status: number; readonly code: ErrorCode } | null;
    entriesError: { readonly status: number; readonly code: ErrorCode } | null;
    urlVersion: number;
    bucketStatus: number | null;
  };
  readonly bucketUrl: (objectId: string) => string;
  readonly proxyPath: (objectId: string) => string;
};

function encryptWhole(sodium: Sodium, key: Uint8Array, plaintext: Uint8Array): Uint8Array {
  const encryptor = createStreamEncryptor(sodium, key, plaintext.length);
  const parts = [encryptor.header];
  for (let index = 0; index < encryptor.layout.chunkCount; index += 1) {
    const start = index * encryptor.layout.chunkSize;
    parts.push(encryptor.encryptNext(plaintext.subarray(start, Math.min(start + encryptor.layout.chunkSize, plaintext.length))));
  }
  return concatBytes(...parts);
}

function errorResponse(status: number, code: ErrorCode): Response {
  return new Response(JSON.stringify({ error: { code, message: code, requestId: 'req-fake', details: null } }), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: HTTP_OK, headers: { 'content-type': 'application/json' } });
}

export function createFakeLinkServer(sodium: Sodium, options: FakeLinkOptions): FakeLinkServer {
  const randomId = () => encodeBytes(sodium.randomBytes(ID_BYTES));
  const linkId = randomId();
  const collectionId = randomId();
  const ownerId = randomId();
  const secret = sodium.randomBytes(KEY_BYTES);
  const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, secret);
  const accessToken = encodeBytes(sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, secret));
  const titleKey = options.tamperTitle === true ? sodium.randomBytes(KEY_BYTES) : linkKey;
  const collectionMetadata = { schemaVersion: 1, name: options.title, createdAtUtc: NOW };
  const metadataBlob = encodeBytes(sealJson(sodium, titleKey, collectionMetadata, AD_CONTEXTS.collectionMetadata, collectionId));
  const mediaKeys = new Map<string, Uint8Array>();
  const objects = new Map<string, FakeObject>();
  const entryIds: string[] = [];
  const mediaIds: string[][] = [];
  const requests: FakeRequest[] = [];
  const control: FakeLinkServer['control'] = { offline: false, openError: null, entriesError: null, urlVersion: 1, bucketStatus: null };
  const download = options.download ?? 'presigned';
  const pageSize = options.pageSize ?? Number.MAX_SAFE_INTEGER;

  type Built = { readonly item: unknown; readonly objectIds: readonly string[] };
  const builtEntries: { readonly build: () => Built }[] = [];

  const bucketUrl = (objectId: string) => `${BUCKET_ORIGIN}/${objectId}?v=${control.urlVersion}`;
  const proxyPath = (objectId: string) => ROUTES.publicLinkObjectContent(linkId, objectId);

  for (const entry of options.entries) {
    const entryId = randomId();
    entryIds.push(entryId);
    const entryKey = sodium.randomBytes(KEY_BYTES);
    const descriptors: unknown[] = [];
    const mediaRows: unknown[] = [];
    const entryObjects: FakeObject[] = [];
    const ids: string[] = [];
    for (const media of entry.media) {
      const mediaId = randomId();
      ids.push(mediaId);
      const mediaKey = sodium.randomBytes(KEY_BYTES);
      mediaKeys.set(mediaId, mediaKey);
      const originalKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey);
      const thumbnailKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey);
      descriptors.push({
        mediaId,
        kind: media.kind,
        mimeType: media.mimeType,
        width: 640,
        height: 480,
        durationMs: media.kind === 'video' ? 3000 : null,
        originalPlaintextBytes: media.original.length,
        thumbnail: { width: 160, height: 120, mimeType: 'image/webp', plaintextBytes: media.thumbnail.length },
        capturedAtUtc: null,
        originalFileName: null,
      });
      mediaRows.push({
        id: mediaId,
        entryId,
        mediaKeyByEntry: encodeBytes(wrapKey(sodium, entryKey, mediaKey, AD_CONTEXTS.mediaKeyByEntry, `${entryId}/${mediaId}`)),
        createdAt: NOW,
      });
      if (media.missing !== 'original') {
        entryObjects.push({ id: randomId(), mediaId, variant: 'original', ciphertext: encryptWhole(sodium, originalKey, media.original) });
      }
      if (media.missing !== 'thumbnail') {
        entryObjects.push({ id: randomId(), mediaId, variant: 'thumbnail', ciphertext: encryptWhole(sodium, thumbnailKey, media.thumbnail) });
      }
    }
    mediaIds.push(ids);
    for (const object of entryObjects) objects.set(object.id, object);
    const metadata = {
      schemaVersion: 1,
      takenAtUtc: entry.takenAtUtc,
      utcOffsetMinutes: entry.utcOffsetMinutes,
      timeZone: null,
      note: entry.note,
      tags: [],
      location: null,
      media: descriptors,
      createdAtUtc: NOW,
      editedAtUtc: NOW,
    };
    const entryBlob = encodeBytes(sealJson(sodium, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId));
    const entryKeyByCollection = encodeBytes(
      wrapKey(sodium, linkKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${entryId}`),
    );
    builtEntries.push({
      build: () => ({
        item: {
          entry: { id: entryId, ownerId, purpose: 'link_copy', revision: 1, metadataBlob: entryBlob, trashedAt: null, createdAt: NOW, updatedAt: NOW },
          entryKeyByCollection,
          media: mediaRows,
          objects: entryObjects.map((object) => ({
            id: object.id,
            mediaId: object.mediaId,
            variant: object.variant,
            sizeBytes: object.ciphertext.length,
            state: 'uploaded',
            download:
              download === 'presigned'
                ? { kind: 'presigned', url: bucketUrl(object.id), expiresAt: URL_EXPIRES }
                : { kind: 'proxy', path: proxyPath(object.id) },
          })),
        },
        objectIds: entryObjects.map((object) => object.id),
      }),
    });
  }

  const openPath = ROUTES.publicLinkOpen(linkId);
  const entriesPath = ROUTES.publicLinkEntries(linkId);

  function linkTokenMatches(headers: Headers): boolean {
    return headers.get('x-kodakiko-link-token') === accessToken;
  }

  function viewTokenMatches(headers: Headers): boolean {
    return headers.get('x-kodakiko-view-token') === VIEW_TOKEN;
  }

  function open(headers: Headers): Response {
    if (!linkTokenMatches(headers)) return errorResponse(HTTP_FORBIDDEN, 'LINK_TOKEN_INVALID');
    if (control.openError !== null) return errorResponse(control.openError.status, control.openError.code);
    return jsonResponse(
      linkOpenResponse.parse({ collectionId, metadataBlob, expiresAt: null, maxViews: options.maxViews ?? null, viewToken: VIEW_TOKEN }),
    );
  }

  function entries(url: URL, headers: Headers): Response {
    if (!linkTokenMatches(headers)) return errorResponse(HTTP_FORBIDDEN, 'LINK_TOKEN_INVALID');
    if (!viewTokenMatches(headers)) return errorResponse(HTTP_FORBIDDEN, 'LINK_VIEW_EXPIRED');
    if (control.entriesError !== null) return errorResponse(control.entriesError.status, control.entriesError.code);
    const start = Number(url.searchParams.get('cursor') ?? '0');
    const slice = builtEntries.slice(start, start + pageSize).map((entry) => entry.build().item);
    const next = start + pageSize < builtEntries.length ? String(start + pageSize) : null;
    return jsonResponse(linkEntriesResponse.parse({ items: slice, nextCursor: next }));
  }

  function bucket(url: URL): Response {
    const object = objects.get(url.pathname.slice(1));
    if (control.bucketStatus !== null) return new Response(null, { status: control.bucketStatus });
    if (object === undefined) return new Response(null, { status: HTTP_NOT_FOUND });
    if (url.searchParams.get('v') !== String(control.urlVersion)) return new Response(null, { status: HTTP_FORBIDDEN });
    return new Response(object.ciphertext.slice(), { status: HTTP_OK });
  }

  function proxy(path: string, headers: Headers): Response | null {
    for (const object of objects.values()) {
      if (proxyPath(object.id) !== path) continue;
      if (!linkTokenMatches(headers)) return errorResponse(HTTP_FORBIDDEN, 'LINK_TOKEN_INVALID');
      if (!viewTokenMatches(headers)) return errorResponse(HTTP_FORBIDDEN, 'LINK_VIEW_EXPIRED');
      return new Response(object.ciphertext.slice(), { status: HTTP_OK });
    }
    return null;
  }

  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input instanceof Request ? input.url : input));
    const method = init?.method ?? 'GET';
    const headers = new Headers(init?.headers);
    requests.push({ method, url: url.toString(), headers });
    if (control.offline) throw new TypeError('Failed to fetch');
    if (url.origin === BUCKET_ORIGIN && method === 'GET') return bucket(url);
    if (url.origin !== ORIGIN) return new Response(null, { status: HTTP_NOT_FOUND });
    if (method === 'POST' && url.pathname === openPath) return open(headers);
    if (method === 'GET' && url.pathname === entriesPath) return entries(url, headers);
    if (method === 'GET') return proxy(url.pathname, headers) ?? errorResponse(HTTP_NOT_FOUND, 'NOT_FOUND');
    return errorResponse(HTTP_FORBIDDEN, 'LINK_TOKEN_INVALID');
  }) as typeof fetch;

  return {
    linkId,
    secret,
    accessToken,
    collectionId,
    entryIds,
    mediaIds,
    mediaKeys,
    objects,
    requests,
    fetcher,
    control,
    bucketUrl,
    proxyPath,
  };
}
