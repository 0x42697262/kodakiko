import { beforeEach, describe, expect, it } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { createDecryptingVideoSource, NotEnoughSpaceError } from '../src/services/video/decrypting-video-source';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

let sodium: Sodium;
let harness: EntriesHarness;

type FakeFile = { bytes: Uint8Array[]; closed: boolean; deleted: boolean };

function fakeFiles() {
  const files = new Map<string, FakeFile>();
  let counter = 0;
  return {
    files,
    create: async (extension: string) => {
      counter += 1;
      const uri = `file:///tmp/play-${counter}.${extension}`;
      const file: FakeFile = { bytes: [], closed: false, deleted: false };
      files.set(uri, file);
      return {
        uri,
        write: async (bytes: Uint8Array) => {
          file.bytes.push(bytes.slice());
        },
        close: async () => {
          file.closed = true;
        },
        delete: async () => {
          file.deleted = true;
        },
      };
    },
  };
}

function joined(file: FakeFile | undefined): Uint8Array {
  const parts = file?.bytes ?? [];
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
});

function source(files: ReturnType<typeof fakeFiles>, freeBytes = Number.MAX_SAFE_INTEGER) {
  return createDecryptingVideoSource({
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    entryKeys: harness.entryKeys,
    sodium,
    readMetadata: (entryId) => harness.service.readMetadata(entryId),
    fetchOriginal: async () => undefined,
    createFile: files.create,
    freeSpaceBytes: async () => freeBytes,
    marginBytes: 100,
  });
}

describe('decrypting video source', () => {
  it('decrypts the original into a file and reports progress up to 1', async () => {
    const original = fakeJpeg(2_500_000, 9);
    const entryId = await harness.createEntry([original]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const files = fakeFiles();
    const progress: number[] = [];
    const prepared = await source(files).prepare({
      entryId,
      mediaId,
      onProgress: (fraction) => progress.push(fraction),
      signal: new AbortController().signal,
    });
    const file = files.files.get(prepared.uri);
    expect(file?.closed).toBe(true);
    expect(joined(file)).toEqual(original);
    expect(progress.at(-1)).toBe(1);
    await prepared.release();
    expect(file?.deleted).toBe(true);
  });

  it('abort deletes the partial file', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2_500_000, 9)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const files = fakeFiles();
    const controller = new AbortController();
    const preparing = source(files).prepare({
      entryId,
      mediaId,
      onProgress: (fraction) => {
        if (fraction > 0.5) controller.abort();
      },
      signal: controller.signal,
    });
    await expect(preparing).rejects.toThrow();
    const [file] = [...files.files.values()];
    expect(file?.deleted).toBe(true);
  });

  it('refuses when free space is below the size plus the margin', async () => {
    const entryId = await harness.createEntry([fakeJpeg(50_000, 9)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    const files = fakeFiles();
    await expect(
      source(files, 50_000).prepare({ entryId, mediaId, onProgress: () => undefined, signal: new AbortController().signal }),
    ).rejects.toBeInstanceOf(NotEnoughSpaceError);
    expect(files.files.size).toBe(0);
  });
});
