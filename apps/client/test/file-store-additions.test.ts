import { beforeEach, describe, expect, it } from 'vitest';
import { createMemoryFileStore } from '../src/adapters/memory/memory-file-store';
import type { FileStore } from '../src/adapters/filestore/filestore';

const ACCOUNT_KEY = 'host/user';
const HOUR_MS = 3_600_000;
const FIVE_MINUTES_MS = 300_000;
const START_MS = Date.parse('2026-11-12T10:00:00.000Z');
const OLD_UNKNOWN = 'AAAAAAAAAAAAAAAAAAAAAA';
const OLD_KNOWN = 'BBBBBBBBBBBBBBBBBBBBBB';
const NEW_UNKNOWN = 'CCCCCCCCCCCCCCCCCCCCCC';
const NEW_KNOWN = 'DDDDDDDDDDDDDDDDDDDDDD';
const MEDIA_ID = 'EEEEEEEEEEEEEEEEEEEEEE';
const OTHER_MEDIA_ID = 'FFFFFFFFFFFFFFFFFFFFFF';

let current: number;
let fileStore: FileStore;

beforeEach(async () => {
  current = START_MS;
  fileStore = createMemoryFileStore({ now: () => current });
  await fileStore.open(ACCOUNT_KEY);
});

async function* chunk(): AsyncGenerator<Uint8Array> {
  yield Uint8Array.of(1, 2, 3);
}

describe('FileStore.sweepOrphans (memory)', () => {
  it('deletes only files that are unknown and older than the age', async () => {
    await fileStore.writeObject(OLD_UNKNOWN, chunk());
    await fileStore.writeObject(OLD_KNOWN, chunk());
    current += 2 * HOUR_MS;
    await fileStore.writeObject(NEW_UNKNOWN, chunk());
    await fileStore.writeObject(NEW_KNOWN, chunk());
    current += FIVE_MINUTES_MS;

    await fileStore.sweepOrphans(new Set([OLD_KNOWN, NEW_KNOWN]), HOUR_MS);

    expect([...(await fileStore.listSizes()).keys()].sort()).toEqual(
      [OLD_KNOWN, NEW_UNKNOWN, NEW_KNOWN].sort(),
    );
  });

  it('deletes nothing when every file is known', async () => {
    await fileStore.writeObject(OLD_KNOWN, chunk());
    current += 2 * HOUR_MS;

    await fileStore.sweepOrphans(new Set([OLD_KNOWN]), HOUR_MS);

    expect((await fileStore.listSizes()).has(OLD_KNOWN)).toBe(true);
  });

  it('deletes nothing from an empty store', async () => {
    await expect(fileStore.sweepOrphans(new Set(), HOUR_MS)).resolves.not.toThrow();
    expect((await fileStore.listSizes()).size).toBe(0);
  });
});

describe('FileStore.sweepOrphans plain thumbnails (memory)', () => {
  const OLD_UNKNOWN_MEDIA = 'GGGGGGGGGGGGGGGGGGGGGG';
  const OLD_KNOWN_MEDIA = 'HHHHHHHHHHHHHHHHHHHHHH';
  const NEW_UNKNOWN_MEDIA = 'IIIIIIIIIIIIIIIIIIIIII';

  async function writeThumbnails(): Promise<void> {
    await fileStore.writePlainThumbnail(OLD_UNKNOWN_MEDIA, Uint8Array.of(1));
    await fileStore.writePlainThumbnail(OLD_KNOWN_MEDIA, Uint8Array.of(2));
    current += 2 * HOUR_MS;
    await fileStore.writePlainThumbnail(NEW_UNKNOWN_MEDIA, Uint8Array.of(3));
    current += FIVE_MINUTES_MS;
  }

  it('deletes only plain thumbnails whose media is unknown and older than the age', async () => {
    await writeThumbnails();

    await fileStore.sweepOrphans(new Set(), HOUR_MS, new Set([OLD_KNOWN_MEDIA]));

    expect(fileStore.plainThumbnailUri(OLD_UNKNOWN_MEDIA)).toBeNull();
    expect(fileStore.plainThumbnailUri(OLD_KNOWN_MEDIA)).not.toBeNull();
    expect(fileStore.plainThumbnailUri(NEW_UNKNOWN_MEDIA)).not.toBeNull();
  });

  it('leaves plain thumbnails alone when no known media ids are given', async () => {
    await writeThumbnails();

    await fileStore.sweepOrphans(new Set(), HOUR_MS);

    expect(fileStore.plainThumbnailUri(OLD_UNKNOWN_MEDIA)).not.toBeNull();
  });

  it('still sweeps objects in the same call', async () => {
    await fileStore.writeObject(OLD_UNKNOWN, chunk());
    await writeThumbnails();

    await fileStore.sweepOrphans(new Set(), HOUR_MS, new Set([OLD_KNOWN_MEDIA]));

    expect((await fileStore.listSizes()).has(OLD_UNKNOWN)).toBe(false);
  });
});

describe('FileStore plain thumbnails (memory)', () => {
  it('has no plain thumbnail before one is written', () => {
    expect(fileStore.plainThumbnailUri(MEDIA_ID)).toBeNull();
  });

  it('returns a URI for a written plain thumbnail and only for that media', async () => {
    await fileStore.writePlainThumbnail(MEDIA_ID, Uint8Array.of(1, 2, 3));

    expect(fileStore.plainThumbnailUri(MEDIA_ID)).toEqual(expect.any(String));
    expect(fileStore.plainThumbnailUri(OTHER_MEDIA_ID)).toBeNull();
  });

  it('forgets a deleted plain thumbnail', async () => {
    await fileStore.writePlainThumbnail(MEDIA_ID, Uint8Array.of(1, 2, 3));

    await fileStore.deletePlainThumbnail(MEDIA_ID);

    expect(fileStore.plainThumbnailUri(MEDIA_ID)).toBeNull();
  });

  it('deletes a plain thumbnail that does not exist without failing', async () => {
    await expect(fileStore.deletePlainThumbnail(MEDIA_ID)).resolves.not.toThrow();
  });

  it('does not list plain thumbnails as objects', async () => {
    await fileStore.writePlainThumbnail(MEDIA_ID, Uint8Array.of(1, 2, 3));

    expect((await fileStore.listSizes()).size).toBe(0);
  });
});
