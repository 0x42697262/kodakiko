import {
  AD_CONTEXTS,
  buildSharePayload,
  normalizeUsername,
  publicKeyFingerprint,
  sealJson,
  wrapKey,
  type AccountKeys,
  type Sodium,
} from '@kodakiko/core';
import { decodeBytes, encodeBytes, type ShareDto } from '@kodakiko/contract';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import { ApiError, NetworkError, type HttpClient } from '../../adapters/http/http';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { EntryKeys } from '../keys/entry-keys';
import { dropLocalCollection } from '../sync/local-delete';
import { SHARING_CALLS } from './sharing-calls';

const KEY_BYTES = 32;
const COLLECTION_METADATA_VERSION = 1;
const HTTP_NOT_FOUND = 404;
const HTTP_CONFLICT = 409;

export type SharingErrorReason = 'offline' | 'disabled' | 'locked' | 'userNotFound' | 'alreadyShared' | 'notOwner';

export class SharingError extends Error {
  readonly reason: SharingErrorReason;
  constructor(reason: SharingErrorReason) {
    super(reason);
    this.reason = reason;
  }
}

export type Recipient = {
  readonly userId: string;
  readonly username: string;
  readonly publicKey: Uint8Array;
  readonly fingerprint: string;
};

export type AddEntriesResult = {
  readonly added: readonly string[];
  readonly alreadyIn: readonly string[];
  readonly notSynced: readonly string[];
  readonly failed: readonly string[];
};

export type SharingService = {
  readonly createJournal: (name: string) => Promise<string>;
  readonly renameJournal: (id: string, name: string) => Promise<void>;
  readonly deleteJournal: (id: string) => Promise<void>;
  readonly addEntries: (collectionId: string, entryIds: readonly string[]) => Promise<AddEntriesResult>;
  readonly removeEntries: (collectionId: string, entryIds: readonly string[]) => Promise<void>;
  readonly lookupRecipient: (username: string) => Promise<Recipient>;
  readonly share: (collectionId: string, recipient: Recipient) => Promise<void>;
  readonly listShares: (collectionId: string) => Promise<readonly ShareDto[]>;
  readonly unshare: (collectionId: string, recipientUserId: string) => Promise<void>;
  readonly leave: (collectionId: string) => Promise<void>;
  readonly myFingerprint: () => string;
};

export type SharingServiceDeps = {
  readonly sodium: Sodium;
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly entryKeys: Pick<EntryKeys, 'collectionKey' | 'entryKey'>;
  readonly account: () => { readonly userId: string; readonly keys: AccountKeys } | null;
  readonly sharingEnabled: () => boolean;
  readonly randomId: () => string;
  readonly clock: Clock;
  readonly afterChange: (entryIds: readonly string[]) => void;
};

function isStatus(error: unknown, status: number): boolean {
  return error instanceof ApiError && error.status === status;
}

