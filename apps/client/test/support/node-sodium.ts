import sodiumLibrary from 'libsodium-wrappers-sumo';
import type { Sodium } from '@kodakiko/core';
import { createWrappersSodium } from '../../src/adapters/sodium/sodium-wrappers';

export async function createNodeSodium(): Promise<Sodium> {
  await sodiumLibrary.ready;
  return createWrappersSodium(sodiumLibrary, async (input) =>
    sodiumLibrary.crypto_pwhash(
      input.outputLength,
      input.password,
      input.salt,
      input.opsLimit,
      input.memLimitBytes,
      sodiumLibrary.crypto_pwhash_ALG_ARGON2ID13,
    ),
  );
}
