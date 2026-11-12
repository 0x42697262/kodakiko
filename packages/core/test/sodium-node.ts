import sodium from 'libsodium-wrappers-sumo';
import { CRYPTO_TEXT, DecryptionError, type Sodium } from '../src/crypto/index.ts';
import type { RandomSource } from '../src/crypto/deterministic-bytes.ts';

export async function createNodeSodium(random?: RandomSource): Promise<Sodium> {
  await sodium.ready;
  return {
    randomBytes: (length) =>
      random === undefined ? sodium.randombytes_buf(length) : random(length),
    argon2id: async (input) =>
      sodium.crypto_pwhash(
        input.outputLength,
        input.password,
        input.salt,
        input.opsLimit,
        input.memLimitBytes,
        sodium.crypto_pwhash_ALG_ARGON2ID13,
      ),
    kdfDerive: (subkeyLength, subkeyId, context, key) =>
      sodium.crypto_kdf_derive_from_key(subkeyLength, subkeyId, context, key),
    aeadEncrypt: (plaintext, additionalData, nonce, key) =>
      sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
        plaintext,
        additionalData,
        null,
        nonce,
        key,
      ),
    aeadDecrypt: (ciphertext, additionalData, nonce, key) => {
      try {
        return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
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
      const keypair = sodium.crypto_box_keypair();
      return { publicKey: keypair.publicKey, privateKey: keypair.privateKey };
    },
    boxSeal: (message, recipientPublicKey) => sodium.crypto_box_seal(message, recipientPublicKey),
    boxSealOpen: (ciphertext, publicKey, privateKey) => {
      try {
        return sodium.crypto_box_seal_open(ciphertext, publicKey, privateKey);
      } catch {
        throw new DecryptionError(CRYPTO_TEXT.decryptionFailed);
      }
    },
    genericHash: (outputLength, message) => sodium.crypto_generichash(outputLength, message, null),
  };
}
