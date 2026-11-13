import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  openSharePayload,
  unwrapKey,
  wipe,
  type AccountKeys,
  type Sodium,
} from '@kodakiko/core';
import type { CollectionRow, RowStore } from '../../adapters/rowstore/rowstore';
import { EN } from '../../text/en';

export class AccountLockedError extends Error {
  constructor() {
    super(EN['keys.accountLocked']);
  }
}

export class EntryKeyUnavailableError extends Error {
  constructor() {
    super(EN['keys.unavailable']);
  }
}

export type EntryKeys = {
  readonly collectionKey: (collectionId: string) => Promise<Uint8Array>;
  readonly entryKey: (entryId: string) => Promise<Uint8Array>;
  readonly mediaKey: (entryId: string, mediaId: string) => Promise<Uint8Array>;
  readonly variantKey: (
    entryId: string,
    mediaId: string,
    variant: 'original' | 'thumbnail',
  ) => Promise<Uint8Array>;
  readonly forget: (entryId: string, mediaId?: string) => void;
  readonly clear: () => void;
};

export type EntryKeysDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly account: () => AccountKeys | null;
};

const VARIANT_SUBKEY_IDS = Object.freeze({
  original: KDF_SUBKEY_IDS.mediaOriginal,
  thumbnail: KDF_SUBKEY_IDS.mediaThumbnail,
} as const);

export function createEntryKeys(deps: EntryKeysDeps): EntryKeys {
  const { sodium, rowStore } = deps;
  const collectionKeys = new Map<string, Uint8Array>();
  const entryKeys = new Map<string, Uint8Array>();
  const mediaKeys = new Map<string, Uint8Array>();
  let generation = 0;

  function requireGeneration(started: number, key: Uint8Array | null): void {
    if (started === generation) return;
    if (key !== null) wipe(key);
    throw new AccountLockedError();
  }

  function requireAccount(): AccountKeys {
    const account = deps.account();
    if (account === null) throw new AccountLockedError();
    return account;
  }

  function resolveCollectionKey(account: AccountKeys, collection: CollectionRow): Uint8Array {
    if (collection.kind === 'link') {
      const linkSecret = unwrapKey(
        sodium,
        account.masterKey,
        collection.keyMaterial,
        AD_CONTEXTS.linkSecretByMaster,
        collection.id,
      );
      try {
        return sodium.kdfDerive(
          KEY_BYTES,
          KDF_SUBKEY_IDS.linkCollectionKey,
          KDF_CONTEXTS.link,
          linkSecret,
        );
      } finally {
        wipe(linkSecret);
      }
    }
    if (collection.role === 'viewer') {
      return openSharePayload(
        sodium,
        collection.keyMaterial,
        account.publicKey,
        account.privateKey,
        collection.id,
      );
    }
    return unwrapKey(
      sodium,
      account.masterKey,
      collection.keyMaterial,
      AD_CONTEXTS.collectionKeyByMaster,
      collection.id,
    );
  }

  async function collectionKey(collectionId: string): Promise<Uint8Array> {
    const account = requireAccount();
    const started = generation;
    const cached = collectionKeys.get(collectionId);
    if (cached !== undefined) return cached;
    const collection = await rowStore.transaction((tx) => tx.collections.get(collectionId));
    requireGeneration(started, null);
    if (collection === null) throw new EntryKeyUnavailableError();
    const key = resolveCollectionKey(account, collection);
    collectionKeys.set(collectionId, key);
    return key;
  }

  async function entryKey(entryId: string): Promise<Uint8Array> {
    requireAccount();
    const started = generation;
    const cached = entryKeys.get(entryId);
    if (cached !== undefined) return cached;
    const { memberships, kinds } = await rowStore.transaction(async (tx) => {
      const rows = await tx.memberships.byEntry(entryId);
      const collections = await Promise.all(rows.map((row) => tx.collections.get(row.collectionId)));
      return {
        memberships: rows,
        kinds: new Map(collections.map((collection) => [collection?.id, collection?.kind])),
      };
    });
    const ordered = [...memberships].sort(
      (left, right) =>
        Number(kinds.get(right.collectionId) === 'home') -
        Number(kinds.get(left.collectionId) === 'home'),
    );
    requireGeneration(started, null);
    for (const membership of ordered) {
      try {
        const parent = await collectionKey(membership.collectionId);
        requireGeneration(started, null);
        const key = unwrapKey(
          sodium,
          parent,
          membership.entryKeyWrap,
          AD_CONTEXTS.entryKeyByCollection,
          `${membership.collectionId}/${entryId}`,
        );
        requireGeneration(started, key);
        entryKeys.set(entryId, key);
        return key;
      } catch (error) {
        if (error instanceof AccountLockedError) throw error;
      }
    }
    throw new EntryKeyUnavailableError();
  }

  async function mediaKey(entryId: string, mediaId: string): Promise<Uint8Array> {
    requireAccount();
    const started = generation;
    const cacheKey = `${entryId}/${mediaId}`;
    const cached = mediaKeys.get(cacheKey);
    if (cached !== undefined) return cached;
    const parent = await entryKey(entryId);
    const row = await rowStore.transaction((tx) => tx.media.get(mediaId));
    requireGeneration(started, null);
    if (row === null || row.entryId !== entryId) throw new EntryKeyUnavailableError();
    const key = unwrapKey(sodium, parent, row.mediaKeyWrap, AD_CONTEXTS.mediaKeyByEntry, cacheKey);
    requireGeneration(started, key);
    mediaKeys.set(cacheKey, key);
    return key;
  }

  async function variantKey(
    entryId: string,
    mediaId: string,
    variant: 'original' | 'thumbnail',
  ): Promise<Uint8Array> {
    const parent = await mediaKey(entryId, mediaId);
    return sodium.kdfDerive(KEY_BYTES, VARIANT_SUBKEY_IDS[variant], KDF_CONTEXTS.media, parent);
  }

  function dropMediaKey(cacheKey: string): void {
    const key = mediaKeys.get(cacheKey);
    if (key !== undefined) wipe(key);
    mediaKeys.delete(cacheKey);
  }

  function forget(entryId: string, mediaId?: string): void {
    if (mediaId !== undefined) {
      dropMediaKey(`${entryId}/${mediaId}`);
      return;
    }
    const prefix = `${entryId}/`;
    for (const cacheKey of [...mediaKeys.keys()]) {
      if (cacheKey.startsWith(prefix)) dropMediaKey(cacheKey);
    }
    const key = entryKeys.get(entryId);
    if (key !== undefined) wipe(key);
    entryKeys.delete(entryId);
  }

  function clear(): void {
    generation += 1;
    for (const cache of [collectionKeys, entryKeys, mediaKeys]) {
      for (const key of cache.values()) wipe(key);
      cache.clear();
    }
  }

  return { collectionKey, entryKey, mediaKey, variantKey, forget, clear };
}
