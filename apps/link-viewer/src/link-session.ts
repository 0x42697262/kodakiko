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
  LINK_TOKEN_HEADER,
  LINK_VIEW_TOKEN_HEADER,
  ROUTES,
  decodeBytes,
  encodeBytes,
  linkEntriesResponse,
  linkOpenResponse,
  type LinkDownload,
  type LinkEntriesResponse,
  type LinkEntryItem,
  type LinkOpenResponse,
  type MediaDescriptorV1,
} from '@kodakiko/contract';
import type { z } from 'zod';
import { HTTP_STATUS, KEY_BYTES, METADATA_VERSION } from './constants.ts';
import { decryptWhole } from './decrypt-whole.ts';
import type { ApiCall, HttpClient } from './http.ts';
import { CURSOR_QUERY } from './keys.ts';
import { TEXT } from './text.ts';

export type MediaDescriptor = z.infer<typeof MediaDescriptorV1>;
export type LinkMediaKind = 'photo' | 'video';
export type LinkMediaDescriptor = MediaDescriptor & { readonly kind: LinkMediaKind };

export type LinkObjectRef = {
  readonly id: string;
  readonly sizeBytes: number;
  readonly download: LinkDownload;
};

export type LinkItem = {
  readonly entryId: string;
  readonly position: number;
  readonly descriptor: LinkMediaDescriptor;
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
    super(TEXT.fetchError(status));
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

type EntryMetadata = z.infer<typeof EntryMetadataV1>;
type OpenedEntry = {
  readonly page: LinkEntryItem;
  readonly entryKey: Uint8Array;
  readonly metadata: EntryMetadata;
};

const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();

function isLinkMedia(descriptor: MediaDescriptor): descriptor is LinkMediaDescriptor {
  return descriptor.kind === 'photo' || descriptor.kind === 'video';
}

function byNewest(left: OpenedEntry, right: OpenedEntry): number {
  return Date.parse(right.metadata.takenAtUtc) - Date.parse(left.metadata.takenAtUtc);
}

export function createLinkSession(deps: LinkSessionDeps): LinkSession {
  const { sodium, http, linkId } = deps;
  const fetcher =
    deps.fetcher ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const linkKey = sodium.kdfDerive(
    KEY_BYTES,
    KDF_SUBKEY_IDS.linkCollectionKey,
    KDF_CONTEXTS.link,
    deps.secret,
  );
  const accessToken = encodeBytes(
    sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, deps.secret),
  );
  const downloads = new Map<string, LinkDownload>();
  let viewToken: string | null = null;
  let collectionId = '';

  const openCall = (): ApiCall<LinkOpenResponse> => ({
    method: 'POST',
    path: ROUTES.publicLinkOpen(linkId),
    responseSchema: linkOpenResponse,
    auth: { linkToken: accessToken },
    idempotent: false,
  });

  const entriesCall = (cursor: string | null): ApiCall<LinkEntriesResponse> => ({
    method: 'GET',
    path: `${ROUTES.publicLinkEntries(linkId)}${cursor === null ? '' : `${CURSOR_QUERY}${encodeURIComponent(cursor)}`}`,
    responseSchema: linkEntriesResponse,
    auth: { linkToken: accessToken, viewToken },
    idempotent: true,
  });

  async function allPages(): Promise<LinkEntryItem[]> {
    const items: LinkEntryItem[] = [];
    let cursor: string | null = null;
    for (;;) {
      const page: LinkEntriesResponse = await http.request(entriesCall(cursor));
      items.push(...page.items);
      for (const item of page.items)
        for (const object of item.objects) downloads.set(object.id, object.download);
      if (page.nextCursor === null) return items;
      cursor = page.nextCursor;
    }
  }

  function openEntry(page: LinkEntryItem): OpenedEntry {
    const entryId = page.entry.id;
    const entryKey = unwrapKey(
      sodium,
      linkKey,
      decodeBytes(page.entryKeyByCollection),
      AD_CONTEXTS.entryKeyByCollection,
      `${collectionId}/${entryId}`,
    );
    const metadata = openJson(
      sodium,
      entryKey,
      decodeBytes(page.entry.metadataBlob),
      EntryMetadataV1,
      METADATA_VERSION,
      NO_MIGRATIONS,
      AD_CONTEXTS.entryMetadata,
      entryId,
    );
    return { page, entryKey, metadata };
  }

  function entryItems(
    { page, entryKey, metadata }: OpenedEntry,
    firstPosition: number,
  ): LinkItem[] {
    const items: LinkItem[] = [];
    for (const descriptor of metadata.media) {
      if (!isLinkMedia(descriptor)) continue;
      const media = page.media.find((row) => row.id === descriptor.mediaId);
      const original = page.objects.find(
        (object) => object.mediaId === descriptor.mediaId && object.variant === 'original',
      );
      const thumbnail = page.objects.find(
        (object) => object.mediaId === descriptor.mediaId && object.variant === 'thumbnail',
      );
      if (media === undefined || original === undefined || thumbnail === undefined) continue;
      items.push({
        entryId: page.entry.id,
        position: firstPosition + items.length,
        descriptor,
        takenAtUtc: metadata.takenAtUtc,
        utcOffsetMinutes: metadata.utcOffsetMinutes,
        note: metadata.note,
        mediaKey: unwrapKey(
          sodium,
          entryKey,
          decodeBytes(media.mediaKeyByEntry),
          AD_CONTEXTS.mediaKeyByEntry,
          `${page.entry.id}/${descriptor.mediaId}`,
        ),
        original: { id: original.id, sizeBytes: original.sizeBytes, download: original.download },
        thumbnail: {
          id: thumbnail.id,
          sizeBytes: thumbnail.sizeBytes,
          download: thumbnail.download,
        },
      });
    }
    return items;
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
    const entries = (await allPages()).map(openEntry).sort(byNewest);
    const items: LinkItem[] = [];
    for (const entry of entries) items.push(...entryItems(entry, items.length));
    return { title, maxViews: opened.maxViews, items };
  }

  function proxyHeaders(): Record<string, string> {
    return {
      [LINK_TOKEN_HEADER]: accessToken,
      ...(viewToken === null ? {} : { [LINK_VIEW_TOKEN_HEADER]: viewToken }),
    };
  }

  function urlOf(download: LinkDownload): string {
    return download.kind === 'presigned' ? download.url : `${deps.origin}${download.path}`;
  }

  async function fetchCiphertext(object: LinkObjectRef, retried = false): Promise<Uint8Array> {
    const download = downloads.get(object.id) ?? object.download;
    const response =
      download.kind === 'presigned'
        ? await fetcher(download.url, { credentials: 'omit' })
        : await fetcher(urlOf(download), { headers: proxyHeaders(), credentials: 'omit' });
    if (response.status === HTTP_STATUS.forbidden && download.kind === 'presigned' && !retried) {
      await allPages();
      return fetchCiphertext(object, true);
    }
    if (!response.ok) throw new LinkFetchError(response.status);
    return new Uint8Array(await response.arrayBuffer());
  }

  function variantKey(item: LinkItem, subkey: number): Uint8Array {
    return sodium.kdfDerive(KEY_BYTES, subkey, KDF_CONTEXTS.media, item.mediaKey);
  }

  async function decrypted(
    item: LinkItem,
    object: LinkObjectRef,
    subkey: number,
    plaintextSize: number,
  ): Promise<Uint8Array> {
    const ciphertext = await fetchCiphertext(object);
    return decryptWhole(sodium, variantKey(item, subkey), ciphertext, plaintextSize);
  }

  return {
    open,
    thumbnail: (item) =>
      decrypted(
        item,
        item.thumbnail,
        KDF_SUBKEY_IDS.mediaThumbnail,
        item.descriptor.thumbnail.plaintextBytes,
      ),
    original: (item) =>
      decrypted(
        item,
        item.original,
        KDF_SUBKEY_IDS.mediaOriginal,
        item.descriptor.originalPlaintextBytes,
      ),
    video: (item) => {
      const download = downloads.get(item.original.id) ?? item.original.download;
      return {
        key: variantKey(item, KDF_SUBKEY_IDS.mediaOriginal),
        objectId: item.original.id,
        downloadUrl: urlOf(download),
        extraHeaders: download.kind === 'presigned' ? {} : proxyHeaders(),
        plaintextSize: item.descriptor.originalPlaintextBytes,
        ciphertextSize: item.original.sizeBytes,
        mimeType: item.descriptor.mimeType,
      };
    },
    refreshUrl: async (objectId) => {
      await allPages();
      const download = downloads.get(objectId);
      return download === undefined ? null : urlOf(download);
    },
  };
}
