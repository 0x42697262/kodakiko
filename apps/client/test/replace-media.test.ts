import { describe, expect, it, vi } from 'vitest';
import { replaceMedia } from '../src/features/entries/replace-media';

function fakeEntries(ids: string[], captions: Record<string, string> = {}) {
  let media = ids.map((mediaId) => ({ mediaId, ...(captions[mediaId] === undefined ? {} : { caption: captions[mediaId] }) }));
  return {
    readMetadata: vi.fn(async () => ({ media })),
    addMedia: vi.fn(async (_entryId: string, items: readonly { mediaId: string }[]) => {
      media = [...media, ...items.map((item) => ({ mediaId: item.mediaId }))];
    }),
    removeMedia: vi.fn(async (_entryId: string, mediaId: string) => {
      media = media.filter((item) => item.mediaId !== mediaId);
    }),
    edit: vi.fn(async (_entryId: string, edit: { mediaOrder?: readonly string[]; captions?: Record<string, string> }) => {
      if (edit.mediaOrder !== undefined) media = edit.mediaOrder.map((id) => media.find((item) => item.mediaId === id) ?? { mediaId: id });
      if (edit.captions !== undefined) media = media.map((item) => ({ ...item, ...(edit.captions?.[item.mediaId] === undefined ? {} : { caption: edit.captions[item.mediaId] }) }));
    }),
    current: () => media,
  };
}

describe('replaceMedia (spec 25 crop, D-97)', () => {
  it('puts the new item where the old one was, keeps its caption and drops the old one', async () => {
    const entries = fakeEntries(['a', 'b', 'c'], { b: 'Sunset' });
    const importOne = vi.fn(async () => ({ ok: true as const, media: { mediaId: 'n' } }));
    const result = await replaceMedia({ entries, importOne, limit: 20 }, 'e1', 'b', { fake: true });
    expect(result).toBe('replaced');
    expect(entries.current()).toEqual([{ mediaId: 'a' }, { mediaId: 'n', caption: 'Sunset' }, { mediaId: 'c' }]);
    expect(importOne).toHaveBeenCalledWith({ fake: true }, { used: 2, limit: 20 });
  });

  it('changes nothing when the import is refused', async () => {
    const entries = fakeEntries(['a', 'b']);
    const result = await replaceMedia({ entries, importOne: async () => ({ ok: false as const }), limit: 20 }, 'e1', 'b', {});
    expect(result).toBe('refused');
    expect(entries.current().map((item) => item.mediaId)).toEqual(['a', 'b']);
  });
});
