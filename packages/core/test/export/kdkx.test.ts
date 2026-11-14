import { beforeAll, describe, expect, it } from 'vitest';
import { createKdkxWriter, decryptKdkx, deriveKdkxKey, isKdkx, KDKX_CHUNK_BYTES, readKdkxParams, type Sodium } from '../../src/index.ts';
import { createNodeSodium } from '../sodium-node.ts';

const PASSPHRASE = 'a long export passphrase';
const FAST = { opsLimit: 2, memLimitBytes: 8_388_608 };

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function bytes(length: number, seed = 1): Uint8Array {
  return Uint8Array.from({ length }, (_, index) => (index * 31 + seed) % 251);
}

async function encrypt(plain: Uint8Array, pieces: number[]): Promise<Uint8Array> {
  const salt = sodium.randomBytes(16);
  const key = await deriveKdkxKey(sodium, PASSPHRASE, { salt, ...FAST });
  const writer = createKdkxWriter(sodium, key, { salt, ...FAST });
  const out: Uint8Array[] = [writer.header];
  let offset = 0;
  for (const size of pieces) {
    out.push(...writer.push(plain.subarray(offset, offset + size)));
    offset += size;
  }
  out.push(writer.finish());
  const total = out.reduce((sum, part) => sum + part.length, 0);
  const joined = new Uint8Array(total);
  let at = 0;
  for (const part of out) {
    joined.set(part, at);
    at += part.length;
  }
  return joined;
}

async function decrypt(file: Uint8Array, passphrase = PASSPHRASE): Promise<Uint8Array> {
  const params = readKdkxParams(file);
  return decryptKdkx(sodium, await deriveKdkxKey(sodium, passphrase, params), file);
}

describe('.kdkx encrypted export parts', () => {
  it('round-trips empty, small, exact-chunk and multi-chunk payloads written in uneven pieces', async () => {
    for (const size of [0, 5, KDKX_CHUNK_BYTES, KDKX_CHUNK_BYTES * 2 + 7]) {
      const plain = bytes(size, size);
      const pieces = size === 0 ? [] : [Math.min(size, 3), Math.max(0, size - 3)];
      const file = await encrypt(plain, pieces);
      expect(isKdkx(file)).toBe(true);
      expect(await decrypt(file)).toEqual(plain);
    }
  });

  it('rejects a wrong passphrase, a flipped byte and a truncated file', async () => {
    const file = await encrypt(bytes(KDKX_CHUNK_BYTES + 10), [KDKX_CHUNK_BYTES + 10]);
    await expect(decrypt(file, 'not the passphrase')).rejects.toThrow();
    const flipped = file.slice();
    flipped[flipped.length - 20] = (flipped[flipped.length - 20] ?? 0) ^ 1;
    await expect(decrypt(flipped)).rejects.toThrow();
    await expect(decrypt(file.subarray(0, file.length - KDKX_CHUNK_BYTES / 2))).rejects.toThrow();
  });

  it('tells a .kdkx part from a zip', () => {
    expect(isKdkx(Uint8Array.of(0x50, 0x4b, 0x03, 0x04))).toBe(false);
  });
});
