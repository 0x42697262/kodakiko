import { wipe } from '../crypto/bytes.ts';
import { AD_CONTEXTS, SALT_BYTES } from '../crypto/constants.ts';
import { DecryptionError } from '../crypto/errors.ts';
import { decodeRecoveryWords } from '../crypto/recovery-words.ts';
import { unwrapKey, wrapKey } from '../crypto/sealed-blob.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { deriveWithAdaptiveParams, recoveryLoginKey } from './derive.ts';
import { WrongRecoveryWordsError } from './errors.ts';
import { startKdfParams, type KdfParams } from './kdf-params.ts';

export type RecoveryCompleteBytes = {
  readonly recoveryLoginKey: Uint8Array;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly loginKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
};

export function openRecovery(
  sodium: Sodium,
  words: readonly string[],
  userId: string,
  masterKeyByRecovery: Uint8Array,
): { readonly masterKey: Uint8Array; readonly recoveryKey: Uint8Array } {
  const recoveryKey = decodeRecoveryWords(sodium, words);
  try {
    const masterKey = unwrapKey(
      sodium,
      recoveryKey,
      masterKeyByRecovery,
      AD_CONTEXTS.masterKeyByRecovery,
      userId,
    );
    return { masterKey, recoveryKey };
  } catch (error) {
    wipe(recoveryKey);
    if (error instanceof DecryptionError) throw new WrongRecoveryWordsError();
    throw error;
  }
}

export async function createRecoveryCompletion(
  sodium: Sodium,
  input: {
    readonly userId: string;
    readonly newPassword: string;
    readonly masterKey: Uint8Array;
    readonly recoveryKey: Uint8Array;
  },
  startParams: (salt: Uint8Array) => KdfParams = startKdfParams,
): Promise<RecoveryCompleteBytes> {
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
    input.masterKey,
    AD_CONTEXTS.masterKeyByKek,
    input.userId,
  );
  wipe(material.kek);
  return {
    recoveryLoginKey: recoveryLoginKey(sodium, input.recoveryKey),
    salt,
    opsLimit: params.opsLimit,
    memLimitBytes: params.memLimitBytes,
    loginKey: material.loginKey,
    masterKeyByKek,
  };
}
