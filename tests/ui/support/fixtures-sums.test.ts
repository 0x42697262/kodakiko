import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  FIXTURES_DIRECTORY,
  FIXTURES_MAX_TOTAL_BYTES,
  FIXTURES_SUMS_FILE,
  GENERATED_FIXTURES,
} from './constants.ts';

const SUM_LINE = /^([0-9a-f]{64}) {2}(\S+)$/;

async function readSums(): Promise<Map<string, string>> {
  const text = await readFile(join(FIXTURES_DIRECTORY, FIXTURES_SUMS_FILE), 'utf8');
  const sums = new Map<string, string>();
  for (const line of text.split('\n').filter((entry) => entry !== '')) {
    const match = SUM_LINE.exec(line);
    if (match === null) throw new Error(`Bad SHA256SUMS line: ${line}`);
    sums.set(match[2] ?? '', match[1] ?? '');
  }
  return sums;
}

describe('media fixtures', () => {
  it('matches SHA256SUMS for every listed file', async () => {
    const sums = await readSums();
    expect(sums.size).toBeGreaterThan(0);
    for (const [name, expected] of sums) {
      const bytes = await readFile(join(FIXTURES_DIRECTORY, name));
      expect(createHash('sha256').update(bytes).digest('hex'), name).toBe(expected);
    }
  });

  it('lists the files generate.sh produces', async () => {
    const names = [...(await readSums()).keys()];
    expect(names).toEqual(expect.arrayContaining([...GENERATED_FIXTURES]));
  });

  it('stays within the size budget', async () => {
    const names = await readdir(FIXTURES_DIRECTORY);
    let total = 0;
    for (const name of names) {
      const info = await stat(join(FIXTURES_DIRECTORY, name));
      if (info.isFile()) total += info.size;
    }
    expect(total).toBeLessThanOrEqual(FIXTURES_MAX_TOTAL_BYTES);
  });
});
