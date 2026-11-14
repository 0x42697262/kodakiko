import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as contract from '@kodakiko/contract';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { counterBytes } from '../src/crypto/deterministic-bytes.ts';
import { hexToBytes, utf8Decode } from '../src/crypto/bytes.ts';
import { openBlob } from '../src/crypto/sealed-blob.ts';
import { unpadIso7816 } from '../src/crypto/padding.ts';
import { createStreamDecryptor, layoutFromCiphertextSize } from '../src/crypto/stream.ts';
import { PADDING_BLOCK_BYTES } from '../src/crypto/constants.ts';
import type { AdContext } from '../src/crypto/constants.ts';
import { generateGoldens } from '../scripts/generate-goldens.ts';
import { createNodeSodium } from './sodium-node.ts';

const GOLDEN = fileURLToPath(new URL('../../../tests/fixtures/golden/', import.meta.url));

type ManifestFile = { readonly path: string; readonly caseFields: readonly string[]; readonly minCases: number };
type Manifest = { readonly format: number; readonly generator: string; readonly files: readonly ManifestFile[] };
type GoldenFile = { readonly format: number; readonly generator: string; readonly cases: readonly Record<string, unknown>[] };

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, 'utf8')) as T;
}

async function listFiles(directory: string): Promise<readonly string[]> {
  const entries = await readdir(directory, { recursive: true, withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile())
    .map((entry) => relative(directory, join(entry.parentPath, entry.name)))
    .sort();
}

const manifest = await readJson<Manifest>(join(GOLDEN, 'manifest.json'));

describe('golden fixtures for the Kotlin port', () => {
  it('every manifest file exists with the required case fields', async () => {
    for (const file of manifest.files) {
      const golden = await readJson<GoldenFile>(join(GOLDEN, file.path));
      expect(golden.format, file.path).toBe(1);
      expect(golden.generator, file.path).toBe(manifest.generator);
      expect(golden.cases.length, file.path).toBeGreaterThanOrEqual(file.minCases);
      for (const goldenCase of golden.cases) {
        for (const field of file.caseFields) expect(goldenCase, `${file.path} ${field}`).toHaveProperty(field);
      }
    }
  });

  it('regenerating produces byte-identical files', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'kodakiko-goldens-'));
    try {
      const written = await generateGoldens(directory);
      const committed = (await listFiles(GOLDEN)).filter((path) => path !== 'manifest.json' && path !== 'README.md');
      expect([...written].sort()).toEqual(committed);
      for (const path of committed) {
        const fresh = await readFile(join(directory, path));
        const stored = await readFile(join(GOLDEN, path));
        expect(fresh.equals(stored), path).toBe(true);
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }, 120_000);

  it('sealed JSON goldens open with their key and match their JSON', async () => {
    const sodium = await createNodeSodium();
    const golden = await readJson<GoldenFile>(join(GOLDEN, 'crypto/sealed-json.json'));
    const schemas = new Set(golden.cases.map((entry) => entry['schema']));
    expect(schemas).toEqual(new Set(['EntryMetadataV1', 'CollectionMetadataV1']));
    for (const entry of golden.cases) {
      const padded = openBlob(
        sodium,
        hexToBytes(entry['key'] as string),
        hexToBytes(entry['blob'] as string),
        entry['context'] as AdContext,
        entry['bindingId'] as string,
      );
      const json = utf8Decode(unpadIso7816(padded, PADDING_BLOCK_BYTES));
      expect(json, entry['name'] as string).toBe(entry['json']);
      const schema = entry['schema'] === 'EntryMetadataV1' ? contract.EntryMetadataV1 : contract.CollectionMetadataV1;
      expect(schema.safeParse(JSON.parse(json)).success, entry['name'] as string).toBe(true);
    }
  });

  it('stream object goldens decrypt to their counter plaintext', async () => {
    const sodium = await createNodeSodium();
    const golden = await readJson<GoldenFile>(join(GOLDEN, 'crypto/stream-objects.json'));
    const sizes = golden.cases.map((entry) => entry['plaintextSize'] as number);
    expect(sizes).toEqual(expect.arrayContaining([0, 5, 1_048_577]));
    for (const entry of golden.cases) {
      const object = new Uint8Array(await readFile(join(GOLDEN, entry['file'] as string)));
      expect(createHash('sha256').update(object).digest('hex')).toBe(entry['objectSha256']);
      const size = entry['plaintextSize'] as number;
      const layout = layoutFromCiphertextSize(object.length, 20);
      const decryptor = createStreamDecryptor(sodium, hexToBytes(entry['key'] as string), object.slice(0, 24), size);
      const parts: Uint8Array[] = [];
      for (let index = 0; index < layout.chunkCount; index += 1) {
        const start = 24 + index * (layout.chunkSize + 16);
        const end = Math.min(object.length, start + layout.chunkSize + 16);
        parts.push(decryptor.decryptNext(object.slice(start, end)));
      }
      decryptor.finish();
      const plaintext = Buffer.concat(parts);
      expect(plaintext.equals(Buffer.from(counterBytes(entry['plaintextSeed'] as number, size))), entry['name'] as string).toBe(true);
    }
  });

  it('DTO samples cover every exported contract schema and parse as labeled', async () => {
    const golden = await readJson<GoldenFile>(join(GOLDEN, 'contract/dto-samples.json'));
    const exportedSchemas = Object.entries(contract)
      .filter(([, value]) => value instanceof z.ZodType)
      .map(([name]) => name)
      .sort();
    const covered = golden.cases.map((entry) => entry['schema'] as string).sort();
    expect(covered).toEqual(exportedSchemas);
    for (const entry of golden.cases) {
      const schema = (contract as Record<string, unknown>)[entry['schema'] as string] as z.ZodType;
      const valid = entry['valid'] as readonly unknown[];
      const invalid = entry['invalid'] as readonly unknown[];
      expect(valid.length, `${entry['schema']} needs a valid sample`).toBeGreaterThan(0);
      for (const sample of valid) expect(schema.safeParse(sample).success, `${entry['schema']} valid ${JSON.stringify(sample)}`).toBe(true);
      for (const sample of invalid) expect(schema.safeParse(sample).success, `${entry['schema']} invalid ${JSON.stringify(sample)}`).toBe(false);
    }
  });

  it('account goldens carry requests that the contract accepts', async () => {
    const signup = await readJson<GoldenFile>(join(GOLDEN, 'account/signup.json'));
    for (const entry of signup.cases) {
      const outputs = entry['outputs'] as Record<string, unknown>;
      expect(contract.signUpRequest.safeParse(outputs['request']).success, entry['name'] as string).toBe(true);
    }
    const recovery = await readJson<GoldenFile>(join(GOLDEN, 'account/recovery.json'));
    for (const entry of recovery.cases) {
      const outputs = entry['outputs'] as Record<string, unknown>;
      expect(contract.recoveryCompleteRequest.safeParse(outputs['request']).success, entry['name'] as string).toBe(true);
    }
    const change = await readJson<GoldenFile>(join(GOLDEN, 'account/password-change.json'));
    for (const entry of change.cases) {
      const outputs = entry['outputs'] as Record<string, unknown>;
      expect(contract.passwordChangeRequest.safeParse(outputs['request']).success, entry['name'] as string).toBe(true);
    }
  });
});
