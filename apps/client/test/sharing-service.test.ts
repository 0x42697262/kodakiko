import { beforeEach, describe, expect, it } from 'vitest';
import {
  AD_CONTEXTS,
  openJson,
  openSharePayload,
  publicKeyFingerprint,
  sealJson,
  unwrapKey,
  type Sodium,
} from '@kodakiko/core';
import { CollectionMetadataV1, decodeBytes, encodeBytes, ROUTES } from '@kodakiko/contract';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { NetworkError } from '../src/adapters/http/http';
import { createCollectionNames } from '../src/services/sharing/collection-names';
import { createSharingService, SharingError, type SharingService } from '../src/services/sharing/sharing-service';
import { apiFailure, createFakeHttp, type FakeHttp } from './support/fake-http';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

const NOW = '2026-06-01T10:00:00.000Z';
const WRAPPED_BYTES = 73;

let sodium: Sodium;
let harness: EntriesHarness;
let fake: FakeHttp;
let service: SharingService;
let sharingEnabled: boolean;
let changed: string[][];

function collectionDtoFor(body: { id: string; keyByOwner: string; metadataBlob: string }) {
  return {
    id: body.id,
    kind: 'shared',
    role: 'owner',
    ownerId: 'U'.repeat(22),
    ownerUsername: 'tester',
    keyMaterial: body.keyByOwner,
    metadataBlob: body.metadataBlob,
    lastSeq: 0,
    createdAt: NOW,
    metaUpdatedAt: NOW,
    recipientCount: 0,
  };
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
  fake = createFakeHttp();
  sharingEnabled = true;
  changed = [];
  fake.on('POST', ROUTES.collections(), (request) => collectionDtoFor(request.body as never));
  service = createSharingService({
    sodium,
    http: fake.http,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    entryKeys: harness.entryKeys,
    account: () => ({ userId: harness.fixture.userId, keys: harness.fixture.keys }),
    sharingEnabled: () => sharingEnabled,
    randomId: harness.fixture.randomId,
    clock: harness.clock,
    afterChange: (entryIds) => changed.push([...entryIds]),
  });
});

async function journal(name = 'Trip'): Promise<string> {
  return service.createJournal(name);
}

async function syncedEntry(fill: number): Promise<string> {
  const entryId = await harness.createEntry([fakeJpeg(1000, fill)]);
  await harness.markSynced(entryId);
  return entryId;
}

