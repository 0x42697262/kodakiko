import { beforeEach, describe, expect, it } from 'vitest';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  buildSharePayload,
  wrapKey,
  type AccountKeys,
  type Sodium,
} from '@kodakiko/core';
import {
  AccountLockedError,
  EntryKeyUnavailableError,
  createEntryKeys,
} from '../src/services/keys/entry-keys';
import {
  createAccountFixture,
  createLocalEntry,
  createMemoryFileStore,
  createMemoryRowStore,
  createNodeSodium,
  type AccountFixture,
} from './support';

let sodium: Sodium;
let fixture: AccountFixture;
let account: AccountKeys | null;

beforeEach(async () => {
  sodium = await createNodeSodium();
  fixture = await createAccountFixture(sodium, createMemoryRowStore(), createMemoryFileStore());
  account = fixture.keys;
});

function keysUnderTest() {
  return createEntryKeys({ sodium, rowStore: fixture.rowStore, account: () => account });
}

describe('entry keys', () => {
  it('resolves the home collection key for the owner', async () => {
    const keys = keysUnderTest();
    expect([...(await keys.collectionKey(fixture.homeCollectionId))]).toEqual([
      ...fixture.homeCollectionKey,
    ]);
  });

  it('resolves entry, media and variant keys through the membership', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1, 2, 3)], note: '', tags: [] });
    const keys = keysUnderTest();
    expect([...(await keys.entryKey(local.entryId))]).toEqual([...local.entryKey]);
    const mediaId = local.mediaIds[0] ?? '';
    const mediaKey = await keys.mediaKey(local.entryId, mediaId);
    expect(mediaKey).toHaveLength(KEY_BYTES);
    const original = await keys.variantKey(local.entryId, mediaId, 'original');
    const thumbnail = await keys.variantKey(local.entryId, mediaId, 'thumbnail');
    expect([...original]).toEqual([
      ...sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaOriginal, KDF_CONTEXTS.media, mediaKey),
    ]);
    expect([...thumbnail]).toEqual([
      ...sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey),
    ]);
  });

  it('does not cache variant keys, so wiping one does not break the next call', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const keys = keysUnderTest();
    const mediaId = local.mediaIds[0] ?? '';
    const first = await keys.variantKey(local.entryId, mediaId, 'original');
    first.fill(0);
    const second = await keys.variantKey(local.entryId, mediaId, 'original');
    expect(second.some((byte) => byte !== 0)).toBe(true);
  });

  it('caches collection, entry and media keys', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const keys = keysUnderTest();
    const mediaId = local.mediaIds[0] ?? '';
    expect(await keys.entryKey(local.entryId)).toBe(await keys.entryKey(local.entryId));
    expect(await keys.mediaKey(local.entryId, mediaId)).toBe(await keys.mediaKey(local.entryId, mediaId));
    expect(await keys.collectionKey(fixture.homeCollectionId)).toBe(
      await keys.collectionKey(fixture.homeCollectionId),
    );
  });

  it('throws AccountLockedError while the account is locked', async () => {
    account = null;
    const keys = keysUnderTest();
    await expect(keys.collectionKey(fixture.homeCollectionId)).rejects.toBeInstanceOf(AccountLockedError);
    await expect(keys.entryKey(fixture.randomId())).rejects.toBeInstanceOf(AccountLockedError);
  });

  it('wipes every cached key on clear and re-derives afterwards', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const keys = keysUnderTest();
    const mediaId = local.mediaIds[0] ?? '';
    const collectionKey = await keys.collectionKey(fixture.homeCollectionId);
    const entryKey = await keys.entryKey(local.entryId);
    const mediaKey = await keys.mediaKey(local.entryId, mediaId);
    keys.clear();
    for (const key of [collectionKey, entryKey, mediaKey]) expect(key.every((byte) => byte === 0)).toBe(true);
    expect([...(await keys.entryKey(local.entryId))]).toEqual([...local.entryKey]);
  });

  it('forget(entryId, mediaId) wipes and drops only that media key', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1), Uint8Array.of(2)], note: '', tags: [] });
    const keys = keysUnderTest();
    const [first = '', second = ''] = local.mediaIds;
    const entryKey = await keys.entryKey(local.entryId);
    const firstKey = await keys.mediaKey(local.entryId, first);
    const secondKey = await keys.mediaKey(local.entryId, second);
    const entryCopy = entryKey.slice();
    keys.forget(local.entryId, first);
    expect(firstKey.every((byte) => byte === 0)).toBe(true);
    expect(secondKey.some((byte) => byte !== 0)).toBe(true);
    expect([...entryKey]).toEqual([...entryCopy]);
    expect(await keys.mediaKey(local.entryId, first)).not.toBe(firstKey);
  });

  it('forget(entryId) wipes and drops the entry key and all its media keys', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const other = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(2)], note: '', tags: [] });
    const keys = keysUnderTest();
    const entryKey = await keys.entryKey(local.entryId);
    const mediaKey = await keys.mediaKey(local.entryId, local.mediaIds[0] ?? '');
    const otherKey = await keys.entryKey(other.entryId);
    keys.forget(local.entryId);
    expect(entryKey.every((byte) => byte === 0)).toBe(true);
    expect(mediaKey.every((byte) => byte === 0)).toBe(true);
    expect(otherKey.some((byte) => byte !== 0)).toBe(true);
    const again = await keys.entryKey(local.entryId);
    expect(again).not.toBe(entryKey);
    expect([...again]).toEqual([...local.entryKey]);
  });

  it('throws when the entry has no usable membership', async () => {
    const keys = keysUnderTest();
    await expect(keys.entryKey(fixture.randomId())).rejects.toBeInstanceOf(EntryKeyUnavailableError);
  });

  it('throws when the collection row is unknown', async () => {
    const keys = keysUnderTest();
    await expect(keys.collectionKey(fixture.randomId())).rejects.toBeInstanceOf(EntryKeyUnavailableError);
  });

  it('opens a shared collection key from the sealed share payload for a viewer', async () => {
    const sharedId = fixture.randomId();
    const sharedKey = sodium.randomBytes(KEY_BYTES);
    const sealed = sodium.boxSeal(buildSharePayload(sharedId, sharedKey), fixture.keys.publicKey);
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({
        id: sharedId,
        kind: 'shared',
        ownerId: 'other',
        ownerUsername: 'other',
        role: 'viewer',
        keyMaterial: sealed,
        metadataBlob: Uint8Array.of(1),
        metaUpdatedAt: '2026-11-12T10:00:00.000Z',
        serverLastSeq: 0,
        cursorSeq: 0,
      }),
    );
    const keys = keysUnderTest();
    expect([...(await keys.collectionKey(sharedId))]).toEqual([...sharedKey]);
  });

  it('derives a link collection key from the unwrapped link secret', async () => {
    const linkId = fixture.randomId();
    const linkSecret = sodium.randomBytes(KEY_BYTES);
    await fixture.rowStore.transaction((tx) =>
      tx.collections.put({
        id: linkId,
        kind: 'link',
        ownerId: 'user',
        ownerUsername: 'tester',
        role: 'owner',
        keyMaterial: wrapKey(sodium, fixture.keys.masterKey, linkSecret, AD_CONTEXTS.linkSecretByMaster, linkId),
        metadataBlob: Uint8Array.of(1),
        metaUpdatedAt: '2026-11-12T10:00:00.000Z',
        serverLastSeq: 0,
        cursorSeq: 0,
      }),
    );
    const keys = keysUnderTest();
    expect([...(await keys.collectionKey(linkId))]).toEqual([
      ...sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.linkCollectionKey, KDF_CONTEXTS.link, linkSecret),
    ]);
  });

  it('prefers the home membership and skips collections whose key cannot be resolved', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const brokenId = fixture.randomId();
    await fixture.rowStore.transaction(async (tx) => {
      await tx.collections.put({
        id: brokenId,
        kind: 'shared',
        ownerId: 'other',
        ownerUsername: 'other',
        role: 'viewer',
        keyMaterial: Uint8Array.of(9, 9, 9),
        metadataBlob: Uint8Array.of(1),
        metaUpdatedAt: '2026-11-12T10:00:00.000Z',
        serverLastSeq: 0,
        cursorSeq: 0,
      });
      await tx.memberships.put({
        collectionId: brokenId,
        entryId: local.entryId,
        entryKeyWrap: Uint8Array.of(1, 2, 3),
        seq: 1,
      });
    });
    const keys = keysUnderTest();
    expect([...(await keys.entryKey(local.entryId))]).toEqual([...local.entryKey]);
  });

  it('throws AccountLockedError for a cached media key once the account is locked', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    const keys = keysUnderTest();
    const mediaId = local.mediaIds[0] ?? '';
    await keys.mediaKey(local.entryId, mediaId);
    account = null;
    await expect(keys.mediaKey(local.entryId, mediaId)).rejects.toBeInstanceOf(AccountLockedError);
  });

  it('wipes and rejects a resolution that finishes after clear()', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const slowStore = {
      ...fixture.rowStore,
      transaction: async <T,>(work: Parameters<typeof fixture.rowStore.transaction<T>>[0]) => {
        const result = await fixture.rowStore.transaction(work);
        await gate;
        return result;
      },
    };
    const keys = createEntryKeys({ sodium, rowStore: slowStore, account: () => fixture.keys });
    const pending = keys.collectionKey(fixture.homeCollectionId);
    pending.catch(() => undefined);
    keys.clear();
    release();
    await expect(pending).rejects.toBeInstanceOf(AccountLockedError);
    const fresh = keys.entryKey(local.entryId);
    await expect(fresh).resolves.toHaveLength(KEY_BYTES);
  });

  it('does not cache an entry key that was resolved in an older generation', async () => {
    const local = await createLocalEntry(fixture, { mediaBytes: [Uint8Array.of(1)], note: '', tags: [] });
    let release: () => void = () => undefined;
    let held = false;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const slowStore = {
      ...fixture.rowStore,
      transaction: async <T,>(work: Parameters<typeof fixture.rowStore.transaction<T>>[0]) => {
        const result = await fixture.rowStore.transaction(work);
        if (!held) {
          held = true;
          await gate;
        }
        return result;
      },
    };
    const keys = createEntryKeys({ sodium, rowStore: slowStore, account: () => fixture.keys });
    const pending = keys.entryKey(local.entryId);
    pending.catch(() => undefined);
    keys.clear();
    release();
    await expect(pending).rejects.toBeInstanceOf(AccountLockedError);
    const again = await keys.entryKey(local.entryId);
    expect([...again]).toEqual([...local.entryKey]);
    expect(again.some((byte) => byte !== 0)).toBe(true);
  });
});
