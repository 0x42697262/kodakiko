import { idSchema, type EntryMetadataV1 } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  KEY_BYTES,
  STREAM_CHUNK_SIZE_EXPONENT,
  sealJson,
  streamLayout,
  wipe,
  wrapKey,
  type Sodium,
} from '@kodakiko/core';
import type { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { EntryKeys } from '../../services/keys/entry-keys';
import { EN } from '../../text/en';
import {
  UPLOAD_SELF_TEST_CHUNK_BYTES,
  UPLOAD_SELF_TEST_DIMENSION,
  UPLOAD_SELF_TEST_MIME_TYPE,
  UPLOAD_SELF_TEST_NOTE,
  UPLOAD_SELF_TEST_PLAINTEXT_BYTES,
  UPLOAD_SELF_TEST_THUMBNAIL_BYTES,
} from './self-test-constants';

type Metadata = z.infer<typeof EntryMetadataV1>;

export type UploadSelfTestDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'collectionKey'>;
  readonly userId: string;
  readonly randomId: () => string;
  readonly clock: Clock;
};

export type UploadSelfTestResult = {
  readonly entryId: string;
  readonly objectId: string;
  readonly sizeBytes: number;
};

async function* randomChunks(sodium: Sodium, totalBytes: number): AsyncGenerator<Uint8Array> {
  let remaining = totalBytes;
  while (remaining > 0) {
    const length = Math.min(UPLOAD_SELF_TEST_CHUNK_BYTES, remaining);
    yield sodium.randomBytes(length);
    remaining -= length;
  }
}

export async function enqueueUploadSelfTest(
  deps: UploadSelfTestDeps,
  plaintextBytes: number = UPLOAD_SELF_TEST_PLAINTEXT_BYTES,
): Promise<UploadSelfTestResult> {
  const { sodium, rowStore, fileStore, clock } = deps;
  const collections = await rowStore.transaction((tx) => tx.collections.all());
  const home = collections.find((collection) => collection.kind === 'home');
  if (home === undefined) throw new Error(EN['sync.diagnosticsUploadTestNoHome']);
  const homeKey = await deps.entryKeys.collectionKey(home.id);
  const entryId = idSchema.parse(deps.randomId());
  const mediaId = idSchema.parse(deps.randomId());
  const objectId = idSchema.parse(deps.randomId());
  const entryKey = sodium.randomBytes(KEY_BYTES);
  const mediaKey = sodium.randomBytes(KEY_BYTES);
  const sizeBytes = streamLayout(plaintextBytes, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize;
  const nowIso = clock.nowIso();
  try {
    await fileStore.writeObject(objectId, randomChunks(sodium, sizeBytes));
    const metadata: Metadata = {
      schemaVersion: 1,
      takenAtUtc: nowIso,
      utcOffsetMinutes: 0,
      timeZone: null,
      note: UPLOAD_SELF_TEST_NOTE,
      tags: [],
      location: null,
      media: [
        {
          mediaId,
          kind: 'photo',
          mimeType: UPLOAD_SELF_TEST_MIME_TYPE,
          width: UPLOAD_SELF_TEST_DIMENSION,
          height: UPLOAD_SELF_TEST_DIMENSION,
          durationMs: null,
          originalPlaintextBytes: plaintextBytes,
          thumbnail: {
            width: UPLOAD_SELF_TEST_DIMENSION,
            height: UPLOAD_SELF_TEST_DIMENSION,
            mimeType: 'image/webp',
            plaintextBytes: UPLOAD_SELF_TEST_THUMBNAIL_BYTES,
          },
          capturedAtUtc: null,
          originalFileName: null,
        },
      ],
      createdAtUtc: nowIso,
      editedAtUtc: nowIso,
    };
    const metadataBlob = sealJson(sodium, entryKey, metadata, AD_CONTEXTS.entryMetadata, entryId);
    const entryKeyWrap = wrapKey(
      sodium,
      homeKey,
      entryKey,
      AD_CONTEXTS.entryKeyByCollection,
      `${home.id}/${entryId}`,
    );
    const mediaKeyWrap = wrapKey(
      sodium,
      entryKey,
      mediaKey,
      AD_CONTEXTS.mediaKeyByEntry,
      `${entryId}/${mediaId}`,
    );
    await rowStore.transaction(async (tx) => {
      await tx.entries.put({
        id: entryId,
        ownerId: deps.userId,
        purpose: 'journal',
        revision: 0,
        metadataBlob,
        baseMetadataBlob: null,
        baseRevision: 0,
        serverPendingBlob: null,
        serverPendingRevision: null,
        trashedAt: null,
        createdAt: nowIso,
        updatedAt: nowIso,
        localState: 'pending_create',
      });
      await tx.memberships.put({ collectionId: home.id, entryId, entryKeyWrap, seq: 0 });
      await tx.media.put({ id: mediaId, entryId, mediaKeyWrap });
      await tx.objects.put({
        id: objectId,
        mediaId,
        variant: 'original',
        sizeBytes,
        plaintextSize: plaintextBytes,
        localState: 'present',
        uploadState: 'local',
        uploadJobId: null,
        lastAccessAt: 0,
      });
      await tx.outbox.enqueue({
        kind: 'createEntry',
        payload: { entryId },
        state: 'queued',
        createdAt: clock.now(),
        attempts: 0,
        nextAttemptAt: 0,
        lastErrorCode: null,
      });
    });
  } finally {
    wipe(entryKey);
    wipe(mediaKey);
  }
  return { entryId, objectId, sizeBytes };
}

export type UploadSelfTestCleanupDeps = {
  readonly rowStore: RowStore;
  readonly uploads: { readonly onObjectUploaded: (listener: (entryId: string) => void) => () => void };
  readonly clock: Clock;
  readonly onEnqueued: () => void;
};

export function scheduleUploadSelfTestCleanup(
  deps: UploadSelfTestCleanupDeps,
  entryId: string,
): () => void {
  const unsubscribe = deps.uploads.onObjectUploaded((uploadedEntryId) => {
    if (uploadedEntryId !== entryId) return;
    unsubscribe();
    deps.rowStore
      .transaction((tx) =>
        tx.outbox.enqueue({
          kind: 'deleteEntry',
          payload: { entryId },
          state: 'queued',
          createdAt: deps.clock.now(),
          attempts: 0,
          nextAttemptAt: 0,
          lastErrorCode: null,
        }),
      )
      .then(deps.onEnqueued, () => undefined);
  });
  return unsubscribe;
}
