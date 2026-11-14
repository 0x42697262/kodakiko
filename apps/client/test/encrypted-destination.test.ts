import { decryptKdkx, deriveKdkxKey, readKdkxParams, type Sodium } from '@kodakiko/core';
import { beforeAll, describe, expect, it } from 'vitest';
import { encryptedDestination } from '../src/services/export/encrypted-destination';
import type { ExportDestination } from '../src/services/export/export-service';
import { createNodeSodium } from './support/node-sodium';

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function memoryDestination() {
  const files = new Map<string, Uint8Array[]>();
  const aborted: string[] = [];
  const destination: ExportDestination = {
    partMaxBytes: 1_000,
    createPart: async (name) => {
      files.set(name, []);
      return {
        write: async (bytes) => {
          files.get(name)?.push(bytes.slice());
        },
        close: async () => undefined,
        abort: async () => {
          aborted.push(name);
        },
      };
    },
  };
  return { destination, files, aborted };
}

function joined(parts: readonly Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

describe('encryptedDestination (spec 23 §7)', () => {
  it('writes each part as name.kdkx that decrypts with the passphrase to the original bytes', async () => {
    const inner = memoryDestination();
    const destination = await encryptedDestination(sodium, inner.destination, 'export passphrase', { opsLimit: 2, memLimitBytes: 8_388_608 });
    expect(destination.partMaxBytes).toBe(1_000);
    const part = await destination.createPart('kodakiko-export-01.zip');
    await part.write(Uint8Array.of(1, 2, 3));
    await part.write(Uint8Array.of(4, 5));
    await part.close();
    const file = joined(inner.files.get('kodakiko-export-01.zip.kdkx') ?? []);
    const key = await deriveKdkxKey(sodium, 'export passphrase', readKdkxParams(file));
    expect(decryptKdkx(sodium, key, file)).toEqual(Uint8Array.of(1, 2, 3, 4, 5));
  });

  it('passes an abort through to the inner part', async () => {
    const inner = memoryDestination();
    const destination = await encryptedDestination(sodium, inner.destination, 'export passphrase', { opsLimit: 2, memLimitBytes: 8_388_608 });
    const part = await destination.createPart('x.zip');
    await part.abort();
    expect(inner.aborted).toEqual(['x.zip.kdkx']);
  });
});
