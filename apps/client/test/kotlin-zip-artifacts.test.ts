import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unzipSync, zipSync } from 'fflate';
import { describe, expect, it } from 'vitest';

const DEFAULT_DIRECTORY = fileURLToPath(
  new URL('../../android/build/kotlin-artifacts/zip/', import.meta.url),
);
const SUFFIX = '.artifact.json';

type ZipArtifact = {
  readonly kind: string;
  readonly file: string;
  readonly entries: readonly {
    readonly name: string;
    readonly size: number;
    readonly sha256: string;
    readonly mtime: string;
  }[];
};

const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

async function verify(directory: string): Promise<readonly string[]> {
  if (!existsSync(directory)) return [];
  const problems: string[] = [];
  for (const name of (await readdir(directory)).filter((file) => file.endsWith(SUFFIX)).sort()) {
    try {
      const artifact = JSON.parse(await readFile(join(directory, name), 'utf8')) as ZipArtifact;
      if (artifact.kind !== 'zip') {
        problems.push(`${name}: unknown kind ${artifact.kind}`);
        continue;
      }
      const unzipped = unzipSync(new Uint8Array(await readFile(join(directory, artifact.file))));
      const actual = Object.keys(unzipped).sort();
      const expected = artifact.entries.map((entry) => entry.name).sort();
      if (JSON.stringify(actual) !== JSON.stringify(expected))
        problems.push(`${name}: entries ${JSON.stringify(actual)}`);
      for (const entry of artifact.entries) {
        const bytes = unzipped[entry.name];
        if (bytes === undefined || bytes.length !== entry.size || sha256(bytes) !== entry.sha256)
          problems.push(`${name}: ${entry.name} differs`);
      }
    } catch (error) {
      problems.push(`${name}: ${String(error)}`);
    }
  }
  return problems;
}

describe('ZIP archives written by the Kotlin export', () => {
  it('every Kotlin archive unzips with fflate to exactly its declared files', async () => {
    expect(await verify(process.env['KOTLIN_ZIP_ARTIFACTS_DIR'] ?? DEFAULT_DIRECTORY)).toEqual([]);
  });

  it('a corrupted or mislabeled archive fails', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'kodakiko-zip-artifacts-'));
    try {
      const content = new TextEncoder().encode('hello hello hello hello');
      const zip = zipSync({ 'a.txt': content }, { level: 6 });
      const corrupted = zip.slice();
      corrupted[40] = (corrupted[40] ?? 0) ^ 0xff;
      const entry = {
        name: 'a.txt',
        size: content.length,
        sha256: sha256(content),
        mtime: '2026-01-01T00:00:00.000Z',
      };
      await writeFile(join(directory, 'good.zip'), zip);
      await writeFile(join(directory, 'bad.zip'), corrupted);
      await writeFile(
        join(directory, `good${SUFFIX}`),
        JSON.stringify({ kind: 'zip', file: 'good.zip', entries: [entry] }),
      );
      await writeFile(
        join(directory, `bad${SUFFIX}`),
        JSON.stringify({ kind: 'zip', file: 'bad.zip', entries: [entry] }),
      );
      await writeFile(
        join(directory, `wrong${SUFFIX}`),
        JSON.stringify({ kind: 'zip', file: 'good.zip', entries: [{ ...entry, name: 'b.txt' }] }),
      );
      const problems = await verify(directory);
      expect(problems.some((problem) => problem.startsWith('bad'))).toBe(true);
      expect(problems.some((problem) => problem.startsWith('wrong'))).toBe(true);
      expect(problems.some((problem) => problem.startsWith('good'))).toBe(false);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
