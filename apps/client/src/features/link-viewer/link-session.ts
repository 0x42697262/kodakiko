import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  openJson,
  unwrapKey,
  type Sodium,
} from '@kodakiko/core';
import {
  CollectionMetadataV1,
  EntryMetadataV1,
  ROUTES,
  decodeBytes,
  encodeBytes,
  linkEntriesResponse,
  linkOpenResponse,
  type LinkDownload,
  type MediaDescriptorV1,
} from '@kodakiko/contract';
import type { z } from 'zod';
import type { ApiCall, HttpClient } from '../../adapters/http/http';
import { HTTP_HEADERS } from '../../keys/http-keys';
import { decryptWhole } from '../../services/media/decrypt-whole';

type Descriptor = z.infer<typeof MediaDescriptorV1>;

const KEY_BYTES = 32;
const METADATA_VERSION = 1;
const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();
const HTTP_FORBIDDEN = 403;

export type LinkObjectRef = { readonly id: string; readonly sizeBytes: number; readonly download: LinkDownload };

export type LinkItem = {
  readonly entryId: string;
  readonly position: number;
  readonly descriptor: Descriptor;
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly note: string;
  readonly mediaKey: Uint8Array;
  readonly original: LinkObjectRef;
  readonly thumbnail: LinkObjectRef;
};

export type OpenedLink = {
  readonly title: string;
  readonly maxViews: number | null;
  readonly items: readonly LinkItem[];
};

export type VideoRegistration = {
  readonly key: Uint8Array;
  readonly objectId: string;
  readonly downloadUrl: string;
  readonly extraHeaders: Readonly<Record<string, string | null>>;
  readonly plaintextSize: number;
  readonly ciphertextSize: number;
  readonly mimeType: string;
};

export class LinkFetchError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

export type LinkSession = {
  readonly open: () => Promise<OpenedLink>;
  readonly thumbnail: (item: LinkItem) => Promise<Uint8Array>;
  readonly original: (item: LinkItem) => Promise<Uint8Array>;
  readonly video: (item: LinkItem) => VideoRegistration;
  readonly refreshUrl: (objectId: string) => Promise<string | null>;
};

export type LinkSessionDeps = {
  readonly sodium: Sodium;
  readonly http: HttpClient;
  readonly linkId: string;
  readonly secret: Uint8Array;
  readonly origin: string;
  readonly fetcher?: typeof fetch;
};

function variantKey(sodium: Sodium, mediaKey: Uint8Array, subkey: number): Uint8Array {
  return sodium.kdfDerive(KEY_BYTES, subkey, KDF_CONTEXTS.media, mediaKey);
}

