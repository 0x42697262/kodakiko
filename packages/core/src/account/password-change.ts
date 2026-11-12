import { wipe } from '../crypto/bytes.ts';
import { AD_CONTEXTS, SALT_BYTES } from '../crypto/constants.ts';
import { DecryptionError } from '../crypto/errors.ts';
import { unwrapKey, wrapKey } from '../crypto/sealed-blob.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { deriveLoginMaterial, deriveWithAdaptiveParams } from './derive.ts';
import { WrongPasswordError } from './errors.ts';
import { startKdfParams, type KdfParams } from './kdf-params.ts';

export type PasswordChangeBytes = {
  readonly currentLoginKey: Uint8Array;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly loginKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
};

export async function createPasswordChangeMaterial(
  sodium: Sodium,
  input: {
    readonly userId: string;
    readonly currentPassword: string;
    readonly newPassword: string;
    readonly currentParams: KdfParams;
    readonly cachedMasterKeyByKek: Uint8Array;
  },
  startParams: (salt: Uint8Array) => KdfParams = startKdfParams,
): Promise<PasswordChangeBytes> {
  const current = await deriveLoginMaterial(sodium, input.currentPassword, input.currentParams);
  let masterKey: Uint8Array;
  try {
    masterKey = unwrapKey(
      sodium,
      current.kek,
      input.cachedMasterKeyByKek,
      AD_CONTEXTS.masterKeyByKek,
      input.userId,
    );
  } catch (error) {
    if (error instanceof DecryptionError) throw new WrongPasswordError();
    throw error;
  } finally {
    wipe(current.kek);
  }
  try {
    const salt = sodium.randomBytes(SALT_BYTES);
    const { params, material } = await deriveWithAdaptiveParams(
      sodium,
      input.newPassword,
      salt,
      startParams,
    );
    const masterKeyByKek = wrapKey(
      sodium,
      material.kek,
      masterKey,
      AD_CONTEXTS.masterKeyByKek,
      input.userId,
    );
    wipe(material.kek);
    return {
      currentLoginKey: current.loginKey,
      salt,
      opsLimit: params.opsLimit,
      memLimitBytes: params.memLimitBytes,
      loginKey: material.loginKey,
      masterKeyByKek,
    };
  } finally {
    wipe(masterKey);
  }
}
