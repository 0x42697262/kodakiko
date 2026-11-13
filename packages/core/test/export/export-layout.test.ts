import { describe, expect, it } from 'vitest';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  EXPORT_FILE_OVERHEAD_BYTES,
  EXPORT_PART_MAX_FILES,
  clampZipDate,
  frontMatter,
  partFileName,
  planEntryExport,
  planParts,
  yamlQuote,
  type PlannedFile,
} from '../../src/export/index.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;

const ENTRY_ID = 'AbCdEfGh12345678901234';

function metadata(overrides: Partial<Metadata> = {}): Metadata {
  const media = (mimeType: string, kind: 'photo' | 'video', index: number) => ({
    mediaId: `M${String(index).padStart(21, '0')}`,
    kind,
    mimeType,
    width: 10,
    height: 10,
    durationMs: kind === 'video' ? 1000 : null,
    originalPlaintextBytes: 1000 * (index + 1),
    thumbnail: { width: 4, height: 4, mimeType: 'image/webp', plaintextBytes: 10 },
    capturedAtUtc: null,
    originalFileName: index === 0 ? 'IMG_1234.JPG' : null,
  });
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-11-11T13:05:00.000Z',
    utcOffsetMinutes: 60,
    timeZone: null,
    note: 'Lunch at the beach.',
    tags: ['beach', 'family'],
    location: { latitude: 37.3861, longitude: -122.0839, source: 'exif' },
    media: [media('image/jpeg', 'photo', 0), media('video/mp4', 'video', 1), media('video/quicktime', 'video', 2)],
    createdAtUtc: '2026-11-11T13:06:00.000Z',
    editedAtUtc: '2026-11-11T13:06:00.000Z',
    ...overrides,
  } as Metadata;
}

describe('planEntryExport', () => {
  it('names the folder from the local time and numbers media with extensions from the MIME type', () => {
    const plan = planEntryExport(ENTRY_ID, metadata());
    expect(plan.folder).toBe('2026/11/2026-11-11_1405_AbCdEfGh/');
    expect(plan.noteFile).toBe('2026/11/2026-11-11_1405_AbCdEfGh/entry.md');
    expect(plan.media.map((media) => media.fileName)).toEqual(['001.jpg', '002.mp4', '003.mov']);
    expect(plan.media.map((media) => media.plaintextBytes)).toEqual([1000, 2000, 3000]);
    expect(plan.mtime.toISOString()).toBe('2026-11-11T13:05:00.000Z');
  });

  it('crosses midnight and months by the entry offset', () => {
    const late = planEntryExport(ENTRY_ID, metadata({ takenAtUtc: '2026-12-31T23:30:00.000Z', utcOffsetMinutes: 120 }));
    expect(late.folder).toBe('2027/01/2027-01-01_0130_AbCdEfGh/');
    const west = planEntryExport(ENTRY_ID, metadata({ takenAtUtc: '2026-03-01T02:00:00.000Z', utcOffsetMinutes: -300 }));
    expect(west.folder).toBe('2026/02/2026-02-28_2100_AbCdEfGh/');
  });
});

describe('frontMatter', () => {
  it('writes id, local date with offset, tags, location and media', () => {
    expect(frontMatter(ENTRY_ID, metadata())).toBe(
      [
        '---',
        `id: ${ENTRY_ID}`,
        'date: 2026-11-11T14:05:00+01:00',
        'tags: [beach, family]',
        'location: { latitude: 37.38610, longitude: -122.08390, source: exif }',
        'media: [{ file: 001.jpg, original_name: IMG_1234.JPG, kind: photo }, { file: 002.mp4, kind: video }, { file: 003.mov, kind: video }]',
        '---',
        '',
      ].join('\n'),
    );
  });

  it('omits a missing location and quotes awkward tags', () => {
    const text = frontMatter(ENTRY_ID, metadata({ location: null, tags: ['a: b', 'say "hi"', ' padded', '🌊 sea', 'back\\slash'] }));
    expect(text).not.toContain('location:');
    expect(text).toContain('tags: ["a: b", "say \\"hi\\"", " padded", 🌊 sea, back\\slash]');
  });

  it('writes the note after the front matter in entry.md', () => {
    expect(planEntryExport(ENTRY_ID, metadata()).noteText.endsWith('---\n\nLunch at the beach.\n')).toBe(true);
  });
});

describe('yamlQuote', () => {
  it.each([
    ['plain', 'plain'],
    ['a:b', '"a:b"'],
    ['#tag', '"#tag"'],
    ['[x]', '"[x]"'],
    ["it's", '"it\'s"'],
    ['', '""'],
    ['trailing ', '"trailing "'],
    ['q"\\', '"q\\"\\\\"'],
  ])('%j → %s', (input, output) => {
    expect(yamlQuote(input)).toBe(output);
  });
});

describe('planParts', () => {
  const file = (entryId: string, path: string, bytes: number): PlannedFile => ({ entryId, path, bytes });

  it('splits greedily by bytes and lets an entry span two parts', () => {
    const files = [file('a', 'a/entry.md', 100), file('a', 'a/001.jpg', 5000), file('a', 'a/002.jpg', 5000), file('b', 'b/entry.md', 100)];
    const parts = planParts(files, 5100 + 2 * EXPORT_FILE_OVERHEAD_BYTES, EXPORT_PART_MAX_FILES);
    expect(parts.map((part) => part.map((entry) => entry.path))).toEqual([['a/entry.md', 'a/001.jpg'], ['a/002.jpg', 'b/entry.md']]);
  });

  it('splits by file count', () => {
    const files = Array.from({ length: 5 }, (_, index) => file('x', `x/${index}`, 1));
    expect(planParts(files, Number.MAX_SAFE_INTEGER, 2).map((part) => part.length)).toEqual([2, 2, 1]);
    expect(EXPORT_PART_MAX_FILES).toBe(65_000);
  });

  it('returns no parts for an empty journal', () => {
    expect(planParts([], 1000, 10)).toEqual([]);
  });
});

describe('names and dates', () => {
  it('names parts with two-digit numbers', () => {
    expect(partFileName('2026-11-11', 3)).toBe('kodakiko-export-2026-11-11-part03.zip');
  });

  it('clamps ZIP dates into 1980-2099', () => {
    expect(clampZipDate(new Date('1970-01-01T00:00:00Z')).toISOString().slice(0, 10)).toBe('1980-01-01');
    expect(clampZipDate(new Date('2150-06-01T00:00:00Z')).toISOString().slice(0, 10)).toBe('2099-12-31');
    expect(clampZipDate(new Date('2026-01-01T00:00:00Z')).toISOString()).toBe('2026-01-01T00:00:00.000Z');
  });
});
