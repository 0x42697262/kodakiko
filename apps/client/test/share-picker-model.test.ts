import { describe, expect, it } from 'vitest';
import { SHARE_WARN_BYTES, shareSelection, shareText } from '../src/features/sharing/share-picker-model';

const items = [
  { mediaId: 'a', bytes: 200_000_000, caption: 'Dock' },
  { mediaId: 'b', bytes: 250_000_000 },
  { mediaId: 'c', bytes: 100_000_000, caption: 'Gulls' },
];

describe('share picker model', () => {
  it('counts the checked items and warns above the size limit', () => {
    expect(shareSelection(items, new Set(['a', 'b']))).toEqual({ count: 2, bytes: 450_000_000, warn: false });
    const all = shareSelection(items, new Set(['a', 'b', 'c']));
    expect(all.bytes).toBe(550_000_000);
    expect(all.warn).toBe(all.bytes > SHARE_WARN_BYTES);
    expect(all.warn).toBe(true);
  });

  it('builds the optional text from captions and the note', () => {
    const chosen = new Set(['a', 'b', 'c']);
    expect(shareText('none', items, chosen, 'A note')).toBeNull();
    expect(shareText('captions', items, chosen, 'A note')).toBe('Dock\nGulls');
    expect(shareText('captionsAndNote', items, chosen, 'A note')).toBe('Dock\nGulls\n\nA note');
    expect(shareText('captions', items, new Set(['b']), 'A note')).toBeNull();
    expect(shareText('captionsAndNote', items, new Set(['b']), '')).toBeNull();
  });
});
