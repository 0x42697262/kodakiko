import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unzipSync } from 'fflate';
import { describe, expect, it } from 'vitest';
import { generateClientGoldens } from '../scripts/generate-golden-client.ts';

const GOLDEN = fileURLToPath(new URL('../../../tests/fixtures/golden-client/', import.meta.url));

type ManifestFile = {
  readonly path: string;
  readonly caseFields: readonly string[];
  readonly minCases: number;
};
type Manifest = {
  readonly format: number;
  readonly generator: string;
  readonly files: readonly ManifestFile[];
};
type GoldenCase = Record<string, unknown> & {
  readonly fn: string;
  readonly args: Record<string, unknown>;
  readonly result: unknown;
};
type GoldenFile = {
  readonly format: number;
  readonly generator: string;
  readonly cases: readonly GoldenCase[];
};
type FileRef = { readonly file: string; readonly size: number; readonly sha256: string };

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

async function refBytes(ref: FileRef): Promise<Buffer> {
  const bytes = await readFile(join(GOLDEN, ref.file));
  expect(bytes.length, ref.file).toBe(ref.size);
  expect(createHash('sha256').update(bytes).digest('hex'), ref.file).toBe(ref.sha256);
  return bytes;
}

const manifest = await readJson<Manifest>(join(GOLDEN, 'manifest.json'));

const REQUIRED_FUNCTIONS: Readonly<Record<string, readonly string[]>> = {
  'markdown/parse.json': ['parseMarkdown'],
  'markdown/toolbar.json': ['applyMarkdownAction'],
  'calendar/model.json': ['canShowMonth', 'monthTarget', 'pagerMonths', 'isValidLocalDate'],
  'gallery/arrange.json': ['groupSections', 'arrangeSections', 'galleryAttributes', 'columnsFor'],
  'archive/zips.json': ['writeArchive', 'readArchive'],
  'settings/device.json': ['nativeDefaults', 'resolveStored', 'update'],
};

describe('client golden fixtures for the Kotlin port', () => {
  it('every manifest file exists with the required fields and functions', async () => {
    expect(new Set(manifest.files.map((file) => file.path))).toEqual(
      new Set(Object.keys(REQUIRED_FUNCTIONS)),
    );
    for (const file of manifest.files) {
      const golden = await readJson<GoldenFile>(join(GOLDEN, file.path));
      expect(golden.format, file.path).toBe(1);
      expect(golden.generator, file.path).toBe(manifest.generator);
      expect(golden.cases.length, file.path).toBeGreaterThanOrEqual(file.minCases);
      for (const goldenCase of golden.cases) {
        for (const field of file.caseFields)
          expect(goldenCase, `${file.path} ${field}`).toHaveProperty(field);
      }
      const names = golden.cases.map((goldenCase) => goldenCase['name']);
      expect(new Set(names).size, `${file.path} case names are unique`).toBe(names.length);
      const functions = new Set(golden.cases.map((goldenCase) => goldenCase.fn));
      for (const fn of REQUIRED_FUNCTIONS[file.path] ?? [])
        expect(functions, `${file.path} covers ${fn}`).toContain(fn);
    }
  });

  it('regenerating produces byte-identical files', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'kodakiko-golden-client-'));
    try {
      const written = await generateClientGoldens(directory);
      const committed = (await listFiles(GOLDEN)).filter(
        (path) => path !== 'manifest.json' && path !== 'README.md',
      );
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

  it('written archives unzip with fflate to exactly their declared files', async () => {
    const golden = await readJson<GoldenFile>(join(GOLDEN, 'archive/zips.json'));
    const writes = golden.cases.filter((goldenCase) => goldenCase.fn === 'writeArchive');
    expect(writes.length).toBeGreaterThanOrEqual(3);
    const modes = new Set<boolean>();
    for (const goldenCase of writes) {
      const files = goldenCase.args['files'] as readonly {
        readonly name: string;
        readonly compress: boolean;
        readonly content: FileRef;
      }[];
      const zip = await refBytes((goldenCase.result as { readonly zip: FileRef }).zip);
      const unzipped = unzipSync(new Uint8Array(zip));
      expect(Object.keys(unzipped).sort(), goldenCase['name'] as string).toEqual(
        files.map((file) => file.name).sort(),
      );
      for (const file of files) {
        modes.add(file.compress);
        const expected = await refBytes(file.content);
        expect(
          Buffer.from(unzipped[file.name] ?? new Uint8Array(0)).equals(expected),
          `${goldenCase['name']} ${file.name}`,
        ).toBe(true);
      }
    }
    expect(modes).toEqual(new Set([true, false]));
  });
});
