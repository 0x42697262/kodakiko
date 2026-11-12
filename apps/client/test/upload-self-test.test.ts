import { beforeEach, describe, expect, it } from 'vitest';
import { EntryMetadataV1 } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  STREAM_CHUNK_SIZE_EXPONENT,
  openJson,
  streamLayout,
  unwrapKey,
  type Sodium,
} from '@kodakiko/core';
import type { Clock } from '../src/adapters/clock';
import { createEntryKeys } from '../src/services/keys/entry-keys';
import {
  enqueueUploadSelfTest,
  scheduleUploadSelfTestCleanup,
  type UploadSelfTestDeps,
} from '../src/features/diagnostics/upload-self-test';
import { EN } from '../src/text/en';
import {
  createAccountFixture,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
} from './support';

const PLAINTEXT_BYTES = 150_000;
const NOW_ISO = '2026-11-12T10:00:00.000Z';
const CLOCK: Clock = { now: () => Date.parse(NOW_ISO), nowIso: () => NOW_ISO };

let sodium: Sodium;
let fixture: AccountFixture;

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
});

function depsFor(subject: AccountFixture): UploadSelfTestDeps {
  return {
    sodium,
    rowStore: subject.rowStore,
    fileStore: subject.fileStore,
    entryKeys: createEntryKeys({ sodium, rowStore: subject.rowStore, account: () => subject.keys }),
    userId: subject.userId,
    randomId: subject.randomId,
    clock: CLOCK,
  };
}

describe('enqueueUploadSelfTest', () => {
  it('stores a pending_create entry with unwrappable keys and a queued createEntry item', async () => {
    const result = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);

    const snapshot = await fixture.rowStore.transaction(async (tx) => ({
      entry: await tx.entries.get(result.entryId),
      memberships: await tx.memberships.byEntry(result.entryId),
      media: await tx.media.byEntry(result.entryId),
      objects: await tx.objects.all(),
      outbox: await tx.outbox.all(),
    }));
    expect(snapshot.entry).toMatchObject({
      purpose: 'journal',
      localState: 'pending_create',
      baseRevision: 0,
      baseMetadataBlob: null,
    });
    expect(snapshot.memberships).toHaveLength(1);
    expect(snapshot.memberships[0]?.collectionId).toBe(fixture.homeCollectionId);
    const entryKey = unwrapKey(
      sodium,
      fixture.homeCollectionKey,
      snapshot.memberships[0]?.entryKeyWrap ?? new Uint8Array(),
      AD_CONTEXTS.entryKeyByCollection,
      `${fixture.homeCollectionId}/${result.entryId}`,
    );
    expect(snapshot.media).toHaveLength(1);
    const mediaId = snapshot.media[0]?.id ?? '';
    expect(() =>
      unwrapKey(
        sodium,
        entryKey,
        snapshot.media[0]?.mediaKeyWrap ?? new Uint8Array(),
        AD_CONTEXTS.mediaKeyByEntry,
        `${result.entryId}/${mediaId}`,
      ),
    ).not.toThrow();
    expect(snapshot.outbox).toHaveLength(1);
    expect(snapshot.outbox[0]).toMatchObject({
      kind: 'createEntry',
      payload: { entryId: result.entryId },
      state: 'queued',
    });
  });

  it('writes one original object of the exact ciphertext size to the FileStore', async () => {
    const result = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);

    const expectedSize = streamLayout(PLAINTEXT_BYTES, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize;
    expect(result.sizeBytes).toBe(expectedSize);
    expect(await fixture.fileStore.size(result.objectId)).toBe(expectedSize);
    const objects = await fixture.rowStore.transaction((tx) => tx.objects.all());
    expect(objects).toHaveLength(1);
    expect(objects[0]).toMatchObject({
      id: result.objectId,
      variant: 'original',
      sizeBytes: expectedSize,
      localState: 'present',
      uploadState: 'local',
      uploadJobId: null,
    });
  });

  it('seals metadata that opens with the entry key and describes the media', async () => {
    const result = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);

    const keys = createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => fixture.keys });
    const entry = await fixture.rowStore.transaction((tx) => tx.entries.get(result.entryId));
    const metadata = openJson(
      sodium,
      await keys.entryKey(result.entryId),
      entry?.metadataBlob ?? new Uint8Array(),
      EntryMetadataV1,
      1,
      new Map(),
      AD_CONTEXTS.entryMetadata,
      result.entryId,
    );
    const media = await fixture.rowStore.transaction((tx) => tx.media.byEntry(result.entryId));
    expect(metadata.media).toHaveLength(1);
    expect(metadata.media[0]).toMatchObject({
      mediaId: media[0]?.id,
      originalPlaintextBytes: PLAINTEXT_BYTES,
    });
    expect(metadata.createdAtUtc).toBe(NOW_ISO);
  });

  it('produces different ids and bytes on every run', async () => {
    const first = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);
    const second = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);

    expect(second.entryId).not.toBe(first.entryId);
    expect(second.objectId).not.toBe(first.objectId);
    const firstHead = await fixture.fileStore.readRange(first.objectId, 0, 64);
    const secondHead = await fixture.fileStore.readRange(second.objectId, 0, 64);
    expect(firstHead).not.toEqual(secondHead);
  });

  it('refuses when no home collection is on the device', async () => {
    const rowStore = createMemoryRowStore();
    const fileStore = createMemoryFileStore();
    await rowStore.open('empty/account');
    await fileStore.open('empty/account');
    const empty: AccountFixture = { ...fixture, rowStore, fileStore };

    await expect(enqueueUploadSelfTest(depsFor(empty), PLAINTEXT_BYTES)).rejects.toThrow(
      EN['sync.diagnosticsUploadTestNoHome'],
    );
  });

  describe('cleanup', () => {
    function uploadsPort() {
      const listeners = new Set<(entryId: string) => void>();
      return {
        port: {
          onObjectUploaded: (listener: (entryId: string) => void) => {
            listeners.add(listener);
            return () => {
              listeners.delete(listener);
            };
          },
        },
        fire: (entryId: string) => {
          for (const listener of [...listeners]) listener(entryId);
        },
        count: () => listeners.size,
      };
    }

    async function outboxKinds() {
      return (await fixture.rowStore.transaction((tx) => tx.outbox.all())).map((row) => [row.kind, row.payload]);
    }

    it('enqueues a deleteEntry for the entry once its object is uploaded, then stops listening', async () => {
      const result = await enqueueUploadSelfTest(depsFor(fixture), PLAINTEXT_BYTES);
      const uploads = uploadsPort();
      let notified = 0;
      scheduleUploadSelfTestCleanup(
        { rowStore: fixture.rowStore, uploads: uploads.port, clock: CLOCK, onEnqueued: () => (notified += 1) },
        result.entryId,
      );
      expect(await outboxKinds()).toEqual([['createEntry', { entryId: result.entryId }]]);
      uploads.fire(fixture.randomId());
      await new Promise((resolve) => setTimeout(resolve, 5));
      expect(await outboxKinds()).toHaveLength(1);
      uploads.fire(result.entryId);
      await new Promise((resolve) => setTimeout(resolve, 5));
      expect(await outboxKinds()).toEqual([
        ['createEntry', { entryId: result.entryId }],
        ['deleteEntry', { entryId: result.entryId }],
      ]);
      expect(notified).toBe(1);
      expect(uploads.count()).toBe(0);
      uploads.fire(result.entryId);
      await new Promise((resolve) => setTimeout(resolve, 5));
      expect(await outboxKinds()).toHaveLength(2);
    });
  });
});
