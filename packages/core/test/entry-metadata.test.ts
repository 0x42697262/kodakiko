import { describe, expect, it } from 'vitest';
import { EntryMetadataV1, type MediaDescriptorV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  InvalidEditError,
  applyEntryEdit,
  buildNewEntryMetadata,
  type EntryDraft,
  type ResolvedTime,
} from '../src/index.ts';

type Descriptor = z.infer<typeof MediaDescriptorV1>;
type Metadata = z.infer<typeof EntryMetadataV1>;

const NOW = '2026-11-11T12:00:00.000Z';
const LATER = '2026-11-12T08:00:00.000Z';
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const BERLIN = { latitude: 52.52, longitude: 13.405 };
const DEVICE_POINT = { latitude: 1.5, longitude: 2.5 };
const DEVICE_ACCURACY_METERS = 12;
const OFFSET_MINUTES = 120;

function descriptor(mediaId: string): Descriptor {
  return {
    mediaId,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width: 100,
    height: 80,
    durationMs: null,
    originalPlaintextBytes: 10,
    thumbnail: { width: 10, height: 8, mimeType: 'image/webp', plaintextBytes: 5 },
    capturedAtUtc: null,
    originalFileName: null,
  } as Descriptor;
}

function time(capturedAtUtc: string, utcOffsetMinutes = 0): ResolvedTime {
  return { capturedAtUtc, utcOffsetMinutes, source: 'exif' };
}

const IDS = ['AAAAAAAAAAAAAAAAAAAAAA', 'BBBBBBBBBBBBBBBBBBBBBB', 'CCCCCCCCCCCCCCCCCCCCCC'];

function draft(overrides: Partial<EntryDraft> = {}): EntryDraft {
  return {
    media: [descriptor(IDS[0] ?? ''), descriptor(IDS[1] ?? '')],
    times: [time('2026-05-01T08:00:00.000Z', OFFSET_MINUTES), time('2026-05-01T07:00:00.000Z', OFFSET_MINUTES)],
    note: 'Picnic',
    tags: ['b', 'a'],
    mediaLocations: [null, null],
    deviceLocation: null,
    timeZone: 'Europe/Paris',
    nowUtc: NOW,
    ...overrides,
  };
}

function existing(overrides: Partial<Metadata> = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-05-01T08:00:00.000Z',
    utcOffsetMinutes: OFFSET_MINUTES,
    timeZone: 'Europe/Paris',
    note: 'before',
    tags: ['a'],
    location: null,
    media: IDS.map(descriptor),
    createdAtUtc: NOW,
    editedAtUtc: NOW,
    ...overrides,
  };
}

describe('buildNewEntryMetadata', () => {
  it('takes the earliest media time with its offset', () => {
    const metadata = buildNewEntryMetadata(draft());
    expect(metadata.takenAtUtc).toBe('2026-05-01T07:00:00.000Z');
    expect(metadata.utcOffsetMinutes).toBe(OFFSET_MINUTES);
  });

  it('sets the creation and edit times to now, the note and sorted tags', () => {
    const metadata = buildNewEntryMetadata(draft());
    expect(metadata).toMatchObject({
      schemaVersion: 1,
      createdAtUtc: NOW,
      editedAtUtc: NOW,
      note: 'Picnic',
      tags: ['a', 'b'],
      timeZone: 'Europe/Paris',
    });
    expect(metadata.media.map((item) => item.mediaId)).toEqual([IDS[0], IDS[1]]);
  });

  it('uses the location of the earliest media that has one, as source exif', () => {
    const metadata = buildNewEntryMetadata(draft({ mediaLocations: [PARIS, BERLIN] }));
    expect(metadata.location).toEqual({ ...BERLIN, accuracyMeters: null, source: 'exif' });
  });

  it('skips media without a location even when they are the earliest', () => {
    const metadata = buildNewEntryMetadata(draft({ mediaLocations: [PARIS, null] }));
    expect(metadata.location).toEqual({ ...PARIS, accuracyMeters: null, source: 'exif' });
  });

  it('prefers a media location over the device location', () => {
    const metadata = buildNewEntryMetadata(
      draft({
        mediaLocations: [PARIS, null],
        deviceLocation: { point: DEVICE_POINT, accuracyMeters: DEVICE_ACCURACY_METERS },
      }),
    );
    expect(metadata.location?.source).toBe('exif');
  });

  it('falls back to the device location with its accuracy, source device', () => {
    const metadata = buildNewEntryMetadata(
      draft({ deviceLocation: { point: DEVICE_POINT, accuracyMeters: DEVICE_ACCURACY_METERS } }),
    );
    expect(metadata.location).toEqual({
      ...DEVICE_POINT,
      accuracyMeters: DEVICE_ACCURACY_METERS,
      source: 'device',
    });
  });

  it('has no location when there is neither', () => {
    expect(buildNewEntryMetadata(draft()).location).toBeNull();
  });

  it('returns metadata that the contract schema accepts', () => {
    expect(() => EntryMetadataV1.parse(buildNewEntryMetadata(draft()))).not.toThrow();
  });

  it('normalizes, deduplicates and sorts the tags', () => {
    const metadata = buildNewEntryMetadata(draft({ tags: [' Beach ', 'family', 'BEACH', 'Road   Trip'] }));
    expect(metadata.tags).toEqual(['beach', 'family', 'road trip']);
  });

  it.each([
    { name: 'an empty tag', tags: ['ok', '   '] },
    { name: 'a tag over the length limit', tags: ['x'.repeat(65)] },
  ])('refuses $name', ({ tags }) => {
    expect(() => buildNewEntryMetadata(draft({ tags }))).toThrow(InvalidEditError);
  });
});

