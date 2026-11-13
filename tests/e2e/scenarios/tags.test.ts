import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SMALL_MEDIA_BYTES } from '../support/constants.ts';
import { outboxRows, readMetadata, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, startPair } from '../support/scenario.ts';

const ADDED_TAG = 'summer';

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

describe('tag edits on several entries', () => {
  it('scenario 10: device A tags 3 entries offline, and after sync device B sees the tag on all 3', async () => {
    const { a, b } = await startPair(harness);
    const entries = [];
    for (const tags of [['a'], ['b'], []]) {
      entries.push(
        await createSyncedEntry(a, {
          mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)],
          note: 'entry',
          tags,
        }),
      );
    }
    await syncUntilIdle(b);
    const ids = entries.map((entry) => entry.entryId);
    a.fetcher.setOffline(true);

    const result = await a.context.entries.addTagToMany(ids, ADDED_TAG);

    expect(result).toEqual({ changed: 3, skipped: 0 });
    expect(await outboxRows(a)).toHaveLength(3);
    a.fetcher.setOffline(false);
    await syncUntilIdle(a);
    await syncUntilIdle(b);

    for (const id of ids) {
      expect((await readMetadata(b, id)).tags).toContain(ADDED_TAG);
    }
    expect((await readMetadata(b, ids[0] ?? '')).tags).toEqual(['a', ADDED_TAG]);
  });
});
