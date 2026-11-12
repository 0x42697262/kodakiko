import { describe, expect, it } from 'vitest';
import { checkVectors, type CryptoVectors } from '../src/crypto/index.ts';
import { CRYPTO_VECTORS } from '../vectors/index.ts';
import { createNodeSodium } from './sodium-node.ts';

const EXPECTED_CASE_COUNTS: Readonly<Record<string, number>> = {
  'argon2id.json': 2,
  'kdf.json': 6,
  'sealed-blob.json': 13,
  'padding.json': 6,
  'stream.json': 6,
  'recovery-words.json': 3,
  'share-payload.json': 2,
};

function flipFirstHexCharacter(hex: string): string {
  const first = hex.startsWith('0') ? '1' : '0';
  return first + hex.slice(1);
}

describe('committed vectors', () => {
  it('every case replays green', async () => {
    const results = await checkVectors(await createNodeSodium(), CRYPTO_VECTORS, () =>
      performance.now(),
    );
    expect(results.filter((result) => !result.passed)).toEqual([]);
    expect(results.length).toBeGreaterThan(0);
  });

  it('has the planned files and case counts', async () => {
    const results = await checkVectors(await createNodeSodium(), CRYPTO_VECTORS, () =>
      performance.now(),
    );
    const counts: Record<string, number> = {};
    for (const result of results) counts[result.file] = (counts[result.file] ?? 0) + 1;
    expect(counts).toEqual(EXPECTED_CASE_COUNTS);
    expect(results[0]?.name).toBe('argon2id.json#0');
  });

  it('reports exactly the tampered case as failed', async () => {
    const [firstKdfCase, ...otherKdfCases] = CRYPTO_VECTORS.kdf.cases;
    if (firstKdfCase === undefined) throw new Error('kdf.json has no cases');
    const tampered: CryptoVectors = {
      ...CRYPTO_VECTORS,
      kdf: {
        ...CRYPTO_VECTORS.kdf,
        cases: [
          { ...firstKdfCase, subkey: flipFirstHexCharacter(firstKdfCase.subkey) },
          ...otherKdfCases,
        ],
      },
    };
    const results = await checkVectors(await createNodeSodium(), tampered, () => performance.now());
    const failed = results.filter((result) => !result.passed);
    expect(failed.map((result) => result.name)).toEqual(['kdf.json#0']);
    expect(results.filter((result) => result.passed)).toHaveLength(results.length - 1);
  });

  it('fails every case of a file whose format is not 1', async () => {
    const wrongFormat = {
      ...CRYPTO_VECTORS,
      kdf: { ...CRYPTO_VECTORS.kdf, format: 2 },
    } as unknown as CryptoVectors;
    const results = await checkVectors(await createNodeSodium(), wrongFormat, () =>
      performance.now(),
    );
    const kdfResults = results.filter((result) => result.file === 'kdf.json');
    expect(kdfResults).toHaveLength(6);
    expect(kdfResults.every((result) => !result.passed)).toBe(true);
    expect(
      results.filter((result) => result.file !== 'kdf.json').every((result) => result.passed),
    ).toBe(true);
  });

  it('measures a duration for every case', async () => {
    const results = await checkVectors(await createNodeSodium(), CRYPTO_VECTORS, () =>
      performance.now(),
    );
    for (const result of results) expect(result.durationMs).toBeGreaterThanOrEqual(0);
  });
});
