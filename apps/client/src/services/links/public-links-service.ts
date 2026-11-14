import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  STREAM_CHUNK_SIZE_EXPONENT,
  createStreamEncryptor,
  openJson,
  sealJson,
  streamLayout,
  unwrapKey,
  wipe,
  wrapKey,
  type AccountKeys,
  type Sodium,
} from '@kodakiko/core';
import {
  COLLECTION_NAME_MAX_CHARS,
  CollectionMetadataV1,
  EntryMetadataV1,
  MediaDescriptorV1,
  ROUTES,
  decodeBytes,
  encodeBytes,
  publicLinkDto,
  publicLinkListResponse,
  type LinkExpiry,
} from '@kodakiko/contract';
import { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { ApiCall, HttpClient } from '../../adapters/http/http';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { Uploader } from '../../adapters/uploader/uploader';
import type { EntryKeys } from '../keys/entry-keys';
import { SHARING_CALLS } from '../sharing/sharing-calls';
import { SYNC_CALLS } from '../sync/sync-calls';
import { decryptWhole } from '../media/decrypt-whole';
import { createDecryptingByteSource } from './decrypting-source';
import { buildLinkUrl, LINK_SECRET_BYTES } from './link-url';
import type { LinkStripper } from './link-stripper';
import { StripUndecodableError, UnsupportedVideoError } from './link-stripper';
import { uploadAll, type LinkObject } from './link-uploads';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;

export const LINK_MAX_ENTRIES = 100;
const KEY_BYTES = 32;
const METADATA_SCHEMA_VERSION = 1;

export type CreateLinkInput = {
  readonly entryIds: readonly string[];
  readonly mediaIds: ReadonlySet<string>;
  readonly title: string;
  readonly expiry: LinkExpiry;
  readonly includeNotes: boolean;
};

export type CreateLinkProgress = {
  readonly step: 'preparing' | 'uploading' | 'registering';
  readonly done: number;
  readonly total: number;
  readonly bytesDone: number;
  readonly bytesTotal: number;
};

export type CreateLinkResult = {
  readonly url: string;
  readonly linkId: string;
  readonly skippedEntries: number;
  readonly skippedUndecodable: number;
  readonly skippedUnsupportedVideo: number;
};

export type OwnerLink = {
  readonly linkId: string;
  readonly url: string;
  readonly title: string;
  readonly expiresAt: string | null;
  readonly maxViews: number | null;
  readonly viewCount: number;
  readonly createdAt: string;
};

export class LinkInputError extends Error {}

export type PublicLinksService = {
  readonly create: (
    input: CreateLinkInput,
    onProgress: (progress: CreateLinkProgress) => void,
    signal: AbortSignal,
  ) => Promise<CreateLinkResult>;
  readonly list: () => Promise<readonly OwnerLink[]>;
  readonly revoke: (linkId: string) => Promise<void>;
};

export type PublicLinksServiceDeps = {
  readonly http: HttpClient;
  readonly sodium: Sodium;
  readonly account: () => AccountKeys | null;
  readonly readMetadata: (entryId: string) => Promise<Metadata>;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly fetchObject: (objectId: string) => Promise<void>;
  readonly stripper: LinkStripper;
  readonly uploader: Uploader;
  readonly baseUrl: () => Promise<string>;
  readonly randomId: () => string;
  readonly clock: Clock;
};

const LINK_CALLS = Object.freeze({
  create: (collectionId: string, accessToken: string, expiry: LinkExpiry): ApiCall<z.infer<typeof publicLinkDto>> => ({
    method: 'POST',
    path: ROUTES.publicLinks(),
    body: { collectionId, accessToken, expiry },
    responseSchema: publicLinkDto,
    auth: 'session',
    idempotent: false,
  }),
  list: (): ApiCall<z.infer<typeof publicLinkListResponse>> => ({
    method: 'GET',
    path: ROUTES.publicLinks(),
    responseSchema: publicLinkListResponse,
    auth: 'session',
    idempotent: true,
  }),
  revoke: (linkId: string): ApiCall<unknown> => ({
    method: 'DELETE',
    path: ROUTES.publicLink(linkId),
    responseSchema: z.unknown(),
    auth: 'session',
    idempotent: true,
  }),
});

async function* encryptChunks(
  sodium: Sodium,
  key: Uint8Array,
  chunks: AsyncIterable<Uint8Array>,
  plaintextSize: number,
): AsyncGenerator<Uint8Array> {
  const encryptor = createStreamEncryptor(sodium, key, plaintextSize);
  yield encryptor.header;
  const { chunkSize } = encryptor.layout;
  let buffer = new Uint8Array(0);
  for await (const chunk of chunks) {
    const merged = new Uint8Array(buffer.length + chunk.length);
    merged.set(buffer);
    merged.set(chunk, buffer.length);
    buffer = merged;
    while (buffer.length >= chunkSize && !encryptor.isDone()) {
      yield encryptor.encryptNext(buffer.subarray(0, chunkSize));
      buffer = buffer.slice(chunkSize);
    }
  }
  if (!encryptor.isDone()) yield encryptor.encryptNext(buffer);
}

async function* singleChunk(bytes: Uint8Array): AsyncGenerator<Uint8Array> {
  yield bytes;
}

function variantKeyOf(sodium: Sodium, mediaKey: Uint8Array, subkeyId: number): Uint8Array {
  return sodium.kdfDerive(KEY_BYTES, subkeyId, KDF_CONTEXTS.media, mediaKey);
}

function ciphertextSize(plaintextSize: number): number {
  return streamLayout(plaintextSize, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize;
}

export function createPublicLinksService(deps: PublicLinksServiceDeps): PublicLinksService {
  const { sodium, http, rowStore, fileStore } = deps;

  function requireAccount(): AccountKeys {
    const account = deps.account();
    if (account === null) throw new LinkInputError('locked');
    return account;
  }

  async function validate(input: CreateLinkInput): Promise<Map<string, Metadata>> {
    if (input.entryIds.length === 0 || input.entryIds.length > LINK_MAX_ENTRIES) throw new LinkInputError('entries');
    if (input.mediaIds.size === 0) throw new LinkInputError('media');
    const title = input.title.trim();
    if (title.length === 0 || title.length > COLLECTION_NAME_MAX_CHARS) throw new LinkInputError('title');
    const metadata = new Map<string, Metadata>();
    const allMedia = new Set<string>();
    for (const entryId of input.entryIds) {
      const row = await rowStore.transaction((tx) => tx.entries.get(entryId));
      if (row === null || row.trashedAt !== null) throw new LinkInputError('entry');
      const meta = await deps.readMetadata(entryId);
      for (const media of meta.media) allMedia.add(media.mediaId);
      metadata.set(entryId, meta);
    }
    for (const mediaId of input.mediaIds) if (!allMedia.has(mediaId)) throw new LinkInputError('media');
    return metadata;
  }

  async function objectsOf(mediaId: string) {
    const rows = await rowStore.transaction((tx) => tx.objects.byMedia(mediaId));
    const original = rows.find((row) => row.variant === 'original');
    const thumbnail = rows.find((row) => row.variant === 'thumbnail');
    if (original === undefined || thumbnail === undefined) throw new LinkInputError('objects');
    return { original, thumbnail };
  }

  type CopiedMedia = { readonly descriptor: Descriptor; readonly mediaKey: Uint8Array; readonly objects: readonly LinkObject[] };

  async function copyMedia(entryId: string, source: Descriptor): Promise<CopiedMedia> {
    const { original, thumbnail } = await objectsOf(source.mediaId);
    await deps.fetchObject(original.id);
    await deps.fetchObject(thumbnail.id);
    const originalKey = await deps.entryKeys.variantKey(entryId, source.mediaId, 'original');
    const thumbnailKey = await deps.entryKeys.variantKey(entryId, source.mediaId, 'thumbnail');
    const mediaId = deps.randomId();
    const mediaKey = sodium.randomBytes(KEY_BYTES);
    const newOriginalKey = variantKeyOf(sodium, mediaKey, KDF_SUBKEY_IDS.mediaOriginal);
    const newThumbnailKey = variantKeyOf(sodium, mediaKey, KDF_SUBKEY_IDS.mediaThumbnail);
    const originalObjectId = deps.randomId();
    const thumbnailObjectId = deps.randomId();
    try {
      const plainSource = await createDecryptingByteSource(sodium, fileStore, original.id, originalKey, original.plaintextSize);
      const stripped = await deps.stripper.strip(plainSource, source);
      await fileStore.writeObject(originalObjectId, encryptChunks(sodium, newOriginalKey, stripped.chunks, stripped.plaintextSize));
      const thumbCiphertext = await fileStore.readRange(thumbnail.id, 0, thumbnail.sizeBytes);
      const thumbBytes = decryptWhole(sodium, thumbnailKey, thumbCiphertext, thumbnail.plaintextSize);
      await fileStore.writeObject(
        thumbnailObjectId,
        encryptChunks(sodium, newThumbnailKey, singleChunk(thumbBytes), thumbBytes.length),
      );
      const descriptor = MediaDescriptorV1.parse({
        ...source,
        mediaId,
        mimeType: stripped.mimeType,
        width: stripped.width,
        height: stripped.height,
        durationMs: stripped.durationMs,
        originalPlaintextBytes: stripped.plaintextSize,
        thumbnail: { ...source.thumbnail, plaintextBytes: thumbBytes.length },
        capturedAtUtc: null,
        originalFileName: null,
      });
      return {
        descriptor,
        mediaKey,
        objects: [
          { id: originalObjectId, mediaId, variant: 'original', sizeBytes: ciphertextSize(stripped.plaintextSize) },
          { id: thumbnailObjectId, mediaId, variant: 'thumbnail', sizeBytes: ciphertextSize(thumbBytes.length) },
        ],
      };
    } catch (error) {
      wipe(mediaKey);
      await fileStore.delete(originalObjectId).catch(() => undefined);
      await fileStore.delete(thumbnailObjectId).catch(() => undefined);
      throw error;
    } finally {
      wipe(originalKey);
      wipe(thumbnailKey);
      wipe(newOriginalKey);
      wipe(newThumbnailKey);
    }
  }

  async function create(
    input: CreateLinkInput,
    onProgress: (progress: CreateLinkProgress) => void,
    signal: AbortSignal,
  ): Promise<CreateLinkResult> {
    const account = requireAccount();
    const metadata = await validate(input);
    const linkSecret = sodium.randomBytes(LINK_SECRET_BYTES);
    const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, linkSecret);
    const accessToken = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkAccessToken, KDF_CONTEXTS.link, linkSecret);
    const collectionId = deps.randomId();
    const keyByOwner = wrapKey(sodium, account.masterKey, linkSecret, AD_CONTEXTS.linkSecretByMaster, collectionId);
    const collectionMetadata = { schemaVersion: METADATA_SCHEMA_VERSION, name: input.title.trim(), createdAtUtc: deps.clock.nowIso() };
    const sealedName = sealJson(sodium, linkKey, collectionMetadata, AD_CONTEXTS.collectionMetadata, collectionId);
    const pendingObjects: LinkObject[] = [];
    let created = false;
    try {
      await http.request(
        SHARING_CALLS.create({
          id: collectionId as never,
          kind: 'link',
          keyByOwner: encodeBytes(keyByOwner),
          metadataBlob: encodeBytes(sealedName),
        }),
      );
      created = true;
      let skippedEntries = 0;
      let skippedUndecodable = 0;
      let skippedUnsupportedVideo = 0;
      const total = input.entryIds.length;
      for (const [done, entryId] of input.entryIds.entries()) {
        signal.throwIfAborted();
        onProgress({ step: 'preparing', done, total, bytesDone: 0, bytesTotal: 0 });
        const meta = metadata.get(entryId);
        const selected = meta?.media.filter((media) => input.mediaIds.has(media.mediaId)) ?? [];
        if (meta === undefined || selected.length === 0) continue;
        const copies: CopiedMedia[] = [];
        for (const media of selected) {
          try {
            copies.push(await copyMedia(entryId, media));
          } catch (error) {
            if (error instanceof StripUndecodableError) skippedUndecodable += 1;
            else if (error instanceof UnsupportedVideoError) skippedUnsupportedVideo += 1;
            else throw error;
          }
        }
        if (copies.length === 0) {
          skippedEntries += 1;
          continue;
        }
        const copyId = deps.randomId();
        const entryKey = sodium.randomBytes(KEY_BYTES);
        try {
          const now = deps.clock.nowIso();
          const copyMetadata = EntryMetadataV1.parse({
            ...meta,
            timeZone: null,
            note: input.includeNotes ? meta.note : '',
            tags: [],
            location: null,
            media: copies.map((copy) => copy.descriptor),
            createdAtUtc: now,
            editedAtUtc: now,
          });
          await http.request(
            SYNC_CALLS.createEntry({
              id: copyId as never,
              purpose: 'link_copy',
              metadataBlob: encodeBytes(sealJson(sodium, entryKey, copyMetadata, AD_CONTEXTS.entryMetadata, copyId)),
              memberships: [
                {
                  collectionId: collectionId as never,
                  entryKeyByCollection: encodeBytes(
                    wrapKey(sodium, linkKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${copyId}`),
                  ),
                },
              ],
              media: copies.map((copy) => ({
                mediaId: copy.descriptor.mediaId,
                mediaKeyByEntry: encodeBytes(
                  wrapKey(sodium, entryKey, copy.mediaKey, AD_CONTEXTS.mediaKeyByEntry, `${copyId}/${copy.descriptor.mediaId}`),
                ),
              })),
            }),
          );
          for (const copy of copies) pendingObjects.push(...copy.objects);
        } finally {
          wipe(entryKey);
          for (const copy of copies) wipe(copy.mediaKey);
        }
      }
      if (pendingObjects.length === 0) throw new LinkInputError('nothing');
      const bytesTotal = pendingObjects.reduce((sum, object) => sum + object.sizeBytes, 0);
      onProgress({ step: 'uploading', done: 0, total: pendingObjects.length, bytesDone: 0, bytesTotal });
      await uploadAll(
        { http, uploader: deps.uploader, fileStore },
        pendingObjects,
        (bytesDone) => onProgress({ step: 'uploading', done: 0, total: pendingObjects.length, bytesDone, bytesTotal }),
        signal,
      );
      onProgress({ step: 'registering', done: 0, total: 1, bytesDone: bytesTotal, bytesTotal });
      const link = await http.request(LINK_CALLS.create(collectionId, encodeBytes(accessToken), input.expiry));
      return {
        url: buildLinkUrl(await deps.baseUrl(), link.linkId, linkSecret),
        linkId: link.linkId,
        skippedEntries,
        skippedUndecodable,
        skippedUnsupportedVideo,
      };
    } catch (error) {
      if (created) await http.request(SHARING_CALLS.remove(collectionId)).catch(() => undefined);
      for (const object of pendingObjects) await fileStore.delete(object.id).catch(() => undefined);
      throw error;
    } finally {
      wipe(linkSecret);
      wipe(linkKey);
      wipe(accessToken);
    }
  }

  async function list(): Promise<readonly OwnerLink[]> {
    const account = requireAccount();
    const base = await deps.baseUrl();
    const { items } = await http.request(LINK_CALLS.list());
    const links: OwnerLink[] = [];
    for (const item of items) {
      const collection = await http.request(SYNC_CALLS.collection(item.collectionId));
      const secret = unwrapKey(sodium, account.masterKey, decodeBytes(collection.keyMaterial), AD_CONTEXTS.linkSecretByMaster, collection.id);
      const linkKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, secret);
      try {
        const title = openJson(
          sodium,
          linkKey,
          decodeBytes(collection.metadataBlob),
          CollectionMetadataV1,
          METADATA_SCHEMA_VERSION,
          new Map(),
          AD_CONTEXTS.collectionMetadata,
          collection.id,
        ).name;
        links.push({
          linkId: item.linkId,
          url: buildLinkUrl(base, item.linkId, secret),
          title,
          expiresAt: item.expiresAt,
          maxViews: item.maxViews,
          viewCount: item.viewCount,
          createdAt: item.createdAt,
        });
      } finally {
        wipe(secret);
        wipe(linkKey);
      }
    }
    return links;
  }

  return {
    create,
    list,
    revoke: async (linkId) => {
      await http.request(LINK_CALLS.revoke(linkId));
    },
  };
}