export function createLinkSession(deps: LinkSessionDeps): LinkSession {
  const { sodium, http, linkId } = deps;
  const fetcher = deps.fetcher ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, deps.secret);
  const accessToken = encodeBytes(sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, deps.secret));
  let viewToken: string | null = null;
  let collectionId = '';
  const downloads = new Map<string, LinkDownload>();

  const openCall = (): ApiCall<z.infer<typeof linkOpenResponse>> => ({
    method: 'POST',
    path: ROUTES.publicLinkOpen(linkId),
    responseSchema: linkOpenResponse,
    auth: { linkToken: accessToken },
    idempotent: false,
  });

  const entriesCall = (cursor: string | null): ApiCall<z.infer<typeof linkEntriesResponse>> => ({
    method: 'GET',
    path: `${ROUTES.publicLinkEntries(linkId)}${cursor === null ? '' : `?cursor=${encodeURIComponent(cursor)}`}`,
    responseSchema: linkEntriesResponse,
    auth: { linkToken: accessToken, viewToken },
    idempotent: true,
  });

  async function allPages(): Promise<z.infer<typeof linkEntriesResponse>['items']> {
    const items: z.infer<typeof linkEntriesResponse>['items'] = [];
    let cursor: string | null = null;
    for (;;) {
      const page: z.infer<typeof linkEntriesResponse> = await http.request(entriesCall(cursor));
      items.push(...page.items);
      for (const item of page.items) for (const object of item.objects) downloads.set(object.id, object.download);
      if (page.nextCursor === null) return items;
      cursor = page.nextCursor;
    }
  }

  async function open(): Promise<OpenedLink> {
    const opened = await http.request(openCall());
    viewToken = opened.viewToken;
    collectionId = opened.collectionId;
    const title = openJson(
      sodium,
      linkKey,
      decodeBytes(opened.metadataBlob),
      CollectionMetadataV1,
      METADATA_VERSION,
      NO_MIGRATIONS,
      AD_CONTEXTS.collectionMetadata,
      collectionId,
    ).name;
    const pages = await allPages();
    const items: LinkItem[] = [];
    const sorted = pages
      .map((page) => {
        const entryKey = unwrapKey(sodium, linkKey, decodeBytes(page.entryKeyByCollection), AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${page.entry.id}`);
        const metadata = openJson(sodium, entryKey, decodeBytes(page.entry.metadataBlob), EntryMetadataV1, METADATA_VERSION, NO_MIGRATIONS, AD_CONTEXTS.entryMetadata, page.entry.id);
        return { page, entryKey, metadata };
      })
      .sort((left, right) => Date.parse(right.metadata.takenAtUtc) - Date.parse(left.metadata.takenAtUtc));
    for (const { page, entryKey, metadata } of sorted) {
      for (const descriptor of metadata.media) {
        const media = page.media.find((row) => row.id === descriptor.mediaId);
        const original = page.objects.find((object) => object.mediaId === descriptor.mediaId && object.variant === 'original');
        const thumbnail = page.objects.find((object) => object.mediaId === descriptor.mediaId && object.variant === 'thumbnail');
        if (media === undefined || original === undefined || thumbnail === undefined) continue;
        items.push({
          entryId: page.entry.id,
          position: items.length,
          descriptor,
          takenAtUtc: metadata.takenAtUtc,
          utcOffsetMinutes: metadata.utcOffsetMinutes,
          note: metadata.note,
          mediaKey: unwrapKey(sodium, entryKey, decodeBytes(media.mediaKeyByEntry), AD_CONTEXTS.mediaKeyByEntry, `${page.entry.id}/${descriptor.mediaId}`),
          original: { id: original.id, sizeBytes: original.sizeBytes, download: original.download },
          thumbnail: { id: thumbnail.id, sizeBytes: thumbnail.sizeBytes, download: thumbnail.download },
        });
      }
    }
    return { title, maxViews: opened.maxViews, items };
  }

  function proxyHeaders(): Record<string, string> {
    return { [HTTP_HEADERS.linkToken]: accessToken, ...(viewToken === null ? {} : { [HTTP_HEADERS.viewToken]: viewToken }) };
  }

  async function fetchCiphertext(object: LinkObjectRef, retried = false): Promise<Uint8Array> {
    const download = downloads.get(object.id) ?? object.download;
    const response =
      download.kind === 'presigned'
        ? await fetcher(download.url)
        : await fetcher(`${deps.origin}${download.path}`, { headers: proxyHeaders() });
    if (response.status === HTTP_FORBIDDEN && download.kind === 'presigned' && !retried) {
      await allPages();
      return fetchCiphertext(object, true);
    }
    if (!response.ok) throw new LinkFetchError(response.status);
    return new Uint8Array(await response.arrayBuffer());
  }

  async function decrypted(item: LinkItem, object: LinkObjectRef, subkey: number, plaintextSize: number): Promise<Uint8Array> {
    const key = variantKey(sodium, item.mediaKey, subkey);
    return decryptWhole(sodium, key, await fetchCiphertext(object), plaintextSize);
  }

  return {
    open,
    thumbnail: (item) => decrypted(item, item.thumbnail, KDF_SUBKEY_IDS.mediaThumbnail, item.descriptor.thumbnail.plaintextBytes),
    original: (item) => decrypted(item, item.original, KDF_SUBKEY_IDS.mediaOriginal, item.descriptor.originalPlaintextBytes),
    video: (item) => {
      const download = downloads.get(item.original.id) ?? item.original.download;
      return {
        key: variantKey(sodium, item.mediaKey, KDF_SUBKEY_IDS.mediaOriginal),
        objectId: item.original.id,
        downloadUrl: download.kind === 'presigned' ? download.url : `${deps.origin}${download.path}`,
        extraHeaders: download.kind === 'presigned' ? {} : proxyHeaders(),
        plaintextSize: item.descriptor.originalPlaintextBytes,
        ciphertextSize: item.original.sizeBytes,
        mimeType: item.descriptor.mimeType,
      };
    },
    refreshUrl: async (objectId) => {
      await allPages();
      const download = downloads.get(objectId);
      if (download === undefined) return null;
      return download.kind === 'presigned' ? download.url : `${deps.origin}${download.path}`;
    },
  };
}
