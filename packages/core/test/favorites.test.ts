import { EntryMetadataV1 } from '@kodakiko/contract';
import { describe, expect, it } from 'vitest';
import type { z } from 'zod';
import { applyEntryEdit, attributesMatch, mergeMetadata, metadataEquivalent, type IndexedEntry } from '../src/index.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;

const NOW = '2026-11-14T10:00:00.000Z';

function metadata(patch: Partial<Metadata> = {}): Metadata {
  return EntryMetadataV1.parse({
    schemaVersion: 1,
    takenAtUtc: '2026-11-01T08:00:00.000Z',
    utcOffsetMinutes: 0,
    timeZone: null,
    note: '',
    tags: [],
    location: null,
    media: [
      {
        mediaId: 'mmmmmmmmmmmmmmmmmmmm01',
        kind: 'photo',
        mimeType: 'image/jpeg',
        width: 10,
        height: 10,
        durationMs: null,
        originalPlaintextBytes: 100,
        thumbnail: { width: 10, height: 10, mimeType: 'image/webp', plaintextBytes: 10 },
        capturedAtUtc: null,
        originalFileName: null,
      },
    ],
    createdAtUtc: '2026-11-01T08:00:00.000Z',
    editedAtUtc: '2026-11-01T08:00:00.000Z',
    ...patch,
  });
}

describe('favorites in entry metadata', () => {
  it('accepts metadata with and without the favorite flag', () => {
    expect(EntryMetadataV1.safeParse(metadata()).success).toBe(true);
    expect(metadata({ favorite: true }).favorite).toBe(true);
  });

  it('sets and clears the flag through an edit and keeps it otherwise', () => {
    const marked = applyEntryEdit(metadata(), { favorite: true }, NOW);
    expect(marked.favorite).toBe(true);
    expect(applyEntryEdit(marked, { note: 'x' }, NOW).favorite).toBe(true);
    expect('favorite' in applyEntryEdit(marked, { favorite: false }, NOW)).toBe(false);
  });

  it('merges the flag three ways: the side that changed it wins', () => {
    const base = metadata();
    expect(mergeMetadata(base, metadata({ favorite: true }), base, NOW).favorite).toBe(true);
    expect(mergeMetadata(metadata({ favorite: true }), metadata({ favorite: true }), metadata(), NOW).favorite).toBeUndefined();
    expect('favorite' in mergeMetadata(base, base, base, NOW)).toBe(false);
  });

  it('treats a favorite change as a real change', () => {
    expect(metadataEquivalent(metadata(), metadata({ favorite: true }))).toBe(false);
    expect(metadataEquivalent(metadata({ favorite: true }), metadata({ favorite: true }))).toBe(true);
  });

  it('filters favorites with the tri-state attribute', () => {
    const entry = { isOwn: true, collectionIds: ['home'], hasVideo: false, notePreview: '', mediaCount: 1, pending: false } as unknown as IndexedEntry;
    expect(attributesMatch({ ...entry, favorite: true }, { favorite: 'include' }, 'home')).toBe(true);
    expect(attributesMatch(entry, { favorite: 'include' }, 'home')).toBe(false);
    expect(attributesMatch(entry, { favorite: 'exclude' }, 'home')).toBe(true);
  });
});
