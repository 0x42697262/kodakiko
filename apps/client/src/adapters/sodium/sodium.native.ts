import * as lib from 'react-native-libsodium';
import { CRYPTO_TEXT, DecryptionError, type Sodium } from '@kodakiko/core';
import { EN } from '../../text/en';
import { additionalDataText } from './sodium-ad';

const REQUIRED_FUNCTIONS = [
  'randombytes_buf',
  'crypto_pwhash',
  'crypto_kdf_derive_from_key',
  'crypto_aead_xchacha20poly1305_ietf_encrypt',
  'crypto_aead_xchacha20poly1305_ietf_decrypt',
  'crypto_box_keypair',
  'crypto_box_seal',
  'crypto_box_seal_open',
  'crypto_generichash',
] as const;
const REQUIRED_CONSTANTS = ['crypto_pwhash_ALG_ARGON2ID13'] as const;

function assertWrapperSurface(): void {
  for (const name of REQUIRED_FUNCTIONS) {
    if (typeof lib[name] !== 'function') throw new Error(EN['sodium.missingFunction'](name));
  }
  for (const name of REQUIRED_CONSTANTS) {
    if (typeof lib[name] !== 'number') throw new Error(EN['sodium.missingFunction'](name));
  }
}

function createNativeSodium(): Sodium {
  return {
    randomBytes: (length) => lib.randombytes_buf(length),
    argon2id: (input) =>
      new Promise((resolve) => {
        resolve(
          lib.crypto_pwhash(
            input.outputLength,
            input.password,
            input.salt,
            input.opsLimit,
            input.memLimitBytes,
            lib.crypto_pwhash_ALG_ARGON2ID13,
          ),
        );
      }),
    kdfDerive: (subkeyLength, subkeyId, context, key) =>
      lib.crypto_kdf_derive_from_key(subkeyLength, subkeyId, context, key),
    aeadEncrypt: (plaintext, additionalData, nonce, key) =>
      lib.crypto_aead_xchacha20poly1305_ietf_encrypt(
        plaintext,
        additionalDataText(additionalData),
        null,
        nonce,
        key,
      ),
    aeadDecrypt: (ciphertext, additionalData, nonce, key) => {
      const additionalText = additionalDataText(additionalData);
      try {
        return lib.crypto_aead_xchacha20poly1305_ietf_decrypt(
          null,
          ciphertext,
          additionalText,
          nonce,
          key,
        );
      } catch {
        throw new DecryptionError(CRYPTO_TEXT.decryptionFailed);
      }
    },
    boxKeypair: () => {
      const keypair = lib.crypto_box_keypair();
      return { publicKey: keypair.publicKey, privateKey: keypair.privateKey };
    },
    boxSeal: (message, recipientPublicKey) => lib.crypto_box_seal(message, recipientPublicKey),
    boxSealOpen: (ciphertext, publicKey, privateKey) => {
      try {
        return lib.crypto_box_seal_open(ciphertext, publicKey, privateKey);
      } catch {
        throw new DecryptionError(CRYPTO_TEXT.decryptionFailed);
      }
    },
    genericHash: (outputLength, message) => lib.crypto_generichash(outputLength, message, null),
  };
}

export async function loadSodium(): Promise<Sodium> {
  await lib.ready;
  assertWrapperSurface();
  return createNativeSodium();
}
