import { describe, expect, it } from 'vitest';
import { applyEntryEdit } from '@kodakiko/core';
import { idSchema, type EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import { diffDraft, draftFromMetadata } from '../src/features/entries/edit-draft';

type Metadata = z.infer<typeof EntryMetadataV1>;

const NOW = '2026-11-12T10:00:00.000Z';

function media(letter: string) {
  return {
    mediaId: idSchema.parse(letter.repeat(22)),
    kind: 'photo' as const,
    mimeType: 'image/jpeg',
    width: 4,
    height: 3,
    durationMs: null,
    originalPlaintextBytes: 100,
    thumbnail: { width: 4, height: 3, mimeType: 'image/webp' as const, plaintextBytes: 10 },
    capturedAtUtc: null,
    originalFileName: null,
  };
}

function metadata(): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-11-01T08:00:00.000Z',
    utcOffsetMinutes: 60,
    timeZone: null,
    note: 'hello',
    tags: ['beach', 'family'],
    location: { latitude: 48.8584, longitude: 2.2945, accuracyMeters: null, source: 'exif' },
    media: [media('a'), media('b'), media('c')],
    createdAtUtc: '2026-11-01T08:00:00.000Z',
    editedAtUtc: '2026-11-01T08:00:00.000Z',
  };
}

const A = 'a'.repeat(22);
const B = 'b'.repeat(22);
const C = 'c'.repeat(22);

describe('edit draft diff', () => {
  it('reports nothing when the draft is unchanged', () => {
    const current = metadata();
    expect(diffDraft(current, draftFromMetadata(current))).toEqual({ removedMediaIds: [], edit: {} });
  });

  it('reports each changed field and only those', () => {
    const current = metadata();
    const draft = {
      ...draftFromMetadata(current),
      note: 'changed',
      tags: ['beach'],
      time: { takenAtUtc: '2026-11-02T08:00:00.000Z', utcOffsetMinutes: 60 },
      location: null,
    };
    expect(diffDraft(current, draft)).toEqual({
      removedMediaIds: [],
      edit: {
        note: 'changed',
        tags: ['beach'],
        time: { takenAtUtc: '2026-11-02T08:00:00.000Z', utcOffsetMinutes: 60 },
        location: null,
      },
    });
  });

  it('reports a pure reorder as mediaOrder', () => {
    const current = metadata();
    const draft = { ...draftFromMetadata(current), mediaIds: [C, A, B] };
    expect(diffDraft(current, draft)).toEqual({ removedMediaIds: [], edit: { mediaOrder: [C, A, B] } });
  });

  it('removes without reordering when the remaining order is unchanged', () => {
    const current = metadata();
    const draft = { ...draftFromMetadata(current), mediaIds: [A, C] };
    expect(diffDraft(current, draft)).toEqual({ removedMediaIds: [B], edit: {} });
  });

  it('removes one and reorders the rest with an edit valid after the removal', () => {
    const current = metadata();
    const draft = { ...draftFromMetadata(current), mediaIds: [C, A], note: 'new note' };
    const { removedMediaIds, edit } = diffDraft(current, draft);
    expect(removedMediaIds).toEqual([B]);
    expect(edit).toEqual({ note: 'new note', mediaOrder: [C, A] });

    let afterRemovals = current;
    for (const mediaId of removedMediaIds) {
      afterRemovals = applyEntryEdit(afterRemovals, { removeMediaId: mediaId }, NOW);
    }
    const result = applyEntryEdit(afterRemovals, edit, NOW);
    expect(result.media.map((item) => item.mediaId)).toEqual([C, A]);
    expect(result.note).toBe('new note');
  });

  it('lists several removals in metadata order', () => {
    const current = metadata();
    const draft = { ...draftFromMetadata(current), mediaIds: [B] };
    expect(diffDraft(current, draft).removedMediaIds).toEqual([A, C]);
  });

  it('treats tags in a different order as unchanged', () => {
    const current = metadata();
    const draft = { ...draftFromMetadata(current), tags: ['family', 'beach'] };
    expect(diffDraft(current, draft)).toEqual({ removedMediaIds: [], edit: {} });
  });
});

describe('edit draft captions', () => {
  it('carries captions and diffs only the changed ones', () => {
    const current = metadata();
    const draft = draftFromMetadata(current);
    const first = current.media[0]?.mediaId ?? '';
    expect(draft.captions).toEqual({});
    const diff = diffDraft(current, { ...draft, captions: { [first]: 'Sunset' } });
    expect(diff.edit).toEqual({ captions: { [first]: 'Sunset' } });
    const applied = applyEntryEdit(current, diff.edit, NOW);
    expect(diffDraft(applied, draftFromMetadata(applied)).edit).toEqual({});
  });

  it('clears a caption with an empty string', () => {
    const current = metadata();
    const first = current.media[0]?.mediaId ?? '';
    const withCaption = applyEntryEdit(current, { captions: { [first]: 'Old' } }, NOW);
    const draft = draftFromMetadata(withCaption);
    expect(draft.captions).toEqual({ [first]: 'Old' });
    const { [first]: _removed, ...rest } = draft.captions;
    expect(diffDraft(withCaption, { ...draft, captions: rest }).edit).toEqual({ captions: { [first]: '' } });
  });
});
