import { CollectionMetadataV1 } from '@kodakiko/contract';
import { wipe } from '../crypto/bytes.ts';
import { AD_CONTEXTS, KEY_BYTES, SALT_BYTES } from '../crypto/constants.ts';
import { encodeRecoveryKey } from '../crypto/recovery-words.ts';
import { sealJson, wrapKey } from '../crypto/sealed-blob.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { deriveWithAdaptiveParams, recoveryLoginKey } from './derive.ts';
import { startKdfParams, type KdfParams } from './kdf-params.ts';

export type SignUpInput = {
  readonly username: string;
  readonly password: string;
  readonly homeCollectionName: string;
  readonly createdAtUtc: string;
  readonly randomId: () => string;
};

export type SignUpRequestBytes = {
  readonly userId: string;
  readonly username: string;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly loginKey: Uint8Array;
  readonly recoveryLoginKey: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
  readonly masterKeyByRecovery: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
  readonly homeCollection: {
    readonly id: string;
    readonly keyByOwner: Uint8Array;
    readonly metadataBlob: Uint8Array;
  };
};

export type AccountSecrets = {
  readonly userId: string;
  readonly masterKey: Uint8Array;
  readonly privateKey: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly homeCollectionId: string;
  readonly homeCollectionKey: Uint8Array;
};

export type SignUpMaterial = {
  readonly request: SignUpRequestBytes;
  readonly secrets: AccountSecrets;
  readonly recoveryWords: readonly string[];
};

export async function createSignUpMaterial(
  sodium: Sodium,
  input: SignUpInput,
  startParams: (salt: Uint8Array) => KdfParams = startKdfParams,
): Promise<SignUpMaterial> {
  const salt = sodium.randomBytes(SALT_BYTES);
  const { params, material } = await deriveWithAdaptiveParams(
    sodium,
    input.password,
    salt,
    startParams,
  );
  const masterKey = sodium.randomBytes(KEY_BYTES);
  const recoveryKey = sodium.randomBytes(KEY_BYTES);
  const homeCollectionKey = sodium.randomBytes(KEY_BYTES);
  const keypair = sodium.boxKeypair();
  const userId = input.randomId();
  const homeCollectionId = input.randomId();

  const metadata = CollectionMetadataV1.parse({
    schemaVersion: 1,
    name: input.homeCollectionName,
    createdAtUtc: input.createdAtUtc,
  });
  const request: SignUpRequestBytes = {
    userId,
    username: input.username,
    salt,
    opsLimit: params.opsLimit,
    memLimitBytes: params.memLimitBytes,
    loginKey: material.loginKey,
    recoveryLoginKey: recoveryLoginKey(sodium, recoveryKey),
    publicKey: keypair.publicKey,
    masterKeyByKek: wrapKey(sodium, material.kek, masterKey, AD_CONTEXTS.masterKeyByKek, userId),
    masterKeyByRecovery: wrapKey(
      sodium,
      recoveryKey,
      masterKey,
      AD_CONTEXTS.masterKeyByRecovery,
      userId,
    ),
    recoveryKeyByMaster: wrapKey(
      sodium,
      masterKey,
      recoveryKey,
      AD_CONTEXTS.recoveryKeyByMaster,
      userId,
    ),
    privateKeyByMaster: wrapKey(
      sodium,
      masterKey,
      keypair.privateKey,
      AD_CONTEXTS.privateKeyByMaster,
      userId,
    ),
    homeCollection: {
      id: homeCollectionId,
      keyByOwner: wrapKey(
        sodium,
        masterKey,
        homeCollectionKey,
        AD_CONTEXTS.collectionKeyByMaster,
        homeCollectionId,
      ),
      metadataBlob: sealJson(
        sodium,
        homeCollectionKey,
        metadata,
        AD_CONTEXTS.collectionMetadata,
        homeCollectionId,
      ),
    },
  };
  const recoveryWords = encodeRecoveryKey(sodium, recoveryKey);
  wipe(material.kek);
  wipe(recoveryKey);
  return {
    request,
    secrets: {
      userId,
      masterKey,
      privateKey: keypair.privateKey,
      publicKey: keypair.publicKey,
      homeCollectionId,
      homeCollectionKey,
    },
    recoveryWords,
  };
}
