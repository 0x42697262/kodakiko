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

describe('parseEntryNote', () => {
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

describe('captions and audio in exports', () => {
  it('writes captions and reads them back with audio items', () => {
    const plan = planEntryExport(
      'ENTRY3',
      metadata({
        media: [
          { ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: null, caption: 'Sunset, "late"' },
          { ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm03', kind: 'audio', mimeType: 'audio/mp4', durationMs: 4000, originalFileName: null },
        ],
      }),
    );
    expect(plan.noteText).toContain('caption: "Sunset, \\"late\\""');
    expect(parseEntryNote(plan.noteText).media).toEqual([
      { file: '001.jpg', originalName: null, kind: 'photo', caption: 'Sunset, "late"' },
      { file: '002.m4a', originalName: null, kind: 'audio' },
    ]);
  });
});
