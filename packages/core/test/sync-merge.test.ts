import { describe, expect, it } from 'vitest';
import type { z } from 'zod';
import type { EntryMetadataV1, Id, MediaDescriptorV1 } from '@kodakiko/contract';
import { mergeMetadata, metadataEquivalent } from '../src/index.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;
type Patch = Partial<Metadata>;

const CREATED = '2026-01-01T00:00:00.000Z';
const OLDER = '2026-02-01T00:00:00.000Z';
const NEWER = '2026-03-01T00:00:00.000Z';
const NOW = '2026-04-01T00:00:00.000Z';
const TAKEN = '2026-01-05T10:00:00.000Z';
const TAKEN_LOCAL = '2026-01-06T10:00:00.000Z';
const TAKEN_SERVER = '2026-01-07T10:00:00.000Z';

const ID_ONE = 'mmmmmmmmmmmmmmmmmmmm01' as Id;
const ID_TWO = 'mmmmmmmmmmmmmmmmmmmm02' as Id;
const ID_THREE = 'mmmmmmmmmmmmmmmmmmmm03' as Id;
const ID_FOUR = 'mmmmmmmmmmmmmmmmmmmm04' as Id;

const PLACE = { latitude: 48.85, longitude: 2.35, accuracyMeters: 10, source: 'device' } as const;
const OTHER_PLACE = { latitude: 52.5, longitude: 13.4, accuracyMeters: null, source: 'exif' } as const;

function descriptor(mediaId: Id, width = 100): Descriptor {
  return {
    mediaId,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width,
    height: 80,
    durationMs: null,
    originalPlaintextBytes: 1000,
    thumbnail: { width: 10, height: 8, mimeType: 'image/webp', plaintextBytes: 64 },
    capturedAtUtc: null,
    originalFileName: null,
  };
}

function metadata(patch: Patch = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: TAKEN,
    utcOffsetMinutes: 0,
    timeZone: null,
    note: 'base',
    tags: ['a', 'b'],
    location: null,
    media: [descriptor(ID_ONE), descriptor(ID_TWO)],
    createdAtUtc: CREATED,
    editedAtUtc: CREATED,
    ...patch,
  };
}

type MergeCase = {
  readonly name: string;
  readonly ancestor?: Patch;
  readonly local?: Patch;
  readonly server?: Patch;
  readonly expected: Patch;
};

function merged(testCase: MergeCase): Metadata {
  const ancestor = metadata(testCase.ancestor);
  return mergeMetadata(
    ancestor,
    metadata({ ...testCase.ancestor, ...testCase.local }),
    metadata({ ...testCase.ancestor, ...testCase.server }),
    NOW,
  );
}

const NOTE_CASES: readonly MergeCase[] = [
  { name: 'note unchanged on both sides keeps the ancestor note', expected: { note: 'base' } },
  {
    name: 'note changed only locally takes the local note',
    local: { note: 'mine', editedAtUtc: NEWER },
    server: { editedAtUtc: OLDER },
    expected: { note: 'mine' },
  },
  {
    name: 'note changed only on the server takes the server note even when local edited later',
    local: { editedAtUtc: NEWER },
    server: { note: 'theirs', editedAtUtc: OLDER },
    expected: { note: 'theirs' },
  },
  {
    name: 'note changed on both sides takes the local note when local edited later',
    local: { note: 'mine', editedAtUtc: NEWER },
    server: { note: 'theirs', editedAtUtc: OLDER },
    expected: { note: 'mine' },
  },
  {
    name: 'note changed on both sides takes the server note when the server edited later',
    local: { note: 'mine', editedAtUtc: OLDER },
    server: { note: 'theirs', editedAtUtc: NEWER },
    expected: { note: 'theirs' },
  },
  {
    name: 'note changed on both sides with equal edit times takes the local note',
    local: { note: 'mine', editedAtUtc: OLDER },
    server: { note: 'theirs', editedAtUtc: OLDER },
    expected: { note: 'mine' },
  },
  {
    name: 'note changed to the same text on both sides keeps that text',
    local: { note: 'same', editedAtUtc: OLDER },
    server: { note: 'same', editedAtUtc: NEWER },
    expected: { note: 'same' },
  },
];

