import { wipe } from '../crypto/bytes.ts';
import { KDF_CONTEXTS, KDF_SUBKEY_IDS, KEY_BYTES } from '../crypto/constants.ts';
import { passwordToBytes } from '../crypto/password.ts';
import type { Sodium } from '../crypto/sodium.ts';
import { KEK_BYTES } from './constants.ts';
import { KdfUnavailableError } from './errors.ts';
import { nextKdfParamsAfterFailure, startKdfParams, type KdfParams } from './kdf-params.ts';

export type LoginMaterial = { readonly kek: Uint8Array; readonly loginKey: Uint8Array };

async function stretchPassword(
  sodium: Sodium,
  password: string,
  params: KdfParams,
): Promise<Uint8Array> {
  const passwordBytes = passwordToBytes(password);
  try {
    return await sodium.argon2id({
      password: passwordBytes,
      salt: params.salt,
      opsLimit: params.opsLimit,
      memLimitBytes: params.memLimitBytes,
      outputLength: KEK_BYTES,
    });
  } finally {
    wipe(passwordBytes);
  }
}

function materialFromKek(sodium: Sodium, kek: Uint8Array): LoginMaterial {
  const loginKey = sodium.kdfDerive(KEY_BYTES, KDF_SUBKEY_IDS.login, KDF_CONTEXTS.login, kek);
  return { kek, loginKey };
}

export async function deriveLoginMaterial(
  sodium: Sodium,
  password: string,
  params: KdfParams,
): Promise<LoginMaterial> {
  return materialFromKek(sodium, await stretchPassword(sodium, password, params));
}

export async function deriveWithAdaptiveParams(
  sodium: Sodium,
  password: string,
  salt: Uint8Array,
  startParams: (salt: Uint8Array) => KdfParams = startKdfParams,
): Promise<{ readonly params: KdfParams; readonly material: LoginMaterial }> {
  for (
    let params: KdfParams | null = startParams(salt);
    params !== null;
    params = nextKdfParamsAfterFailure(params)
  ) {
    const kek = await stretchPassword(sodium, password, params).catch(() => null);
    if (kek !== null) return { params, material: materialFromKek(sodium, kek) };
  }
  throw new KdfUnavailableError();
}

export function recoveryLoginKey(sodium: Sodium, recoveryKey: Uint8Array): Uint8Array {
  return sodium.kdfDerive(
    KEY_BYTES,
    KDF_SUBKEY_IDS.recoveryLogin,
    KDF_CONTEXTS.recovery,
    recoveryKey,
  );
}
