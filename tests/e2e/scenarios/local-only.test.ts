import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { SMALL_MEDIA_BYTES } from '../support/constants.ts';
import { setUpLocalDevice, signInDevice, type Device } from '../support/device.ts';
import { createEntryLocally, downloadAndDecrypt, readMetadata, sameBytes, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { randomMedia } from '../support/media.ts';
import { mediaIdAt } from '../support/scenario.ts';

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness();
});

afterAll(async () => {
  await harness.close();
});

async function entryIdsOf(device: Device): Promise<string[]> {
  return (await device.context.rowStore.transaction((tx) => tx.entries.all())).map((entry) => entry.id).sort();
}

describe('local-only mode', () => {
  it('a journal kept without a server uploads after connecting and opens on a second device', async () => {
    const a = await setUpLocalDevice(harness, 'A');
    const photo = randomMedia(SMALL_MEDIA_BYTES);
    const first = await createEntryLocally(a, { mediaBytes: [photo], note: 'kept offline', tags: ['local'] });
    const second = await createEntryLocally(a, { mediaBytes: [randomMedia(SMALL_MEDIA_BYTES)], note: 'second', tags: [] });
    await a.context.sync.syncNow();
    expect(a.fetcher.requests()).toEqual([]);

    await a.context.auth.connectServer({
      serverInput: harness.baseUrl,
      inviteToken: null,
      username: a.account.username,
      password: a.account.password,
    });
    expect(a.context.auth.mode.get()).toBe('server');
    await syncUntilIdle(a);

    const b = await signInDevice(harness, 'B', a.account);
    expect(b.userId).toBe(a.userId);
    await syncUntilIdle(b);
    expect(await entryIdsOf(b)).toEqual([first.entryId, second.entryId].sort());
    expect((await readMetadata(b, first.entryId)).note).toBe('kept offline');
    expect(sameBytes(await downloadAndDecrypt(b, first.entryId, mediaIdAt(first, 0), 'original'), photo)).toBe(true);
  });
});