const TAG_CASES: readonly MergeCase[] = [
  { name: 'tags unchanged stay the same', expected: { tags: ['a', 'b'] } },
  { name: 'a tag added locally is kept', local: { tags: ['a', 'b', 'c'] }, expected: { tags: ['a', 'b', 'c'] } },
  { name: 'a tag added on the server is kept', server: { tags: ['a', 'b', 'c'] }, expected: { tags: ['a', 'b', 'c'] } },
  {
    name: 'different tags added on both sides are united',
    local: { tags: ['a', 'b', 'c'] },
    server: { tags: ['a', 'b', 'd'] },
    expected: { tags: ['a', 'b', 'c', 'd'] },
  },
  { name: 'a tag removed locally stays removed', local: { tags: ['a'] }, expected: { tags: ['a'] } },
  { name: 'a tag removed on the server stays removed', server: { tags: ['b'] }, expected: { tags: ['b'] } },
  {
    name: 'different tags removed on both sides are both removed',
    local: { tags: ['a'] },
    server: { tags: ['b'] },
    expected: { tags: [] },
  },
  {
    name: 'a removal on one side and an addition on the other both apply',
    local: { tags: ['a'] },
    server: { tags: ['a', 'b', 'c'] },
    expected: { tags: ['a', 'c'] },
  },
  {
    name: 'the same tag added on both sides appears once',
    local: { tags: ['a', 'b', 'c'] },
    server: { tags: ['a', 'b', 'c'] },
    expected: { tags: ['a', 'b', 'c'] },
  },
  {
    name: 'tags are normalized before comparison',
    local: { tags: ['a', 'b', '  Family '] },
    server: { tags: ['a', 'b', 'family'] },
    expected: { tags: ['a', 'b', 'family'] },
  },
  {
    name: 'a tag removed on one side stays removed when the other side only changes its case',
    local: { tags: ['a'] },
    server: { tags: ['A', 'b'] },
    expected: { tags: ['a'] },
  },
  {
    name: 'the result is sorted',
    local: { tags: ['a', 'b', 'zeta'] },
    server: { tags: ['a', 'b', 'alpha'] },
    expected: { tags: ['a', 'alpha', 'b', 'zeta'] },
  },
  {
    name: 'tags that normalize to nothing are dropped',
    local: { tags: ['a', 'b', '   '] },
    expected: { tags: ['a', 'b'] },
  },
];

const TIME_CASES: readonly MergeCase[] = [
  {
    name: 'time unchanged keeps the ancestor time',
    expected: { takenAtUtc: TAKEN, utcOffsetMinutes: 0, timeZone: null },
  },
  {
    name: 'time changed only locally takes the whole local unit',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, timeZone: 'Europe/Paris' },
    expected: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, timeZone: 'Europe/Paris' },
  },
  {
    name: 'time changed only on the server takes the whole server unit',
    server: { takenAtUtc: TAKEN_SERVER, utcOffsetMinutes: 120, timeZone: 'Europe/Athens' },
    expected: { takenAtUtc: TAKEN_SERVER, utcOffsetMinutes: 120, timeZone: 'Europe/Athens' },
  },
  {
    name: 'time changed on both sides takes the local unit when local edited later',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, editedAtUtc: NEWER },
    server: { takenAtUtc: TAKEN_SERVER, timeZone: 'Europe/Athens', editedAtUtc: OLDER },
    expected: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, timeZone: null },
  },
  {
    name: 'time changed on both sides takes the server unit when the server edited later',
    local: { takenAtUtc: TAKEN_LOCAL, utcOffsetMinutes: 60, editedAtUtc: OLDER },
    server: { takenAtUtc: TAKEN_SERVER, timeZone: 'Europe/Athens', editedAtUtc: NEWER },
    expected: { takenAtUtc: TAKEN_SERVER, utcOffsetMinutes: 0, timeZone: 'Europe/Athens' },
  },
  {
    name: 'a change of only the time zone moves the whole unit',
    server: { timeZone: 'Europe/Athens' },
    expected: { takenAtUtc: TAKEN, utcOffsetMinutes: 0, timeZone: 'Europe/Athens' },
  },
];

