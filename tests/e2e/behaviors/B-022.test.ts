import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { FIRST_REVISION, SMALL_MEDIA_BYTES } from '../support/constants.ts';
import {
  createEntryLocally,
  outboxRows,
  readEntry,
  readMetadata,
  syncUntilIdle,
} from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { startPair } from '../support/scenario.ts';
import { findServerEntry } from '../support/server-state.ts';

const NOTE = 'Picnic by the lake';
const TAGS = ['lake', 'family'];

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

describe('B-022 Given a tray of media', () => {
  it('when the user adds a note and tags and saves while offline, then the entry appears at once and syncs when online', async () => {
    const { a, b } = await startPair(harness);
    const changed: string[] = [];
    a.context.events.on('entriesChanged', (ids) => changed.push(...ids));
    a.fetcher.setOffline(true);

    const entry = await createEntryLocally(a, {
      mediaBytes: [randomMedia(SMALL_MEDIA_BYTES), randomMedia(SMALL_MEDIA_BYTES)],
      note: NOTE,
      tags: TAGS,
    });

    expect(changed).toContain(entry.entryId);
    expect(await readEntry(a, entry.entryId)).toMatchObject({
      localState: 'pending_create',
      baseRevision: 0,
      trashedAt: null,
    });
    const local = await readMetadata(a, entry.entryId);
    expect(local.note).toBe(NOTE);
    expect(local.tags).toEqual([...TAGS].sort());
    expect(local.media).toHaveLength(2);
    expect(await findServerEntry(harness, entry.entryId)).toBeUndefined();

    await a.context.sync.syncNow();
    expect(a.context.sync.status().offline).toBe(true);
    expect(await findServerEntry(harness, entry.entryId)).toBeUndefined();
    expect(await readEntry(a, entry.entryId)).toMatchObject({ localState: 'pending_create' });

    a.fetcher.setOffline(false);
    await syncUntilIdle(a);

    expect(await outboxRows(a)).toHaveLength(0);
    expect(await readEntry(a, entry.entryId)).toMatchObject({
      localState: 'synced',
      revision: FIRST_REVISION,
    });
    expect(await findServerEntry(harness, entry.entryId)).toMatchObject({
      revision: FIRST_REVISION,
      trashedAt: null,
    });

    await syncUntilIdle(b);
    const onB = await readMetadata(b, entry.entryId);
    expect(onB.note).toBe(NOTE);
    expect(onB.tags).toEqual([...TAGS].sort());
    expect(onB.media.map((item) => item.mediaId)).toEqual([...entry.mediaIds]);
  });
});
