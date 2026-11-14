import { EntryMetadataV1 } from '@kodakiko/contract';
import { describe, expect, it } from 'vitest';
import { parseEntryNote, planEntryExport } from '../../src/index.ts';

const MEDIA = {
  kind: 'photo' as const,
  mimeType: 'image/jpeg',
  width: 10,
  height: 10,
  durationMs: null,
  originalPlaintextBytes: 100,
  thumbnail: { width: 10, height: 10, mimeType: 'image/webp' as const, plaintextBytes: 10 },
  capturedAtUtc: null,
};

function metadata(patch: Record<string, unknown> = {}) {
  return EntryMetadataV1.parse({
    schemaVersion: 1,
    takenAtUtc: '2026-11-01T07:30:00.000Z',
    utcOffsetMinutes: -330,
    timeZone: null,
    note: '# Title\n\nA line with: colons, "quotes" and #hash.\n---\nnot front matter',
    tags: ['beach', 'trip-2026'],
    location: { latitude: 52.52, longitude: -13.405, accuracyMeters: null, source: 'exif' },
    media: [
      { ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: 'IMG "1", final.jpg' },
      { ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm02', kind: 'video', mimeType: 'video/mp4', durationMs: 1000, originalFileName: null },
    ],
    createdAtUtc: '2026-11-01T07:30:00.000Z',
    editedAtUtc: '2026-11-01T07:30:00.000Z',
    ...patch,
  });
}

describe('parseEntryNote (spec 23 import)', () => {
  it('reads back everything the exporter wrote', () => {
    const plan = planEntryExport('ENTRY1', metadata({ favorite: true }));
    expect(parseEntryNote(plan.noteText)).toEqual({
      id: 'ENTRY1',
      takenAtUtc: '2026-11-01T07:30:00.000Z',
      utcOffsetMinutes: -330,
      tags: ['beach', 'trip-2026'],
      favorite: true,
      location: { latitude: 52.52, longitude: -13.405, source: 'exif' },
      media: [
        { file: '001.jpg', originalName: 'IMG "1", final.jpg', kind: 'photo' },
        { file: '002.mp4', originalName: null, kind: 'video' },
      ],
      note: '# Title\n\nA line with: colons, "quotes" and #hash.\n---\nnot front matter',
    });
  });

  it('handles no tags, no location, no favorite and an empty note', () => {
    const plan = planEntryExport('ENTRY2', metadata({ tags: [], location: null, note: '' }));
    const parsed = parseEntryNote(plan.noteText);
    expect(parsed.tags).toEqual([]);
    expect(parsed.location).toBeNull();
    expect(parsed.favorite).toBe(false);
    expect(parsed.note).toBe('');
  });

  it('rejects text without front matter', () => {
    expect(() => parseEntryNote('just a note')).toThrow();
  });
});
