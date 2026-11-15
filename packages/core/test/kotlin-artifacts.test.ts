import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as contract from '@kodakiko/contract';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { counterBytes } from '../src/crypto/deterministic-bytes.ts';
import { bytesToHex, hexToBytes, utf8Decode } from '../src/crypto/bytes.ts';
import { openBlob } from '../src/crypto/sealed-blob.ts';
import { unpadIso7816 } from '../src/crypto/padding.ts';
import { createStreamDecryptor, layoutFromCiphertextSize } from '../src/crypto/stream.ts';
import { decryptKdkx, deriveKdkxKey, readKdkxParams } from '../src/export/kdkx.ts';
import { PADDING_BLOCK_BYTES, STREAM_HEADER_BYTES } from '../src/crypto/constants.ts';
import type { AdContext } from '../src/crypto/constants.ts';
import type { Sodium } from '../src/crypto/sodium.ts';
import { createNodeSodium } from './sodium-node.ts';

const DEFAULT_DIRECTORY = fileURLToPath(new URL('../../../apps/android/build/kotlin-artifacts/', import.meta.url));
const ARTIFACT_SUFFIX = '.artifact.json';
const CLIENT_VERIFIED_DIRECTORY = 'zip';
const STREAM_EXPONENT = 20;
const TAG_BYTES = 16;
const KDKX_PREFIX_AND_HEADER_BYTES = 53;
const KDKX_CHUNK = 1_048_576;

type Artifact = Record<string, unknown> & { readonly kind: string };
export type ArtifactResult = { readonly file: string; readonly ok: boolean; readonly message: string };

function decryptStream(sodium: Sodium, key: Uint8Array, object: Uint8Array, size: number): Uint8Array {
  const layout = layoutFromCiphertextSize(object.length, STREAM_EXPONENT);
  const decryptor = createStreamDecryptor(sodium, key, object.slice(0, STREAM_HEADER_BYTES), size);
  const parts: Uint8Array[] = [];
  for (let index = 0; index < layout.chunkCount; index += 1) {
    const start = STREAM_HEADER_BYTES + index * (layout.chunkSize + TAG_BYTES);
    parts.push(decryptor.decryptNext(object.slice(start, Math.min(object.length, start + layout.chunkSize + TAG_BYTES))));
  }
  decryptor.finish();
  return Buffer.concat(parts);
}

const CHECKS: Record<string, (sodium: Sodium, artifact: Artifact, directory: string) => Promise<string | null>> = {
  'sealed-json': async (sodium, artifact) => {
    const padded = openBlob(
      sodium,
      hexToBytes(artifact['key'] as string),
      hexToBytes(artifact['blob'] as string),
      artifact['context'] as AdContext,
      artifact['bindingId'] as string,
    );
    const json = utf8Decode(unpadIso7816(padded, PADDING_BLOCK_BYTES));
    const schema = (contract as Record<string, unknown>)[artifact['schema'] as string];
    if (!(schema instanceof z.ZodType)) return `unknown schema ${String(artifact['schema'])}`;
    if (!schema.safeParse(JSON.parse(json)).success) return `payload does not match ${String(artifact['schema'])}: ${json}`;
    if (JSON.stringify(JSON.parse(json)) !== JSON.stringify(JSON.parse(artifact['expectedJson'] as string))) {
      return `payload ${json} differs from expected ${String(artifact['expectedJson'])}`;
    }
    return null;
  },
  'stream-object': async (sodium, artifact, directory) => {
    const object = new Uint8Array(await readFile(join(directory, artifact['file'] as string)));
    const size = artifact['plaintextSize'] as number;
    const plaintext = decryptStream(sodium, hexToBytes(artifact['key'] as string), object, size);
    const expected = counterBytes(artifact['plaintextSeed'] as number, size);
    return bytesToHex(plaintext) === bytesToHex(expected) ? null : 'plaintext differs';
  },
  kdkx: async (sodium, artifact, directory) => {
    const file = new Uint8Array(await readFile(join(directory, artifact['file'] as string)));
    const params = readKdkxParams(file);
    if (params.opsLimit !== artifact['opsLimit'] || params.memLimitBytes !== artifact['memLimitBytes']) return 'KDKX params differ';
    if (bytesToHex(params.salt) !== artifact['salt']) return 'KDKX salt differs';
    const size = artifact['plaintextSize'] as number;
    const expectedSize = KDKX_PREFIX_AND_HEADER_BYTES + size + TAG_BYTES * Math.max(1, Math.ceil(size / KDKX_CHUNK));
    if (file.length !== expectedSize) return `KDKX size ${file.length}, expected ${expectedSize}`;
    const key = await deriveKdkxKey(sodium, artifact['passphrase'] as string, params);
    const plaintext = decryptKdkx(sodium, key, file);
    return bytesToHex(plaintext) === bytesToHex(counterBytes(artifact['plaintextSeed'] as number, size)) ? null : 'KDKX plaintext differs';
  },
  dto: async (_sodium, artifact) => {
    const schema = (contract as Record<string, unknown>)[artifact['schema'] as string];
    if (!(schema instanceof z.ZodType)) return `unknown schema ${String(artifact['schema'])}`;
    const result = schema.safeParse(JSON.parse(artifact['json'] as string));
    return result.success ? null : result.error.message;
  },
};

