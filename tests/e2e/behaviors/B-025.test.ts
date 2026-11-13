import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SMALL_MEDIA_BYTES } from '../support/constants.ts';
import { outboxRows, readEntry, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, startPair } from '../support/scenario.ts';
import { findServerEntry } from '../support/server-state.ts';

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

function smallEntry() {
  return { mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)], note: 'to trash', tags: [] };
}

describe('B-025 Given an entry', () => {
  it('when the user moves it to the trash and then restores it, then it leaves and returns on every device', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);
    expect((await readEntry(b, entry.entryId))?.trashedAt).toBeNull();

    await a.context.entries.trash([entry.entryId]);
    await syncUntilIdle(a);
    await syncUntilIdle(b);

    expect((await readEntry(a, entry.entryId))?.trashedAt).not.toBeNull();
    expect((await readEntry(b, entry.entryId))?.trashedAt).not.toBeNull();
    expect((await findServerEntry(harness, entry.entryId))?.trashedAt).not.toBeNull();

    await a.context.entries.restore([entry.entryId]);
    await syncUntilIdle(a);
    await syncUntilIdle(b);

    expect((await readEntry(a, entry.entryId))?.trashedAt).toBeNull();
    expect((await readEntry(b, entry.entryId))?.trashedAt).toBeNull();
    expect((await findServerEntry(harness, entry.entryId))?.trashedAt).toBeNull();
  });

  it('when the user trashes and restores it offline, then nothing is sent and the other device never sees a change', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);
    a.fetcher.setOffline(true);

    expect(await a.context.entries.trash([entry.entryId])).toEqual({ changed: 1, skipped: 0 });
    expect(await a.context.entries.restore([entry.entryId])).toEqual({ changed: 1, skipped: 0 });

    expect(await outboxRows(a)).toHaveLength(0);
    a.fetcher.setOffline(false);
    a.fetcher.clearRequests();
    await syncUntilIdle(a);
    expect(a.fetcher.requests().filter((request) => request.startsWith('POST'))).toEqual([]);
    expect((await findServerEntry(harness, entry.entryId))?.trashedAt).toBeNull();
    await syncUntilIdle(b);
    expect((await readEntry(b, entry.entryId))?.trashedAt).toBeNull();
  });

  it('when the user deletes a trashed entry permanently, then it disappears on every device', async () => {
    const { a, b } = await startPair(harness);
    const entry = await createSyncedEntry(a, smallEntry());
    await syncUntilIdle(b);
    await a.context.entries.trash([entry.entryId]);
    await syncUntilIdle(a);

    await a.context.entries.deletePermanently([entry.entryId]);
    await syncUntilIdle(a);
    await syncUntilIdle(b);

    expect(await readEntry(a, entry.entryId)).toBeNull();
    expect(await readEntry(b, entry.entryId)).toBeNull();
    expect(await findServerEntry(harness, entry.entryId)).toBeUndefined();
  });
});