const LOCATION_CASES: readonly MergeCase[] = [
  { name: 'location unchanged stays empty', expected: { location: null } },
  { name: 'location set only locally is kept', local: { location: PLACE }, expected: { location: PLACE } },
  { name: 'location set only on the server is kept', server: { location: PLACE }, expected: { location: PLACE } },
  {
    name: 'location changed on both sides takes the server place when the server edited later',
    local: { location: PLACE, editedAtUtc: OLDER },
    server: { location: OTHER_PLACE, editedAtUtc: NEWER },
    expected: { location: OTHER_PLACE },
  },
  {
    name: 'location changed on both sides takes the local place when local edited later',
    local: { location: PLACE, editedAtUtc: NEWER },
    server: { location: OTHER_PLACE, editedAtUtc: OLDER },
    expected: { location: PLACE },
  },
  {
    name: 'location changed on both sides with equal times takes the local place',
    local: { location: PLACE, editedAtUtc: OLDER },
    server: { location: OTHER_PLACE, editedAtUtc: OLDER },
    expected: { location: PLACE },
  },
  {
    name: 'location cleared locally stays cleared',
    ancestor: { location: PLACE },
    local: { location: null },
    expected: { location: null },
  },
];

const MEDIA_CASES: readonly MergeCase[] = [
  { name: 'media unchanged stays the same', expected: { media: [descriptor(ID_ONE), descriptor(ID_TWO)] } },
  {
    name: 'media removed locally stays removed',
    local: { media: [descriptor(ID_ONE)] },
    expected: { media: [descriptor(ID_ONE)] },
  },
  {
    name: 'media removed on the server stays removed',
    server: { media: [descriptor(ID_TWO)] },
    expected: { media: [descriptor(ID_TWO)] },
  },
  {
    name: 'different media removed on both sides are both removed',
    ancestor: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
    local: { media: [descriptor(ID_TWO), descriptor(ID_THREE)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_THREE)] },
    expected: { media: [descriptor(ID_THREE)] },
  },
  {
    name: 'media added on the server is appended after the local order',
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
    expected: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
  },
  {
    name: 'media added on both sides keeps local additions in place and appends server additions',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_THREE)] },
    expected: { media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR), descriptor(ID_THREE)] },
  },
  {
    name: 'several server additions keep the server order',
    server: {
      media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR), descriptor(ID_THREE)],
    },
    expected: {
      media: [descriptor(ID_ONE), descriptor(ID_TWO), descriptor(ID_FOUR), descriptor(ID_THREE)],
    },
  },
  {
    name: 'media reordered locally keeps the local order',
    local: { media: [descriptor(ID_TWO), descriptor(ID_ONE)] },
    expected: { media: [descriptor(ID_TWO), descriptor(ID_ONE)] },
  },
  {
    name: 'a descriptor that differs on both sides takes the server version',
    local: { media: [descriptor(ID_ONE, 100), descriptor(ID_TWO)] },
    server: { media: [descriptor(ID_ONE, 200), descriptor(ID_TWO)] },
    expected: { media: [descriptor(ID_ONE, 200), descriptor(ID_TWO)] },
  },
  {
    name: 'media removed locally stays removed even when the server edited its descriptor',
    local: { media: [descriptor(ID_ONE)] },
    server: { media: [descriptor(ID_ONE), descriptor(ID_TWO, 300)] },
    expected: { media: [descriptor(ID_ONE)] },
  },
  {
    name: 'media removed on the server is dropped even when local still holds it',
    local: { media: [descriptor(ID_ONE), descriptor(ID_TWO, 150)] },
    server: { media: [descriptor(ID_ONE)] },
    expected: { media: [descriptor(ID_ONE)] },
  },
];

