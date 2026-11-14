import { describe, expect, it } from 'vitest';
import {
  CollectionMetadataV1,
  EntryMetadataV1,
  CAPTION_MAX_CHARS,
  MEDIA_PER_ENTRY_HARD_MAX,
  NOTE_MAX_CHARS,
  TAGS_PER_ENTRY_MAX,
  TAG_MAX_CHARS,
  TagName,
  UTC_OFFSET_MINUTES_MAX,
  UTC_OFFSET_MINUTES_MIN,
} from '../src/index.ts';

const MEDIA_ID = 'AAAAAAAAAAAAAAAAAAAAAA';
const MOMENT = '2026-11-11T10:00:00.000Z';

function mediaDescriptor() {
  return {
    mediaId: MEDIA_ID,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width: 4000,
    height: 3000,
    durationMs: null,
    originalPlaintextBytes: 123456,
    thumbnail: { width: 512, height: 384, mimeType: 'image/webp', plaintextBytes: 40000 },
    capturedAtUtc: MOMENT,
    originalFileName: 'IMG_0001.jpg',
  };
}

function entryMetadata(overrides: Record<string, unknown> = {}) {
  return {
    schemaVersion: 1,
    takenAtUtc: MOMENT,
    utcOffsetMinutes: 120,
    timeZone: 'Europe/Berlin',
    note: 'a note',
    tags: ['holiday', 'beach'],
    location: { latitude: 52.5, longitude: 13.4, accuracyMeters: 5, source: 'exif' },
    media: [mediaDescriptor()],
    createdAtUtc: MOMENT,
    editedAtUtc: MOMENT,
    ...overrides,
  };
}

describe('EntryMetadataV1', () => {
  it('parses a valid entry', () => {
    expect(EntryMetadataV1.safeParse(entryMetadata()).success).toBe(true);
  });

  it('accepts the limits exactly', () => {
    const atLimits = entryMetadata({
      note: 'x'.repeat(NOTE_MAX_CHARS),
      tags: Array.from({ length: TAGS_PER_ENTRY_MAX }, (_, index) => `tag-${index}`),
      media: Array.from({ length: MEDIA_PER_ENTRY_HARD_MAX }, mediaDescriptor),
      utcOffsetMinutes: UTC_OFFSET_MINUTES_MAX,
    });
    expect(EntryMetadataV1.safeParse(atLimits).success).toBe(true);
  });

  it('rejects a note one character too long', () => {
    const result = EntryMetadataV1.safeParse(
      entryMetadata({ note: 'x'.repeat(NOTE_MAX_CHARS + 1) }),
    );
    expect(result.success).toBe(false);
  });

  it('rejects one tag too many', () => {
    const tags = Array.from({ length: TAGS_PER_ENTRY_MAX + 1 }, (_, index) => `tag-${index}`);
    expect(EntryMetadataV1.safeParse(entryMetadata({ tags })).success).toBe(false);
  });

  it('rejects one media item too many', () => {
    const media = Array.from({ length: MEDIA_PER_ENTRY_HARD_MAX + 1 }, mediaDescriptor);
    expect(EntryMetadataV1.safeParse(entryMetadata({ media })).success).toBe(false);
  });

  it('rejects an offset one minute outside the range', () => {
    const tooHigh = entryMetadata({ utcOffsetMinutes: UTC_OFFSET_MINUTES_MAX + 1 });
    const tooLow = entryMetadata({ utcOffsetMinutes: UTC_OFFSET_MINUTES_MIN - 1 });
    expect(EntryMetadataV1.safeParse(tooHigh).success).toBe(false);
    expect(EntryMetadataV1.safeParse(tooLow).success).toBe(false);
  });

  it('rejects an entry without media', () => {
    expect(EntryMetadataV1.safeParse(entryMetadata({ media: [] })).success).toBe(false);
  });

  it('rejects a tag one character too long', () => {
    const tags = ['x'.repeat(TAG_MAX_CHARS + 1)];
    expect(EntryMetadataV1.safeParse(entryMetadata({ tags })).success).toBe(false);
  });

  it('rejects a schema version other than 1', () => {
    expect(EntryMetadataV1.safeParse(entryMetadata({ schemaVersion: 2 })).success).toBe(false);
  });
});

describe('MediaDescriptorV1 additions', () => {
  it('accepts an optional caption up to the limit and keeps old blobs valid', () => {
    const withCaption = entryMetadata({ media: [{ ...mediaDescriptor(), caption: 'x'.repeat(CAPTION_MAX_CHARS) }] });
    expect(EntryMetadataV1.safeParse(withCaption).success).toBe(true);
    expect(EntryMetadataV1.safeParse(entryMetadata()).success).toBe(true);
  });

  it('rejects an empty caption and one that is too long', () => {
    expect(EntryMetadataV1.safeParse(entryMetadata({ media: [{ ...mediaDescriptor(), caption: '' }] })).success).toBe(false);
    expect(
      EntryMetadataV1.safeParse(entryMetadata({ media: [{ ...mediaDescriptor(), caption: 'x'.repeat(CAPTION_MAX_CHARS + 1) }] })).success,
    ).toBe(false);
  });

  it('accepts an audio item with a duration', () => {
    const audio = { ...mediaDescriptor(), kind: 'audio', mimeType: 'audio/mp4', durationMs: 4200 };
    expect(EntryMetadataV1.safeParse(entryMetadata({ media: [audio] })).success).toBe(true);
  });
});

describe('TagName', () => {
  it('checks only the shape', () => {
    expect(TagName.safeParse('x'.repeat(TAG_MAX_CHARS)).success).toBe(true);
    expect(TagName.safeParse('').success).toBe(false);
    expect(TagName.safeParse('x'.repeat(TAG_MAX_CHARS + 1)).success).toBe(false);
  });
});

describe('CollectionMetadataV1', () => {
  it('parses a valid collection and rejects an empty name', () => {
    const valid = { schemaVersion: 1, name: 'Family', createdAtUtc: MOMENT };
    expect(CollectionMetadataV1.safeParse(valid).success).toBe(true);
    expect(CollectionMetadataV1.safeParse({ ...valid, name: '' }).success).toBe(false);
  });
});
