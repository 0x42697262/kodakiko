import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { DIFF_PATH_FRAGMENT, SMALL_MEDIA_BYTES } from '../support/constants.ts';
import { deleteEntryLocally, entrySnapshots, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, startPair } from '../support/scenario.ts';
import { pruneTombstonesWithMovedClock } from '../support/server-state.ts';

const FROM_ZERO_QUERY = `${DIFF_PATH_FRAGMENT}0&`;

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

function smallEntry() {
  return { mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)], note: 'entry', tags: [] };
}

describe("B-063 Given the server's old deletion records pruned", () => {
  it('scenario 8: when a device with an old cursor syncs, then it resyncs and ends identical to the other device', async () => {
    const { a, b } = await startPair(harness);
    const kept = await createSyncedEntry(a, smallEntry());
    const doomed = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);
    expect((await entrySnapshots(b)).size).toBe(2);

    await deleteEntryLocally(a, doomed.entryId);
    await syncUntilIdle(a);
    await pruneTombstonesWithMovedClock(harness);
    b.fetcher.clearRequests();
    await syncUntilIdle(b);

    expect(b.fetcher.requests().some((request) => request.includes(FROM_ZERO_QUERY))).toBe(true);
    const onA = await entrySnapshots(a);
    expect([...onA.keys()]).toEqual([kept.entryId]);
    expect(await entrySnapshots(b)).toEqual(onA);
  });
});