export function createSharingService(deps: SharingServiceDeps): SharingService {
  const { sodium, http, rowStore } = deps;

  function requireAccount() {
    if (!deps.sharingEnabled()) throw new SharingError('disabled');
    const account = deps.account();
    if (account === null) throw new SharingError('locked');
    return account;
  }

  async function online<T>(work: () => Promise<T>): Promise<T> {
    try {
      return await work();
    } catch (error) {
      if (error instanceof NetworkError) throw new SharingError('offline');
      throw error;
    }
  }

  function sealName(key: Uint8Array, id: string, name: string, createdAtUtc: string): Uint8Array {
    const metadata = { schemaVersion: COLLECTION_METADATA_VERSION, name, createdAtUtc };
    return sealJson(sodium, key, metadata, AD_CONTEXTS.collectionMetadata, id);
  }

  async function createJournal(name: string): Promise<string> {
    const account = requireAccount();
    const id = deps.randomId();
    const collectionKey = sodium.randomBytes(KEY_BYTES);
    const keyByOwner = wrapKey(sodium, account.keys.masterKey, collectionKey, AD_CONTEXTS.collectionKeyByMaster, id);
    const metadataBlob = sealName(collectionKey, id, name, deps.clock.nowIso());
    const dto = await online(() =>
      http.request(
        SHARING_CALLS.create({
          id: id as never,
          kind: 'shared',
          keyByOwner: encodeBytes(keyByOwner),
          metadataBlob: encodeBytes(metadataBlob),
        }),
      ),
    );
    await rowStore.transaction((tx) =>
      tx.collections.put({
        id,
        kind: 'shared',
        ownerId: dto.ownerId,
        ownerUsername: dto.ownerUsername,
        role: 'owner',
        keyMaterial: keyByOwner,
        metadataBlob,
        metaUpdatedAt: dto.metaUpdatedAt,
        serverLastSeq: dto.lastSeq,
        cursorSeq: 0,
      }),
    );
    deps.afterChange([]);
    return id;
  }

  async function ownedRow(id: string) {
    const row = await rowStore.transaction((tx) => tx.collections.get(id));
    if (row === null || row.role !== 'owner') throw new SharingError('notOwner');
    return row;
  }

  async function renameJournal(id: string, name: string): Promise<void> {
    requireAccount();
    await ownedRow(id);
    const key = await deps.entryKeys.collectionKey(id);
    const metadataBlob = sealName(key, id, name, deps.clock.nowIso());
    const dto = await online(() => http.request(SHARING_CALLS.rename(id, encodeBytes(metadataBlob))));
    await rowStore.transaction(async (tx) => {
      const row = await tx.collections.get(id);
      if (row !== null) await tx.collections.put({ ...row, metadataBlob, metaUpdatedAt: dto.metaUpdatedAt });
    });
    deps.afterChange([]);
  }

  async function deleteJournal(id: string): Promise<void> {
    requireAccount();
    await ownedRow(id);
    await online(() => http.request(SHARING_CALLS.remove(id)));
    const touched = await rowStore.transaction(async (tx) => {
      const memberships = await tx.memberships.byCollection(id);
      for (const membership of memberships) await tx.memberships.delete([id, membership.entryId]);
      await tx.collections.delete(id);
      return memberships.map((membership) => membership.entryId);
    });
    deps.afterChange(touched);
  }

  async function addOne(collectionId: string, collectionKey: Uint8Array, entryId: string, userId: string) {
    const state = await rowStore.transaction(async (tx) => {
      const entry = await tx.entries.get(entryId);
      const membership = (await tx.memberships.byEntry(entryId)).find((row) => row.collectionId === collectionId);
      return { entry, membership };
    });
    if (state.entry === null || state.entry.ownerId !== userId) return 'failed';
    if (state.membership !== undefined) return 'alreadyIn';
    if ((state.entry.baseRevision ?? 0) === 0) return 'notSynced';
    const entryKey = await deps.entryKeys.entryKey(entryId);
    const wrap = wrapKey(sodium, collectionKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${collectionId}/${entryId}`);
    try {
      await online(() => http.request(SHARING_CALLS.addEntry(collectionId, entryId, encodeBytes(wrap))));
    } catch (error) {
      if (isStatus(error, HTTP_CONFLICT)) return 'alreadyIn';
      if (isStatus(error, HTTP_NOT_FOUND)) return 'failed';
      throw error;
    }
    await rowStore.transaction((tx) => tx.memberships.put({ collectionId, entryId, entryKeyWrap: wrap, seq: 0 }));
    return 'added';
  }

  async function addEntries(collectionId: string, entryIds: readonly string[]): Promise<AddEntriesResult> {
    const account = requireAccount();
    await ownedRow(collectionId);
    const collectionKey = await deps.entryKeys.collectionKey(collectionId);
    const result = { added: [] as string[], alreadyIn: [] as string[], notSynced: [] as string[], failed: [] as string[] };
    for (const entryId of entryIds) {
      result[await addOne(collectionId, collectionKey, entryId, account.userId)].push(entryId);
    }
    if (result.added.length > 0) deps.afterChange(result.added);
    return result;
  }

  async function removeEntries(collectionId: string, entryIds: readonly string[]): Promise<void> {
    requireAccount();
    await ownedRow(collectionId);
    for (const entryId of entryIds) {
      await online(() => http.request(SHARING_CALLS.removeEntry(collectionId, entryId)));
      await rowStore.transaction((tx) => tx.memberships.delete([collectionId, entryId]));
    }
    deps.afterChange(entryIds);
  }

  async function lookupRecipient(username: string): Promise<Recipient> {
    requireAccount();
    try {
      const found = await online(() => http.request(SHARING_CALLS.publicKey(normalizeUsername(username))));
      const publicKey = decodeBytes(found.publicKey);
      return {
        userId: found.userId,
        username: found.username,
        publicKey,
        fingerprint: publicKeyFingerprint(sodium, publicKey),
      };
    } catch (error) {
      if (isStatus(error, HTTP_NOT_FOUND)) throw new SharingError('userNotFound');
      throw error;
    }
  }

  async function share(collectionId: string, recipient: Recipient): Promise<void> {
    requireAccount();
    await ownedRow(collectionId);
    const collectionKey = await deps.entryKeys.collectionKey(collectionId);
    const sealed = sodium.boxSeal(buildSharePayload(collectionId, collectionKey), recipient.publicKey);
    try {
      await online(() => http.request(SHARING_CALLS.share(collectionId, recipient.userId, encodeBytes(sealed))));
    } catch (error) {
      if (isStatus(error, HTTP_CONFLICT)) throw new SharingError('alreadyShared');
      throw error;
    }
    deps.afterChange([]);
  }

  async function listShares(collectionId: string): Promise<readonly ShareDto[]> {
    requireAccount();
    return (await online(() => http.request(SHARING_CALLS.listShares(collectionId)))).items;
  }

  async function unshare(collectionId: string, recipientUserId: string): Promise<void> {
    requireAccount();
    await online(() => http.request(SHARING_CALLS.unshare(collectionId, recipientUserId)));
    deps.afterChange([]);
  }

  async function leave(collectionId: string): Promise<void> {
    const account = requireAccount();
    await online(() => http.request(SHARING_CALLS.unshare(collectionId, account.userId)));
    deps.afterChange(await dropLocalCollection(rowStore, deps.fileStore, collectionId));
  }

  return {
    createJournal,
    renameJournal,
    deleteJournal,
    addEntries,
    removeEntries,
    lookupRecipient,
    share,
    listShares,
    unshare,
    leave,
    myFingerprint: () => {
      const account = deps.account();
      return account === null ? '' : publicKeyFingerprint(sodium, account.keys.publicKey);
    },
  };
}
