import { z } from 'zod';
import { decodeBytes, encodeBytes, idSchema, bytesSchema } from '@kodakiko/contract';
import type { KeyBundleBytes } from '@kodakiko/core';
import type { RowStore } from '../adapters/rowstore/rowstore';
import { STORAGE_KEYS } from '../keys/storage-keys';

const kdfParamsSchema = z.object({
  salt: bytesSchema,
  opsLimit: z.number().int(),
  memLimitBytes: z.number().int(),
});

export type StoredAccount = {
  readonly userId: string;
  readonly username: string;
  readonly bundle: KeyBundleBytes;
  readonly recoveryConfirmed: boolean;
  readonly deletionDueAt: string | null;
};

export type AccountData = {
  readonly userId: string;
  readonly username: string;
  readonly bundle: KeyBundleBytes;
  readonly recoveryConfirmed: boolean | null;
  readonly homeCollectionId: string | null;
  readonly deletionDueAt: string | null;
  readonly role?: string;
};

export async function saveKeyBundle(rowStore: RowStore, bundle: KeyBundleBytes): Promise<void> {
  await rowStore.kvSetJson(STORAGE_KEYS.accountKdfParams, {
    salt: encodeBytes(bundle.salt),
    opsLimit: bundle.opsLimit,
    memLimitBytes: bundle.memLimitBytes,
  });
  await rowStore.kvSet(STORAGE_KEYS.accountMasterKeyByKek, bundle.masterKeyByKek);
  await rowStore.kvSet(STORAGE_KEYS.accountRecoveryKeyByMaster, bundle.recoveryKeyByMaster);
  await rowStore.kvSet(STORAGE_KEYS.accountPublicKey, bundle.publicKey);
  await rowStore.kvSet(STORAGE_KEYS.accountPrivateKeyByMaster, bundle.privateKeyByMaster);
}

export async function saveAccountData(rowStore: RowStore, data: AccountData): Promise<void> {
  await rowStore.kvSetJson(STORAGE_KEYS.accountUserId, data.userId);
  await rowStore.kvSetJson(STORAGE_KEYS.accountUsername, data.username);
  await saveKeyBundle(rowStore, data.bundle);
  if (data.recoveryConfirmed !== null) {
    await rowStore.kvSetJson(STORAGE_KEYS.accountRecoveryConfirmed, data.recoveryConfirmed);
  }
  if (data.homeCollectionId !== null) {
    await rowStore.kvSetJson(STORAGE_KEYS.accountHomeCollectionId, data.homeCollectionId);
  }
  await saveDeletionDueAt(rowStore, data.deletionDueAt);
  if (data.role !== undefined) await saveRole(rowStore, data.role);
}

export async function saveDeletionDueAt(
  rowStore: RowStore,
  deletionDueAt: string | null,
): Promise<void> {
  if (deletionDueAt === null) await rowStore.kvDelete(STORAGE_KEYS.accountDeletionDueAt);
  else await rowStore.kvSetJson(STORAGE_KEYS.accountDeletionDueAt, deletionDueAt);
}

export async function loadAccount(rowStore: RowStore): Promise<StoredAccount | null> {
  const userId = await rowStore.kvGetJson(STORAGE_KEYS.accountUserId, idSchema);
  const username = await rowStore.kvGetJson(STORAGE_KEYS.accountUsername, z.string());
  const kdfParams = await rowStore.kvGetJson(STORAGE_KEYS.accountKdfParams, kdfParamsSchema);
  const masterKeyByKek = await rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek);
  const recoveryKeyByMaster = await rowStore.kvGet(STORAGE_KEYS.accountRecoveryKeyByMaster);
  const publicKey = await rowStore.kvGet(STORAGE_KEYS.accountPublicKey);
  const privateKeyByMaster = await rowStore.kvGet(STORAGE_KEYS.accountPrivateKeyByMaster);
  if (
    userId === null ||
    username === null ||
    kdfParams === null ||
    masterKeyByKek === null ||
    recoveryKeyByMaster === null ||
    publicKey === null ||
    privateKeyByMaster === null
  ) {
    return null;
  }
  const recoveryConfirmed = await rowStore.kvGetJson(
    STORAGE_KEYS.accountRecoveryConfirmed,
    z.boolean(),
  );
  const deletionDueAt = await rowStore.kvGetJson(STORAGE_KEYS.accountDeletionDueAt, z.string());
  return {
    userId,
    username,
    bundle: {
      salt: decodeBytes(kdfParams.salt),
      opsLimit: kdfParams.opsLimit,
      memLimitBytes: kdfParams.memLimitBytes,
      masterKeyByKek,
      recoveryKeyByMaster,
      publicKey,
      privateKeyByMaster,
    },
    recoveryConfirmed: recoveryConfirmed ?? true,
    deletionDueAt,
  };
}

export async function saveRole(rowStore: RowStore, role: string): Promise<void> {
  await rowStore.kvSetJson(STORAGE_KEYS.accountRole, role);
}
