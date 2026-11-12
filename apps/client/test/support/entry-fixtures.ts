import type { z } from 'zod';
import type { EntryMetadataV1, Id, MediaDescriptorV1 } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  createStreamEncryptor,
  sealJson,
  streamLayout,
  wrapKey,
  STREAM_CHUNK_SIZE_EXPONENT,
  type AccountKeys,
  type Sodium,
} from '@kodakiko/core';
import type { FileStore } from '../../src/adapters/filestore/filestore';
import type { ObjectRow, RowStore } from '../../src/adapters/rowstore/rowstore';
import { createRandomId } from '../../src/adapters/ids';

export type Metadata = z.infer<typeof EntryMetadataV1>;
export type Descriptor = z.infer<typeof MediaDescriptorV1>;

const THUMBNAIL_BYTES = 64;
const NOW_ISO = '2026-11-12T10:00:00.000Z';

export type AccountFixture = {
  readonly sodium: Sodium;
  readonly keys: AccountKeys;
  readonly userId: string;
  readonly homeCollectionId: string;
  readonly homeCollectionKey: Uint8Array;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly randomId: () => string;
};

export async function createAccountFixture(
  sodium: Sodium,
  rowStore: RowStore,
  fileStore: FileStore,
  accountKey = 'fixture/account',
): Promise<AccountFixture> {
  await rowStore.open(accountKey);
  await fileStore.open(accountKey);
  const randomId = createRandomId(sodium);
  const pair = sodium.boxKeypair();
  const keys: AccountKeys = {
    masterKey: sodium.randomBytes(KEY_BYTES),
    privateKey: pair.privateKey,
    publicKey: pair.publicKey,
  };
  const homeCollectionId = randomId();
  const homeCollectionKey = sodium.randomBytes(KEY_BYTES);
  const keyMaterial = wrapKey(
    sodium,
    keys.masterKey,
    homeCollectionKey,
    AD_CONTEXTS.collectionKeyByMaster,
    homeCollectionId,
  );
  await rowStore.transaction((tx) =>
    tx.collections.put({
      id: homeCollectionId,
      kind: 'home',
      ownerId: 'user',
      ownerUsername: 'tester',
      role: 'owner',
      keyMaterial,
      metadataBlob: Uint8Array.of(1),
      metaUpdatedAt: NOW_ISO,
      serverLastSeq: 0,
      cursorSeq: 0,
    }),
  );
  return {
    sodium,
    keys,
    userId: 'user',
    homeCollectionId,
    homeCollectionKey,
    rowStore,
    fileStore,
    randomId,
  };
}

async function* encryptedChunks(
  sodium: Sodium,
  variantKey: Uint8Array,
  plaintext: Uint8Array,
): AsyncGenerator<Uint8Array> {
  const encryptor = createStreamEncryptor(sodium, variantKey, plaintext.length);
  yield encryptor.header;
  const { chunkSize, chunkCount } = encryptor.layout;
  for (let index = 0; index < chunkCount; index += 1) {
    yield encryptor.encryptNext(plaintext.slice(index * chunkSize, (index + 1) * chunkSize));
  }
}

export function descriptorOf(mediaId: string, plaintextBytes: number): Descriptor {
  return {
    mediaId: mediaId as Id,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width: 100,
    height: 100,
    durationMs: null,
    originalPlaintextBytes: plaintextBytes,
    thumbnail: { width: 10, height: 10, mimeType: 'image/webp', plaintextBytes: THUMBNAIL_BYTES },
    capturedAtUtc: null,
    originalFileName: null,
  };
}

export type LocalEntry = {
  readonly entryId: string;
  readonly entryKey: Uint8Array;
  readonly mediaIds: readonly string[];
  readonly originalObjectIds: readonly string[];
  readonly thumbnailObjectIds: readonly string[];
  readonly plaintexts: readonly Uint8Array[];
  readonly metadata: Metadata;
};

export type LocalEntryInput = {
  readonly mediaBytes: readonly Uint8Array[];
  readonly note: string;
  readonly tags: readonly string[];
  readonly enqueue?: boolean;
};

export function sealEntryMetadata(
  fixture: Pick<AccountFixture, 'sodium'>,
  entryKey: Uint8Array,
  entryId: string,
  metadata: Metadata,
): Uint8Array {
  return sealJson(fixture.sodium, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId);
}