export async function verifyArtifacts(directory: string): Promise<readonly ArtifactResult[]> {
  if (!existsSync(directory)) return [];
  const sodium = await createNodeSodium();
  const entries = await readdir(directory, { recursive: true, withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(ARTIFACT_SUFFIX))
    .filter((entry) => !entry.parentPath.split(sep).includes(CLIENT_VERIFIED_DIRECTORY))
    .map((entry) => join(entry.parentPath, entry.name))
    .sort();
  const results: ArtifactResult[] = [];
  for (const file of files) {
    try {
      const artifact = JSON.parse(await readFile(file, 'utf8')) as Artifact;
      const check = CHECKS[artifact.kind];
      const problem = check === undefined ? `unknown kind ${artifact.kind}` : await check(sodium, artifact, dirname(file));
      results.push({ file, ok: problem === null, message: problem ?? '' });
    } catch (error) {
      results.push({ file, ok: false, message: String(error) });
    }
  }
  return results;
}

describe('artifacts written by the Kotlin tests', () => {
  it('every Kotlin artifact verifies with the TypeScript reference', async () => {
    const directory = process.env['KOTLIN_ARTIFACTS_DIR'] ?? DEFAULT_DIRECTORY;
    const results = await verifyArtifacts(directory);
    const failed = results.filter((result) => !result.ok).map((result) => `${result.file}: ${result.message}`);
    expect(failed).toEqual([]);
  });

  it('a tampered or unknown artifact fails', async () => {
    const sodium = await createNodeSodium();
    const directory = await mkdtemp(join(tmpdir(), 'kodakiko-artifacts-'));
    try {
      const { sealJson } = await import('../src/crypto/sealed-blob.ts');
      const key = new Uint8Array(32).fill(7);
      const value = { schemaVersion: 1, name: 'Home', createdAtUtc: '2026-11-14T00:00:00.000Z' };
      const blob = sealJson(sodium, key, value, 'kodakiko/v1/collection-metadata', 'AAAAAAAAAAAAAAAAAAAAAA');
      const good = {
        kind: 'sealed-json',
        schema: 'CollectionMetadataV1',
        key: bytesToHex(key),
        context: 'kodakiko/v1/collection-metadata',
        bindingId: 'AAAAAAAAAAAAAAAAAAAAAA',
        blob: bytesToHex(blob),
        expectedJson: JSON.stringify(value),
      };
      const tampered = { ...good, blob: bytesToHex(blob).slice(0, -2) + (blob.at(-1) === 0 ? '01' : '00') };
      await writeFile(join(directory, `good${ARTIFACT_SUFFIX}`), JSON.stringify(good));
      await writeFile(join(directory, `tampered${ARTIFACT_SUFFIX}`), JSON.stringify(tampered));
      await writeFile(join(directory, `unknown${ARTIFACT_SUFFIX}`), JSON.stringify({ kind: 'nope' }));
      await writeFile(join(directory, `dto${ARTIFACT_SUFFIX}`), JSON.stringify({ kind: 'dto', schema: 'idSchema', json: '"short"' }));
      const results = await verifyArtifacts(directory);
      const byName = Object.fromEntries(results.map((result) => [result.file.split('/').at(-1), result.ok]));
      expect(byName).toEqual({
        [`dto${ARTIFACT_SUFFIX}`]: false,
        [`good${ARTIFACT_SUFFIX}`]: true,
        [`tampered${ARTIFACT_SUFFIX}`]: false,
        [`unknown${ARTIFACT_SUFFIX}`]: false,
      });
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
