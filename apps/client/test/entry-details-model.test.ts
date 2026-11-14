import { describe, expect, it } from 'vitest';
import { entryDetailSections } from '../src/features/viewer/entry-details-model';

const METADATA = {
  schemaVersion: 1 as const,
  takenAtUtc: '2026-11-01T08:00:00.000Z',
  utcOffsetMinutes: 60,
  timeZone: 'Europe/Berlin',
  note: 'hello',
  tags: ['beach'],
  location: { latitude: 52.52, longitude: 13.405, source: 'exif' as const },
  media: [
    {
      mediaId: 'MEDIA1',
      kind: 'video' as const,
      mimeType: 'video/mp4',
      width: 1920,
      height: 1080,
      durationMs: 20_500,
      originalPlaintextBytes: 5_242_880,
      thumbnail: { width: 320, height: 180, mimeType: 'image/webp' as const, plaintextBytes: 9000 },
      capturedAtUtc: '2026-11-01T07:59:00.000Z',
      originalFileName: 'clip.mp4',
    },
  ],
};

describe('entryDetailSections', () => {
  const sections = entryDetailSections({
    entryId: 'ENTRY1',
    metadata: METADATA as never,
    row: { revision: 3, createdAt: '2026-11-01T08:01:00.000Z', updatedAt: '2026-11-02T10:00:00.000Z', localState: 'synced' },
    objects: new Map([['MEDIA1', [{ variant: 'original', sizeBytes: 5_300_000, localState: 'present', uploadState: 'uploaded' }]]]) as never,
    technical: true,
  });

  it('lists the entry facts with the offset and the location source', () => {
    const entry = sections[0];
    expect(entry?.rows).toEqual(
      expect.arrayContaining([
        { label: 'Id', value: 'ENTRY1' },
        { label: 'Taken', value: '2026-11-01 09:00 (UTC+01:00, Europe/Berlin)' },
        { label: 'Version', value: '3' },
        { label: 'Location', value: '52.52000, 13.40500 (exif)' },
        { label: 'Tags', value: '#beach' },
      ]),
    );
  });

  it('lists each media item with type, size, dimensions, duration and storage state', () => {
    const media = sections[1];
    expect(media?.title).toBe('Video 1');
    expect(media?.rows).toEqual(
      expect.arrayContaining([
        { label: 'File name', value: 'clip.mp4' },
        { label: 'Type', value: 'video/mp4' },
        { label: 'Size', value: '5 MB' },
        { label: 'Dimensions', value: '1920 × 1080' },
        { label: 'Duration', value: '00:20' },
        { label: 'Original', value: 'On this device, uploaded' },
      ]),
    );
  });

  it('hides ids and encrypted sizes unless technical details are on', () => {
    const plain = entryDetailSections({
      entryId: 'ENTRY1',
      metadata: METADATA as never,
      row: { revision: 3, createdAt: '2026-11-01T08:01:00.000Z', updatedAt: '2026-11-02T10:00:00.000Z', localState: 'synced' },
      objects: new Map() as never,
      technical: false,
    });
    expect(plain.flatMap((section) => section.rows).some((row) => row.label === 'Id')).toBe(false);
  });
});
