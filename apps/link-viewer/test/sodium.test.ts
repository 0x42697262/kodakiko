import sodiumLib from 'libsodium-wrappers-sumo';
import { describe, expect, it } from 'vitest';
import { DecryptionError, KDF_CONTEXTS, checkVectors } from '@kodakiko/core';
import { CRYPTO_VECTORS } from '@kodakiko/core/vectors';
import { createWrappersSodium, loadSodium } from '../src/sodium.ts';

const KEY_LENGTH = 32;
const NONCE_LENGTH = 24;

async function buildSodium() {
  await sodiumLib.ready;
  return createWrappersSodium(sodiumLib, async (input) =>
    sodiumLib.crypto_pwhash(
      input.outputLength,
      input.password,
      input.salt,
      input.opsLimit,
      input.memLimitBytes,
      sodiumLib.crypto_pwhash_ALG_ARGON2ID13,
    ),
  );
}

describe('wrappers sodium (twin of the apps/client adapter)', () => {
  it('replays every committed crypto vector', async () => {
    const results = await checkVectors(await buildSodium(), CRYPTO_VECTORS, () => performance.now());
    expect(results.length).toBeGreaterThan(0);
    expect(results.filter((result) => !result.passed)).toEqual([]);
  });

  it('rethrows decrypt and open failures as DecryptionError', async () => {
    const sodium = await buildSodium();
    const key = sodium.randomBytes(KEY_LENGTH);
    const nonce = sodium.randomBytes(NONCE_LENGTH);
    const ciphertext = sodium.aeadEncrypt(new Uint8Array([1, 2, 3]), new Uint8Array(0), nonce, key);
    const tampered = ciphertext.slice();
    tampered[0] = (tampered[0] ?? 0) ^ 1;
    expect(() => sodium.aeadDecrypt(tampered, new Uint8Array(0), nonce, key)).toThrow(DecryptionError);
    const pair = sodium.boxKeypair();
    expect(() => sodium.boxSealOpen(new Uint8Array(80), pair.publicKey, pair.privateKey)).toThrow(DecryptionError);
    expect(sodium.kdfDerive(KEY_LENGTH, 1, KDF_CONTEXTS.login, key)).toHaveLength(KEY_LENGTH);
  });
});

describe('loadSodium', () => {
  it('waits for libsodium and derives the same subkeys as the reference adapter', async () => {
    const loaded = await loadSodium();
    const reference = await buildSodium();
    const key = reference.randomBytes(KEY_LENGTH);
    expect(loaded.kdfDerive(KEY_LENGTH, 2, KDF_CONTEXTS.link, key)).toEqual(reference.kdfDerive(KEY_LENGTH, 2, KDF_CONTEXTS.link, key));
  });

  it('has no password hashing: a link viewer never derives keys from a password', async () => {
    const loaded = await loadSodium();
    await expect(
      loaded.argon2id({ password: new Uint8Array(1), salt: new Uint8Array(16), opsLimit: 1, memLimitBytes: 8192, outputLength: KEY_LENGTH }),
    ).rejects.toThrow();
  });
});