const FIXED_CASES: readonly MergeCase[] = [
  {
    name: 'createdAtUtc is always the ancestor value',
    local: { createdAtUtc: NEWER },
    server: { createdAtUtc: OLDER },
    expected: { createdAtUtc: CREATED },
  },
  {
    name: 'editedAtUtc is the merge time',
    local: { editedAtUtc: NEWER },
    server: { editedAtUtc: OLDER },
    expected: { editedAtUtc: NOW },
  },
  { name: 'schemaVersion is 1', expected: { schemaVersion: 1 } },
];

describe('mergeMetadata', () => {
  describe.each([
    ['note', NOTE_CASES],
    ['tags', TAG_CASES],
    ['time unit', TIME_CASES],
    ['location', LOCATION_CASES],
    ['media', MEDIA_CASES],
    ['fixed fields', FIXED_CASES],
  ] as const)('%s', (_group, cases) => {
    it.each(cases)('$name', (testCase) => {
      expect(merged(testCase)).toMatchObject(testCase.expected);
    });
  });

  it('merging three identical versions only moves editedAtUtc', () => {
    const base = metadata();
    expect(mergeMetadata(base, base, base, NOW)).toEqual({ ...base, editedAtUtc: NOW });
  });

  it('merges independent fields from both sides in one pass', () => {
    const result = mergeMetadata(
      metadata(),
      metadata({ note: 'mine', editedAtUtc: NEWER }),
      metadata({ tags: ['a', 'b', 'c'], location: PLACE, editedAtUtc: OLDER }),
      NOW,
    );
    expect(result).toEqual(
      metadata({ note: 'mine', tags: ['a', 'b', 'c'], location: PLACE, editedAtUtc: NOW }),
    );
  });
});

describe('metadataEquivalent', () => {
  const EQUIVALENT_CASES: readonly (readonly [string, Patch])[] = [
    ['an identical copy', {}],
    ['a different editedAtUtc', { editedAtUtc: NEWER }],
    ['tags that differ only by case and whitespace', { tags: [' A', 'B '] }],
  ];

  const DIFFERENT_CASES: readonly (readonly [string, Patch])[] = [
    ['a different note', { note: 'other' }],
    ['different tags', { tags: ['a', 'c'] }],
    ['a different takenAtUtc', { takenAtUtc: TAKEN_LOCAL }],
    ['a different utcOffsetMinutes', { utcOffsetMinutes: 60 }],
    ['a different timeZone', { timeZone: 'Europe/Paris' }],
    ['a different createdAtUtc', { createdAtUtc: NEWER }],
    ['a location where there was none', { location: PLACE }],
    ['media in another order', { media: [descriptor(ID_TWO), descriptor(ID_ONE)] }],
    ['a different media descriptor', { media: [descriptor(ID_ONE, 101), descriptor(ID_TWO)] }],
    ['fewer media', { media: [descriptor(ID_ONE)] }],
  ];

  it.each(EQUIVALENT_CASES)('treats %s as equivalent', (_name, patch) => {
    expect(metadataEquivalent(metadata(), metadata(patch))).toBe(true);
  });

  it.each(DIFFERENT_CASES)('treats %s as different', (_name, patch) => {
    expect(metadataEquivalent(metadata(), metadata(patch))).toBe(false);
  });

  it('compares location numbers exactly', () => {
    const left = metadata({ location: PLACE });
    const right = metadata({ location: { ...PLACE, latitude: PLACE.latitude + 0.000001 } });
    expect(metadataEquivalent(left, right)).toBe(false);
    expect(metadataEquivalent(left, metadata({ location: { ...PLACE } }))).toBe(true);
  });

  it('is symmetric', () => {
    const left = metadata({ note: 'x' });
    const right = metadata();
    expect(metadataEquivalent(left, right)).toBe(metadataEquivalent(right, left));
  });
});