export async function createLocalEntry(
  fixture: AccountFixture,
  input: LocalEntryInput,
): Promise<LocalEntry> {
  const { sodium, rowStore, fileStore, randomId } = fixture;
  const entryId = randomId();
  const entryKey = sodium.randomBytes(KEY_BYTES);
  const mediaIds: string[] = [];
  const originalObjectIds: string[] = [];
  const thumbnailObjectIds: string[] = [];
  const descriptors: Descriptor[] = [];
  const mediaRows: Array<{ id: string; entryId: string; mediaKeyWrap: Uint8Array }> = [];
  const objectRows: ObjectRow[] = [];
  for (const plaintext of input.mediaBytes) {
    const mediaId = randomId();
    const mediaKey = sodium.randomBytes(KEY_BYTES);
    const originalKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey);
    const thumbnailKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey);
    const thumbnail = sodium.randomBytes(THUMBNAIL_BYTES);
    const originalObjectId = randomId();
    const thumbnailObjectId = randomId();
    await fileStore.writeObject(originalObjectId, encryptedChunks(sodium, originalKey, plaintext));
    await fileStore.writeObject(thumbnailObjectId, encryptedChunks(sodium, thumbnailKey, thumbnail));
    mediaRows.push({
      id: mediaId,
      entryId,
      mediaKeyWrap: wrapKey(sodium, entryKey, mediaKey, AD_CONTEXTS.mediaKeyByEntry, `${entryId}/${mediaId}`),
    });
    for (const [objectId, variant, size] of [
      [originalObjectId, 'original', plaintext.length],
      [thumbnailObjectId, 'thumbnail', THUMBNAIL_BYTES],
    ] as const) {
      objectRows.push({
        id: objectId,
        mediaId,
        variant,
        sizeBytes: streamLayout(size, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize,
        plaintextSize: size,
        localState: 'present' as const,
        uploadState: 'local' as const,
        uploadJobId: null,
        lastAccessAt: 0,
      });
    }
    mediaIds.push(mediaId);
    originalObjectIds.push(originalObjectId);
    thumbnailObjectIds.push(thumbnailObjectId);
    descriptors.push(descriptorOf(mediaId, plaintext.length));
  }
  const metadata: Metadata = {
    schemaVersion: 1,
    takenAtUtc: NOW_ISO,
    utcOffsetMinutes: 0,
    timeZone: null,
    note: input.note,
    tags: [...input.tags],
    location: null,
    media: descriptors,
    createdAtUtc: NOW_ISO,
    editedAtUtc: NOW_ISO,
  };
  const metadataBlob = sealEntryMetadata(fixture, entryKey, entryId, metadata);
  const entryKeyWrap = wrapKey(
    sodium,
    fixture.homeCollectionKey,
    entryKey,
    AD_CONTEXTS.entryKeyByCollection,
    `${fixture.homeCollectionId}/${entryId}`,
  );
  await rowStore.transaction(async (tx) => {
    await tx.entries.put({
      id: entryId,
      ownerId: fixture.userId,
      purpose: 'journal',
      revision: 0,
      metadataBlob,
      baseMetadataBlob: null,
      baseRevision: 0,
      serverPendingBlob: null,
      serverPendingRevision: null,
      trashedAt: null,
      createdAt: NOW_ISO,
      updatedAt: NOW_ISO,
      localState: 'pending_create',
    });
    await tx.memberships.put({
      collectionId: fixture.homeCollectionId,
      entryId,
      entryKeyWrap,
      seq: 0,
    });
    for (const row of mediaRows) await tx.media.put(row);
    for (const row of objectRows) await tx.objects.put(row);
    if (input.enqueue !== false) {
      await tx.outbox.enqueue({
        kind: 'createEntry',
        payload: { entryId },
        state: 'queued',
        createdAt: 0,
        attempts: 0,
        nextAttemptAt: 0,
        lastErrorCode: null,
      });
    }
  });
  return {
    entryId,
    entryKey,
    mediaIds,
    originalObjectIds,
    thumbnailObjectIds,
    plaintexts: input.mediaBytes,
    metadata,
  };
}
