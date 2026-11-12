import { wipe } from '../crypto/bytes.ts';
import { AD_CONTEXTS } from '../crypto/constants.ts';
import { DecryptionError } from '../crypto/errors.ts';
import { unwrapKey } from '../crypto/sealed-blob.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { WrongPasswordError } from './errors.ts';

export type KeyBundleBytes = {
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly masterKeyByKek: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
};

export type AccountKeys = {
  readonly masterKey: Uint8Array;
  readonly privateKey: Uint8Array;
  readonly publicKey: Uint8Array;
};

export function openKeyBundle(
  sodium: Sodium,
  kek: Uint8Array,
  userId: string,
  bundle: KeyBundleBytes,
): AccountKeys {
  let masterKey: Uint8Array;
  try {
    masterKey = unwrapKey(sodium, kek, bundle.masterKeyByKek, AD_CONTEXTS.masterKeyByKek, userId);
  } catch (error) {
    if (error instanceof DecryptionError) throw new WrongPasswordError();
    throw error;
  }
  try {
    const privateKey = unwrapKey(
      sodium,
      masterKey,
      bundle.privateKeyByMaster,
      AD_CONTEXTS.privateKeyByMaster,
      userId,
    );
    return { masterKey, privateKey, publicKey: bundle.publicKey };
  } catch (error) {
    wipe(masterKey);
    throw error;
  }
}
