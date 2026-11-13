import { beforeEach, describe, expect, it } from 'vitest';
import { unzipSync, strFromU8 } from 'fflate';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { concat, ftyp, box, moov, mvhd, seededBytes } from '../../../packages/core/test/media/builders';
import { createFflateArchiveWriter } from '../src/adapters/archive/fflate-archive-writer';
import {
  createExportService,
  ExportCancelledError,
  type ExportDestination,
  type ExportProgress,
} from '../src/services/export/export-service';
import { createEntriesHarness, fakeJpeg, type EntriesHarness } from './support/entries-harness';

let sodium: Sodium;
let harness: EntriesHarness;
let paused: number;
let resumed: number;

type MemoryPart = { readonly name: string; chunks: Uint8Array[]; closed: boolean; aborted: boolean };

function memoryDestination(partMaxBytes: number, onWrite?: (part: MemoryPart) => void) {
  const parts: MemoryPart[] = [];
  const destination: ExportDestination = {
    partMaxBytes,
    createPart: async (name) => {
      const part: MemoryPart = { name, chunks: [], closed: false, aborted: false };
      parts.push(part);
      return {
        write: async (bytes) => {
          part.chunks.push(bytes.slice());
          onWrite?.(part);
        },
        close: async () => {
          part.closed = true;
        },
        abort: async () => {
          part.aborted = true;
        },
      };
    },
  };
  return { destination, parts };
}

function service(order?: () => Promise<readonly string[]>) {
  return createExportService({
    entryIds: order ?? (async () => (await harness.fixture.rowStore.transaction((tx) => tx.entries.all())).map((row) => row.id)),
    readMetadata: (entryId) => harness.service.readMetadata(entryId),
    entryKeys: harness.entryKeys,
    rowStore: harness.fixture.rowStore,
    fileStore: harness.fixture.fileStore,
    fetchObject: async () => undefined,
    enforceCap: async () => undefined,
    sodium,
    autoLock: {
      pause: () => {
        paused += 1;
      },
      resume: () => {
        resumed += 1;
      },
    },
    clock: harness.clock,
    createArchiveWriter: createFflateArchiveWriter,
  });
}

function tinyMp4(): Uint8Array {
  return concat(ftyp('isom', ['isom']), moov(mvhd({ version: 0, creationSeconds1904: 0 })), box('mdat', seededBytes(3000, 8)));
}

beforeEach(async () => {
  sodium = await createNodeSodium();
  harness = await createEntriesHarness(sodium);
  paused = 0;
  resumed = 0;
});

describe('export service', () => {
  it('writes every entry as a folder with entry.md and the original media bytes', async () => {
    const photo = fakeJpeg(4000, 1);
    const video = tinyMp4();
    const first = await harness.createEntry([photo, video], { note: 'beach day', tags: ['sea'] });
    harness.clock.advance(60_000);
    await harness.createEntry([fakeJpeg(2000, 2)], { note: 'second' });
    harness.clock.advance(60_000);
    await harness.createEntry([fakeJpeg(3000, 3)], { note: 'third' });
    const { destination, parts } = memoryDestination(1_073_741_824);
    const progress: ExportProgress[] = [];

    const summary = await service().run(destination, (step) => progress.push(step));

    expect(summary).toMatchObject({ parts: 1, entries: 3, mediaFiles: 4, skippedMissing: 0, skippedUnreadable: 0, skippedTooLarge: 0 });
    expect(parts).toHaveLength(1);
    expect(parts[0]?.closed).toBe(true);
    expect(parts[0]?.name).toMatch(/^kodakiko-export-\d{4}-\d{2}-\d{2}-part01\.zip$/);
    const files = unzipSync(concat(...(parts[0]?.chunks ?? [])));
    const names = Object.keys(files);
    expect(names).toContain('README.txt');
    const notes = names.filter((name) => name.endsWith('/entry.md'));
    expect(notes).toHaveLength(3);
    const firstFolder = notes.map((name) => name.slice(0, -'entry.md'.length)).find((folder) => strFromU8(files[`${folder}entry.md`] ?? new Uint8Array()).includes('beach day'));
    expect(firstFolder).toBeDefined();
    expect(files[`${firstFolder}001.jpg`]).toEqual(photo);
    expect(files[`${firstFolder}002.mp4`]).toEqual(video);
    expect(strFromU8(files[`${firstFolder}entry.md`] ?? new Uint8Array())).toContain('tags: [sea]');
    expect(firstFolder?.endsWith(`_${first.slice(0, 8)}/`)).toBe(true);
    expect(progress.at(-1)?.phase).toBe('done');
    expect(paused).toBe(1);
    expect(resumed).toBe(1);
  });

  it('splits into two valid zips when the part limit is small', async () => {
    await harness.createEntry([fakeJpeg(30_000, 1)]);
    harness.clock.advance(60_000);
    await harness.createEntry([fakeJpeg(30_000, 2)]);
    const { destination, parts } = memoryDestination(40_000);
    const summary = await service().run(destination, () => undefined);
    expect(summary.parts).toBe(2);
    expect(parts).toHaveLength(2);
    for (const part of parts) expect(Object.keys(unzipSync(concat(...part.chunks))).some((name) => name.endsWith('.jpg'))).toBe(true);
  });

  it('cancel aborts the unfinished part and keeps the finished ones', async () => {
    await harness.createEntry([fakeJpeg(30_000, 1)]);
    harness.clock.advance(60_000);
    await harness.createEntry([fakeJpeg(30_000, 2)]);
    const exporter = service();
    const { destination, parts } = memoryDestination(40_000, (part) => {
      if (part.name.endsWith('part02.zip')) exporter.cancel();
    });
    await expect(exporter.run(destination, () => undefined)).rejects.toBeInstanceOf(ExportCancelledError);
    expect(parts[0]?.closed).toBe(true);
    expect(parts[1]?.aborted).toBe(true);
    expect(resumed).toBe(1);
  });

  it('skips media whose original is missing and lists them in MISSING.txt', async () => {
    const entryId = await harness.createEntry([fakeJpeg(2000, 4)]);
    const mediaId = (await harness.service.readMetadata(entryId)).media[0]?.mediaId ?? '';
    await harness.fixture.rowStore.transaction(async (tx) => {
      for (const row of await tx.objects.byMedia(mediaId)) if (row.variant === 'original') await tx.objects.put({ ...row, localState: 'unavailable' });
    });
    const { destination, parts } = memoryDestination(1_073_741_824);
    const summary = await service().run(destination, () => undefined);
    expect(summary.skippedMissing).toBe(1);
    const files = unzipSync(concat(...(parts[0]?.chunks ?? [])));
    expect(strFromU8(files['MISSING.txt'] ?? new Uint8Array())).toContain('001.jpg');
  });
});
