import sodiumLib from 'libsodium-wrappers-sumo';
import { describe, expect, it } from 'vitest';
import { DecryptionError, KDF_CONTEXTS, checkVectors } from '@kodakiko/core';
import { CRYPTO_VECTORS } from '@kodakiko/core/vectors';
import { createWrappersSodium } from '../src/adapters/sodium/sodium-wrappers';
import { hashAccountKey } from '../src/adapters/account-key';
import { createRandomId } from '../src/adapters/ids';
import { ID_REGEX } from '@kodakiko/contract';

const KEY_LENGTH = 32;

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

describe('wrappers sodium', () => {
  it('replays every committed crypto vector', async () => {
    const results = await checkVectors(await buildSodium(), CRYPTO_VECTORS, () =>
      performance.now(),
    );
    expect(results.length).toBeGreaterThan(0);
    expect(results.filter((result) => !result.passed)).toEqual([]);
  });

  it('rethrows decrypt and open failures as DecryptionError', async () => {
    const sodium = await buildSodium();
    const key = sodium.randomBytes(KEY_LENGTH);
    const nonce = sodium.randomBytes(24);
    const ciphertext = sodium.aeadEncrypt(new Uint8Array([1, 2, 3]), new Uint8Array(0), nonce, key);
    const tampered = ciphertext.slice();
    tampered[0] = (tampered[0] ?? 0) ^ 1;
    expect(() => sodium.aeadDecrypt(tampered, new Uint8Array(0), nonce, key)).toThrow(
      DecryptionError,
    );
    const pair = sodium.boxKeypair();
    expect(() => sodium.boxSealOpen(new Uint8Array(80), pair.publicKey, pair.privateKey)).toThrow(
      DecryptionError,
    );
    expect(sodium.kdfDerive(KEY_LENGTH, 1, KDF_CONTEXTS.login, key)).toHaveLength(KEY_LENGTH);
  });
});

describe('account key hash and ids', () => {
  it('hashes an account key to 16 lowercase hex characters, deterministically', async () => {
    const sodium = await buildSodium();
    const first = hashAccountKey(sodium, 'journal.example.test/user-a');
    expect(first).toMatch(/^[0-9a-f]{16}$/);
    expect(hashAccountKey(sodium, 'journal.example.test/user-a')).toBe(first);
    expect(hashAccountKey(sodium, 'journal.example.test/user-b')).not.toBe(first);
    expect(first).not.toContain('user');
  });

  it('creates 22 character base64url ids that match the id pattern', async () => {
    const randomId = createRandomId(await buildSodium());
    const first = randomId();
    expect(first).toHaveLength(22);
    expect(first).toMatch(ID_REGEX);
    expect(randomId()).not.toBe(first);
  });
});