describe('sharing service', () => {
  it('createJournal sends a 73-byte key that unwraps to the collection key and a sealed name', async () => {
    const id = await journal('Summer 2026');
    const body = fake.requests.find((request) => request.path === ROUTES.collections())?.body as {
      id: string;
      kind: string;
      keyByOwner: string;
      metadataBlob: string;
    };
    expect(body.id).toBe(id);
    expect(body.kind).toBe('shared');
    const wrapped = decodeBytes(body.keyByOwner as never);
    expect(wrapped.length).toBe(WRAPPED_BYTES);
    const collectionKey = unwrapKey(sodium, harness.fixture.keys.masterKey, wrapped, AD_CONTEXTS.collectionKeyByMaster, id);
    const metadata = openJson(sodium, collectionKey, decodeBytes(body.metadataBlob as never), CollectionMetadataV1, 1, new Map(), AD_CONTEXTS.collectionMetadata, id);
    expect(metadata.name).toBe('Summer 2026');
    const row = await harness.fixture.rowStore.transaction((tx) => tx.collections.get(id));
    expect(row).toMatchObject({ kind: 'shared', role: 'owner', cursorSeq: 0 });
  });

  it('share seals the collection key so the recipient opens it with openSharePayload', async () => {
    const id = await journal();
    const recipient = sodium.boxKeypair();
    fake.on('GET', ROUTES.userPublicKey('bea'), () => ({ userId: 'B'.repeat(22), username: 'bea', publicKey: encodeBytes(recipient.publicKey) }));
    fake.on('POST', ROUTES.collectionShares(id), () => ({}));
    const found = await service.lookupRecipient('Bea');
    expect(found.fingerprint).toBe(publicKeyFingerprint(sodium, recipient.publicKey));
    await service.share(id, found);
    const body = fake.requests.find((request) => request.path === ROUTES.collectionShares(id))?.body as {
      recipientUserId: string;
      sealedCollectionKey: string;
    };
    expect(body.recipientUserId).toBe('B'.repeat(22));
    const opened = openSharePayload(sodium, decodeBytes(body.sealedCollectionKey as never), recipient.publicKey, recipient.privateKey, id);
    expect(opened).toEqual(await harness.entryKeys.collectionKey(id));
  });

  it('share maps 409 ALREADY_SHARED and a missing user to sharing errors', async () => {
    const id = await journal();
    const recipient = sodium.boxKeypair();
    fake.on('GET', ROUTES.userPublicKey('bea'), () => ({ userId: 'B'.repeat(22), username: 'bea', publicKey: encodeBytes(recipient.publicKey) }));
    fake.on('POST', ROUTES.collectionShares(id), () => {
      throw apiFailure(409, 'ALREADY_SHARED');
    });
    const found = await service.lookupRecipient('bea');
    await expect(service.share(id, found)).rejects.toMatchObject({ reason: 'alreadyShared' });
    await expect(service.lookupRecipient('nobody')).rejects.toMatchObject({ reason: 'userNotFound' });
  });

  it('addEntries skips unsynced entries, wraps the entry key with the collection key and reports alreadyIn', async () => {
    const id = await journal();
    const synced = await syncedEntry(1);
    const pending = await harness.createEntry([fakeJpeg(1000, 2)]);
    const conflicted = await syncedEntry(3);
    fake.on('POST', ROUTES.collectionEntries(id), (request) => {
      const body = request.body as { entryId: string };
      if (body.entryId === conflicted) throw apiFailure(409, 'ID_CONFLICT');
      return {};
    });
    const result = await service.addEntries(id, [synced, pending, conflicted, 'missing-entry-id-00000']);
    expect(result).toEqual({ added: [synced], alreadyIn: [conflicted], notSynced: [pending], failed: ['missing-entry-id-00000'] });
    const body = fake.requests.find(
      (request) => request.path === ROUTES.collectionEntries(id) && (request.body as { entryId: string }).entryId === synced,
    )?.body as { entryKeyByCollection: string };
    const entryKey = unwrapKey(
      sodium,
      await harness.entryKeys.collectionKey(id),
      decodeBytes(body.entryKeyByCollection as never),
      AD_CONTEXTS.entryKeyByCollection,
      `${id}/${synced}`,
    );
    expect(entryKey).toEqual(await harness.entryKeys.entryKey(synced));
    const posts = fake.count('POST', ROUTES.collectionEntries(id));
    const again = await service.addEntries(id, [synced]);
    expect(again.alreadyIn).toEqual([synced]);
    expect(fake.count('POST', ROUTES.collectionEntries(id))).toBe(posts);
    expect(changed.flat()).toContain(synced);
  });

  it('removeEntries and deleteJournal drop the local memberships but keep the entries', async () => {
    const id = await journal();
    const entryId = await syncedEntry(4);
    fake.on('POST', ROUTES.collectionEntries(id), () => ({}));
    fake.on('DELETE', ROUTES.collectionEntry(id, entryId), () => undefined);
    fake.on('DELETE', ROUTES.collection(id), () => undefined);
    await service.addEntries(id, [entryId]);
    await service.removeEntries(id, [entryId]);
    const memberships = await harness.fixture.rowStore.transaction((tx) => tx.memberships.byCollection(id));
    expect(memberships).toEqual([]);
    await service.addEntries(id, [entryId]);
    await service.deleteJournal(id);
    await harness.fixture.rowStore.transaction(async (tx) => {
      expect(await tx.collections.get(id)).toBeNull();
      expect(await tx.memberships.byCollection(id)).toEqual([]);
      expect(await tx.entries.get(entryId)).not.toBeNull();
    });
  });

  it('leave drops entries reachable only through the journal and keeps own entries', async () => {
    const id = await journal();
    const onlyShared = await syncedEntry(5);
    const ownToo = await syncedEntry(6);
    const { rowStore } = harness.fixture;
    await rowStore.transaction(async (tx) => {
      const collection = await tx.collections.get(id);
      if (collection === null) throw new Error('missing');
      await tx.collections.put({ ...collection, role: 'viewer', ownerId: 'O'.repeat(22) });
      for (const entryId of [onlyShared, ownToo]) {
        await tx.memberships.put({ collectionId: id, entryId, entryKeyWrap: Uint8Array.of(1), seq: 1 });
      }
      await tx.memberships.delete([harness.fixture.homeCollectionId, onlyShared]);
    });
    fake.on('DELETE', ROUTES.collectionShare(id, harness.fixture.userId), () => undefined);
    await service.leave(id);
    await rowStore.transaction(async (tx) => {
      expect(await tx.collections.get(id)).toBeNull();
      expect(await tx.entries.get(onlyShared)).toBeNull();
      expect(await tx.entries.get(ownToo)).not.toBeNull();
    });
  });

  it('surfaces offline and disabled states', async () => {
    fake.on('POST', ROUTES.collections(), () => {
      throw new NetworkError();
    });
    await expect(journal()).rejects.toMatchObject({ reason: 'offline' });
    sharingEnabled = false;
    await expect(journal()).rejects.toBeInstanceOf(SharingError);
    await expect(journal()).rejects.toMatchObject({ reason: 'disabled' });
  });

  it('myFingerprint is the fingerprint of the account public key', () => {
    expect(service.myFingerprint()).toBe(publicKeyFingerprint(sodium, harness.fixture.keys.publicKey));
  });
});

describe('collection names', () => {
  it('opens the name, falls back for unreadable metadata, and forgets on clear', async () => {
    const id = await journal('Trip');
    const names = createCollectionNames({ sodium, rowStore: harness.fixture.rowStore, entryKeys: harness.entryKeys });
    expect(await names.get(id)).toBe('Trip');
    const key = await harness.entryKeys.collectionKey(id);
    const metadata = { schemaVersion: 1, name: 'Renamed', createdAtUtc: NOW };
    const renamed = sealJson(sodium, key, metadata, AD_CONTEXTS.collectionMetadata, id);
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.collections.get(id);
      if (row !== null) await tx.collections.put({ ...row, metadataBlob: renamed });
    });
    expect(await names.get(id)).toBe('Trip');
    names.clear();
    expect(await names.get(id)).toBe('Renamed');
    await harness.fixture.rowStore.transaction(async (tx) => {
      const row = await tx.collections.get(id);
      if (row !== null) await tx.collections.put({ ...row, metadataBlob: Uint8Array.of(1, 2, 3) });
    });
    names.clear();
    expect(await names.get(id)).toBe('Untitled journal');
  });
});
