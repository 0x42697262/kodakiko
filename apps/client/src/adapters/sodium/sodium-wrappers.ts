import type SodiumLibrary from 'libsodium-wrappers-sumo';
import { CRYPTO_TEXT, DecryptionError, type Sodium } from '@kodakiko/core';

export type WrappersLib = typeof SodiumLibrary;

export function createWrappersSodium(lib: WrappersLib, argon2id: Sodium['argon2id']): Sodium {
  return {
    randomBytes: (length) => lib.randombytes_buf(length),
    argon2id,
    kdfDerive: (subkeyLength, subkeyId, context, key) =>
      lib.crypto_kdf_derive_from_key(subkeyLength, subkeyId, context, key),
    aeadEncrypt: (plaintext, additionalData, nonce, key) =>
      lib.crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, additionalData, null, nonce, key),
    aeadDecrypt: (ciphertext, additionalData, nonce, key) => {
      try {
        return lib.crypto_aead_xchacha20poly1305_ietf_decrypt(
          null,
          ciphertext,
          additionalData,
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
