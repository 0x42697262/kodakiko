import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { byteSourceFromBytes, readImageMetadata, readVideoMetadata } from '@kodakiko/core';
import { ApiError, createHttpClient, createLinkSession } from '@kodakiko/client/testing';
import { parseLinkFragment } from '../../../apps/client/src/services/links/link-url.ts';
import {
  box,
  buildJpeg,
  buildTiff,
  concat,
  ftyp,
  insertExifIntoJpeg,
  moov,
  mp4Seconds,
  mvhd,
  seededBytes,
  u32be,
  udta,
  xyzBox,
} from '../../../packages/core/test/media/builders.ts';
import { signUpDevice, type Device } from '../support/device.ts';
import { readMetadata, syncUntilIdle } from '../support/entries.ts';
import { startHarness, type Harness } from '../support/harness.ts';
import { createSyncedEntry, mediaIdAt } from '../support/scenario.ts';

const MP4_EPOCH = 2_082_844_800;

let harness: Harness;

beforeAll(async () => {
  harness = await startHarness({ env: { FLAG_SHARING: 'false', FLAG_PUBLIC_LINKS: 'true' } });
});

afterAll(async () => {
  await harness.close();
});

function photoWithGps(): Uint8Array {
  return insertExifIntoJpeg(
    buildJpeg(),
    buildTiff({ dateTimeOriginal: '2026:05:01 10:00:00', offsetTimeOriginal: '+02:00', gps: { latitude: 48.8584, longitude: 2.2945 } }),
  );
}

function videoWithLocation(): Uint8Array {
  const payload = seededBytes(4096, 3);
  const head = ftyp('isom', ['isom', 'mp42']);
  const build = (offset: number) =>
    moov(
      mvhd({ version: 0, creationSeconds1904: mp4Seconds('2026-05-01T10:00:00.000Z', MP4_EPOCH) }),
      udta(xyzBox('+48.8584+002.2945/')),
      box('trak', box('mdia', box('minf', box('stbl', box('stco', concat([0, 0, 0, 0], u32be(1), u32be(offset))))))),
    );
  const offset = head.length + build(0).length + 8;
  return concat(head, build(offset), box('mdat', payload));
}

function viewer(url: string) {
  const parsed = new URL(url);
  const secret = parseLinkFragment(parsed.hash);
  if (secret === null) throw new Error('bad link');
  const linkId = parsed.pathname.split('/').at(-1) ?? '';
  const http = createHttpClient({
    baseUrl: async () => harness.baseUrl,
    sessionToken: async () => null,
    onSessionInvalid: () => undefined,
  });
  return createLinkSession({ sodium: harness.sodium, http, linkId, secret, origin: harness.baseUrl });
}

async function createLink(device: Device, entryIds: readonly string[], expiry: '7d' | 'one_view') {
  const mediaIds = new Set<string>();
  for (const entryId of entryIds) for (const media of (await readMetadata(device, entryId)).media) mediaIds.add(media.mediaId);
  return device.context.links.create({ entryIds, mediaIds, title: 'Holiday', expiry, includeNotes: true }, () => undefined, new AbortController().signal);
}

describe('public links end to end', () => {
  it('a viewer without an account decrypts stripped copies; one-view and revoke stop access', async () => {
    const a = await signUpDevice(harness, 'A');
    await a.context.config.refresh();
    await syncUntilIdle(a);
    const photoEntry = await createSyncedEntry(a, { mediaBytes: [photoWithGps()], note: 'beach', tags: ['trip'] });
    const videoEntry = await createSyncedEntry(a, { mediaBytes: [videoWithLocation()], note: 'waves', tags: [] });
    expect(mediaIdAt(photoEntry, 0)).toBeTruthy();

    const link = await createLink(a, [photoEntry.entryId, videoEntry.entryId], '7d');
    const session = viewer(link.url);
    const opened = await session.open();
    expect(opened.title).toBe('Holiday');
    expect(opened.items).toHaveLength(2);
    const photo = opened.items.find((item) => item.descriptor.kind === 'photo');
    const video = opened.items.find((item) => item.descriptor.kind === 'video');
    if (photo === undefined || video === undefined) throw new Error('missing items');
    expect(photo.note).toBe('beach');
    const photoMeta = await readImageMetadata(byteSourceFromBytes(await session.original(photo)), 'jpeg');
    expect(photoMeta.gps).toBeNull();
    expect(photoMeta.dateTimeOriginal).toBeNull();
    const videoMeta = await readVideoMetadata(byteSourceFromBytes(await session.original(video)));
    expect(videoMeta.location).toBeNull();
    expect(videoMeta.creationUtc).toBeNull();
    expect((await session.thumbnail(photo)).length).toBeGreaterThan(0);

    const once = await createLink(a, [photoEntry.entryId], 'one_view');
    await viewer(once.url).open();
    await expect(viewer(once.url).open()).rejects.toMatchObject({ code: 'LINK_EXPIRED' });

    await a.context.links.revoke(link.linkId);
    const afterRevoke = viewer(link.url).open();
    await expect(afterRevoke).rejects.toBeInstanceOf(ApiError);
    await expect(afterRevoke).rejects.toMatchObject({ code: 'LINK_TOKEN_INVALID' });

    const listed = await a.context.links.list();
    expect(listed.map((item) => item.linkId)).toEqual([once.linkId]);
    expect(listed[0]?.url).toBe(once.url);
  });
});
