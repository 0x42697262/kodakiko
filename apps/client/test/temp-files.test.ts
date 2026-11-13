import { beforeAll, describe, expect, it } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createTempFiles } from '../src/adapters/temp-files/temp-files.web';
import { TempFilesUnsupportedError } from '../src/adapters/temp-files/temp-files';
import { tmpDirectory } from '../src/keys/paths';
import { createNodeSodium } from './support';

const ACCOUNT_HASH = '0123456789abcdef';

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function webTempFiles() {
  return createTempFiles({ sodium, activeAccountKey: () => null });
}

describe('web temp files', () => {
  it('refuses to create a plaintext file for every purpose', async () => {
    const tempFiles = webTempFiles();

    for (const purpose of ['import', 'view', 'play'] as const) {
      await expect(tempFiles.create(purpose, 'jpg')).rejects.toBeInstanceOf(TempFilesUnsupportedError);
    }
  });

  it('lists nothing', async () => {
    expect(await webTempFiles().list()).toEqual([]);
  });

  it('wipes without doing anything', async () => {
    await expect(webTempFiles().wipe()).resolves.toBeUndefined();
  });
});

describe('tmpDirectory', () => {
  it('is the per-account tmp directory under the app data root', () => {
    expect(tmpDirectory(ACCOUNT_HASH)).toBe(`kodakiko/${ACCOUNT_HASH}/tmp`);
  });
});
