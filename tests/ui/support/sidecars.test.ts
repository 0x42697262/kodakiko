import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { findFlowIds, parseSidecar, selectFlowIds } from './sidecars.ts';

let directory = '';

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'kodakiko-sidecars-'));
});

afterEach(async () => {
  await rm(directory, { recursive: true, force: true });
});

async function touch(relativePath: string, content = ''): Promise<void> {
  const target = join(directory, relativePath);
  await mkdir(join(target, '..'), { recursive: true });
  await writeFile(target, content);
}

describe('sidecar discovery', () => {
  it('finds U-NN.flow.ts files sorted and ignores everything else', async () => {
    await touch('U-03.flow.ts');
    await touch('U-01.flow.ts');
    await touch('U-3.flow.ts');
    await touch('U-03-extra.flow.ts');
    await touch('U-03-smoke.yaml');
    await touch('common/x.yaml');
    await touch('common/U-09.flow.ts');
    expect(await findFlowIds(directory)).toEqual(['U-01', 'U-03']);
  });

  it('filters by the requested ids and rejects unknown ones', () => {
    const ids = ['U-00', 'U-01', 'U-03'];
    expect(selectFlowIds(ids, [])).toEqual(ids);
    expect(selectFlowIds(ids, ['U-03', 'U-00'])).toEqual(['U-00', 'U-03']);
    expect(() => selectFlowIds(ids, ['U-07'])).toThrow('U-07');
  });
});

describe('sidecar loading', () => {
  it('loads parts with waitAfterMs, adb lists and a seed', async () => {
    await touch(
      'U-15.flow.ts',
      `export default {
        seed: async (project) => ({ USERNAME: 'name-' + project }),
        parts: [
          { file: 'U-15-p1.yaml', waitAfterMs: 70000, adb: [['reverse', '--remove', 'tcp:9580']] },
          { file: 'U-15-p2.yaml' },
        ],
      };`,
    );
    const loaded = await import(pathToFileURL(join(directory, 'U-15.flow.ts')).href);
    const sidecar = parseSidecar(loaded.default);
    expect(sidecar.parts).toEqual([
      { file: 'U-15-p1.yaml', waitAfterMs: 70000, adb: [['reverse', '--remove', 'tcp:9580']] },
      { file: 'U-15-p2.yaml' },
    ]);
    expect(await sidecar.seed?.('android')).toEqual({ USERNAME: 'name-android' });
  });

  it('rejects shapes without parts', () => {
    expect(() => parseSidecar(undefined)).toThrow();
    expect(() => parseSidecar({})).toThrow();
    expect(() => parseSidecar({ parts: [] })).toThrow();
    expect(() => parseSidecar({ parts: [{ waitAfterMs: 1 }] })).toThrow();
    expect(() => parseSidecar({ parts: [{ file: 'a.yaml', adb: [['ok'], 'bad'] }] })).toThrow();
  });
});