describe('applyEntryEdit', () => {
  it('changes only the fields that are present and stamps the edit time', () => {
    const next = applyEntryEdit(existing(), { note: 'after' }, LATER);
    expect(next).toEqual({ ...existing(), note: 'after', editedAtUtc: LATER });
  });

  it('replaces the tags', () => {
    expect(applyEntryEdit(existing(), { tags: ['a', 'z'] }, LATER).tags).toEqual(['a', 'z']);
  });

  it('replaces the time but keeps the zone label', () => {
    const next = applyEntryEdit(
      existing(),
      { time: { takenAtUtc: '2026-05-02T10:00:00.000Z', utcOffsetMinutes: OFFSET_MINUTES } },
      LATER,
    );
    expect(next).toMatchObject({
      takenAtUtc: '2026-05-02T10:00:00.000Z',
      utcOffsetMinutes: OFFSET_MINUTES,
      timeZone: 'Europe/Paris',
    });
  });

  it('normalizes, deduplicates and sorts edited tags', () => {
    expect(applyEntryEdit(existing(), { tags: ['Zoo', 'apple', ' ZOO '] }, LATER).tags).toEqual([
      'apple',
      'zoo',
    ]);
  });

  it.each([
    { name: 'an empty tag', tags: [''] },
    { name: 'a tag over the length limit', tags: ['y'.repeat(65)] },
  ])('refuses an edit with $name', ({ tags }) => {
    expect(() => applyEntryEdit(existing(), { tags }, LATER)).toThrow(InvalidEditError);
  });

  it('sets and clears the location', () => {
    const place = { ...PARIS, accuracyMeters: null, source: 'exif' as const };
    const set = applyEntryEdit(existing(), { location: place }, LATER);
    expect(set.location).toEqual(place);
    expect(applyEntryEdit(set, { location: null }, LATER).location).toBeNull();
  });

  it('reorders the media by the given permutation', () => {
    const next = applyEntryEdit(existing(), { mediaOrder: [IDS[2] ?? '', IDS[0] ?? '', IDS[1] ?? ''] }, LATER);
    expect(next.media.map((item) => item.mediaId)).toEqual([IDS[2], IDS[0], IDS[1]]);
  });

  it.each([
    { name: 'an id that is missing', order: [IDS[0] ?? '', IDS[1] ?? ''] },
    { name: 'an unknown id', order: [IDS[0] ?? '', IDS[1] ?? '', 'DDDDDDDDDDDDDDDDDDDDDD'] },
    { name: 'a repeated id', order: [IDS[0] ?? '', IDS[0] ?? '', IDS[1] ?? ''] },
    { name: 'an extra id', order: [...IDS, IDS[0] ?? ''] },
    { name: 'no ids', order: [] },
  ])('refuses a media order with $name', ({ order }) => {
    expect(() => applyEntryEdit(existing(), { mediaOrder: order }, LATER)).toThrow(InvalidEditError);
  });

  it('appends added media after the current ones', () => {
    const added = descriptor('EEEEEEEEEEEEEEEEEEEEEE');
    const next = applyEntryEdit(existing(), { addMedia: [added] }, LATER);
    expect(next.media.map((item) => item.mediaId)).toEqual([...IDS, 'EEEEEEEEEEEEEEEEEEEEEE']);
  });

  it('removes one media item and keeps the rest in order', () => {
    const next = applyEntryEdit(existing(), { removeMediaId: IDS[1] ?? '' }, LATER);
    expect(next.media.map((item) => item.mediaId)).toEqual([IDS[0], IDS[2]]);
  });

  it('refuses to remove the last media item', () => {
    const single = existing({ media: [descriptor(IDS[0] ?? '')] });
    expect(() => applyEntryEdit(single, { removeMediaId: IDS[0] ?? '' }, LATER)).toThrow(
      InvalidEditError,
    );
  });

  it('refuses to remove a media item that is not in the entry', () => {
    expect(() =>
      applyEntryEdit(existing(), { removeMediaId: 'DDDDDDDDDDDDDDDDDDDDDD' }, LATER),
    ).toThrow(InvalidEditError);
  });

  it('does not change the input', () => {
    const current = existing();
    applyEntryEdit(current, { note: 'x', tags: [], removeMediaId: IDS[0] ?? '' }, LATER);
    expect(current).toEqual(existing());
  });

  it('returns metadata that the contract schema accepts', () => {
    expect(() =>
      EntryMetadataV1.parse(applyEntryEdit(existing(), { note: 'x', tags: ['q'] }, LATER)),
    ).not.toThrow();
  });
});
