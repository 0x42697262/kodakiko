import { wipe } from '../crypto/bytes.ts';
import { AD_CONTEXTS } from '../crypto/constants.ts';
import { DecryptionError } from '../crypto/errors.ts';
import { unwrapKey } from '../crypto/sealed-blob.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { deriveLoginMaterial, recoveryLoginKey } from './derive.ts';
import { WrongPasswordError } from './errors.ts';
import type { KdfParams } from './kdf-params.ts';
import type { SignUpRequestBytes } from './sign-up.ts';

export type StoredLocalAccount = {
  readonly userId: string;
  readonly kdfParams: KdfParams;
  readonly masterKeyByKek: Uint8Array;
  readonly masterKeyByRecovery: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
  readonly homeCollection: {
    readonly id: string;
    readonly keyByOwner: Uint8Array;
    readonly metadataBlob: Uint8Array;
  };
};

export async function rebuildSignUpRequest(
  sodium: Sodium,
  input: { readonly password: string; readonly username: string; readonly stored: StoredLocalAccount },
): Promise<SignUpRequestBytes> {
  const { stored } = input;
  const login = await deriveLoginMaterial(sodium, input.password, stored.kdfParams);
  let masterKey: Uint8Array;
  try {
    masterKey = unwrapKey(sodium, login.kek, stored.masterKeyByKek, AD_CONTEXTS.masterKeyByKek, stored.userId);
  } catch (error) {
    wipe(login.loginKey);
    if (error instanceof DecryptionError) throw new WrongPasswordError(error.message);
    throw error;
  } finally {
    wipe(login.kek);
  }
  let recoveryKey: Uint8Array | null = null;
  try {
    recoveryKey = unwrapKey(sodium, masterKey, stored.recoveryKeyByMaster, AD_CONTEXTS.recoveryKeyByMaster, stored.userId);
    return {
      userId: stored.userId,
      username: input.username,
      salt: stored.kdfParams.salt,
      opsLimit: stored.kdfParams.opsLimit,
      memLimitBytes: stored.kdfParams.memLimitBytes,
      loginKey: login.loginKey,
      recoveryLoginKey: recoveryLoginKey(sodium, recoveryKey),
      publicKey: stored.publicKey,
      masterKeyByKek: stored.masterKeyByKek,
      masterKeyByRecovery: stored.masterKeyByRecovery,
      recoveryKeyByMaster: stored.recoveryKeyByMaster,
      privateKeyByMaster: stored.privateKeyByMaster,
      homeCollection: stored.homeCollection,
    };
  } finally {
    wipe(masterKey);
    if (recoveryKey !== null) wipe(recoveryKey);
  }
}
