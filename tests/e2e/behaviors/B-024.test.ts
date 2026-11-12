import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  EARLIER_EDIT_UTC,
  LATER_EDIT_UTC,
  SMALL_MEDIA_BYTES,
  THIRD_REVISION,
} from '../support/constants.ts';
import type { Device } from '../support/device.ts';
import { editEntryLocally, readEntry, readMetadata, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, startPair } from '../support/scenario.ts';

const BASE_TAG = 'family';
const ADDED_TAG = 'beach';
const MERGED_TAGS = [ADDED_TAG, BASE_TAG];

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

async function expectConverged(
  devices: readonly Device[],
  entryId: string,
  expectedNote: string,
): Promise<void> {
  for (const device of devices) {
    expect((await readEntry(device, entryId))?.revision).toBe(THIRD_REVISION);
    const metadata = await readMetadata(device, entryId);
    expect(metadata.note).toBe(expectedNote);
    expect(metadata.tags).toEqual(MERGED_TAGS);
  }
}

describe('B-024 Given the same entry edited on two offline devices', () => {
  it('scenario 2: when both sync, then A\'s note and B\'s tag both survive at revision 3', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, {
      mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)],
      note: 'base note',
      tags: [BASE_TAG],
    });
    await syncUntilIdle(b);
    await editEntryLocally(
      a,
      entry.entryId,
      (metadata) => ({ ...metadata, note: 'note from A' }),
      EARLIER_EDIT_UTC,
    );
    await editEntryLocally(
      b,
      entry.entryId,
      (metadata) => ({ ...metadata, tags: [...metadata.tags, ADDED_TAG] }),
      LATER_EDIT_UTC,
    );

    await syncUntilIdle(a);
    await syncUntilIdle(b);
    await syncUntilIdle(a);

    await expectConverged([a, b], entry.entryId, 'note from A');
  });

  it.each([
    { name: 'B edited later', aEditedAt: EARLIER_EDIT_UTC, bEditedAt: LATER_EDIT_UTC, note: 'note from B' },
    { name: 'A edited later', aEditedAt: LATER_EDIT_UTC, bEditedAt: EARLIER_EDIT_UTC, note: 'note from A' },
  ])(
    'when both change the note, then the later edit wins and the tags are merged ($name)',
    async ({ aEditedAt, bEditedAt, note }) => {
      const { a, b } = await startPair(harness);
      const entry = await createSyncedEntry(a, {
        mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)],
        note: 'base note',
        tags: [BASE_TAG],
      });
      await syncUntilIdle(b);
      await editEntryLocally(
        a,
        entry.entryId,
        (metadata) => ({ ...metadata, note: 'note from A' }),
        aEditedAt,
      );
      await editEntryLocally(
        b,
        entry.entryId,
        (metadata) => ({ ...metadata, note: 'note from B', tags: [...metadata.tags, ADDED_TAG] }),
        bEditedAt,
      );

      await syncUntilIdle(a);
      await syncUntilIdle(b);
      await syncUntilIdle(a);

      await expectConverged([a, b], entry.entryId, note);
    },
  );
});
