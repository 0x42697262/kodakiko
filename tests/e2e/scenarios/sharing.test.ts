import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SMALL_MEDIA_BYTES } from '../support/constants.ts';
import { signUpDevice, type Device } from '../support/device.ts';
import { downloadAndDecrypt, readMetadata, sameBytes, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { createSyncedEntry, mediaIdAt } from '../support/scenario.ts';

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness({ env: { FLAG_SHARING: 'true', FLAG_PUBLIC_LINKS: 'false' } });
});

afterAll(async () => {
  await harness.close();
});

async function entryIdsOf(device: Device): Promise<string[]> {
  return (await device.context.rowStore.transaction((tx) => tx.entries.all())).map((entry) => entry.id).sort();
}

describe('sharing between two accounts', () => {
  it('A shares a journal with B; removal, trash, restore and leave reach B', async () => {
    const a = await signUpDevice(harness, 'A');
    const b = await signUpDevice(harness, 'B');
    await a.context.config.refresh();
    await b.context.config.refresh();
    await syncUntilIdle(a);
    await syncUntilIdle(b);
    const photo = randomMedia(SMALL_MEDIA_BYTES);
    const first = await createSyncedEntry(a, { mediaBytes: [photo], note: 'first', tags: ['shared'] });
    const second = await createSyncedEntry(a, { mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)], note: 'second', tags: [] });

    const journalId = await a.context.sharing.createJournal('Family');
    const added = await a.context.sharing.addEntries(journalId, [first.entryId, second.entryId]);
    expect(added.added).toEqual([first.entryId, second.entryId]);
    await a.context.sharing.share(journalId, await a.context.sharing.lookupRecipient(b.account.username));

    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([first.entryId, second.entryId].sort());
    expect((await readMetadata(b, first.entryId)).note).toBe('first');
    expect(sameBytes(await downloadAndDecrypt(b, first.entryId, mediaIdAt(first, 0), 'original'), photo)).toBe(true);

    await a.context.sharing.removeEntries(journalId, [second.entryId]);
    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([first.entryId]);

    await a.context.entries.trash([first.entryId]);
    await syncUntilIdle(a);
    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([]);

    await a.context.entries.restore([first.entryId]);
    await syncUntilIdle(a);
    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([first.entryId]);

    await b.context.sharing.leave(journalId);
    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([]);
    const collections = await b.context.rowStore.transaction((tx) => tx.collections.all());
    expect(collections.map((collection) => collection.id)).not.toContain(journalId);
  });
});
