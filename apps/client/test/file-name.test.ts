import { describe, expect, it } from 'vitest';
import { FILE_NAME_MAX_CHARS } from '@kodakiko/contract';
import { shortenFileName } from '../src/services/media/file-name';

describe('shortenFileName', () => {
  it('keeps a name within the limit', () => {
    expect(shortenFileName('holiday.jpg')).toBe('holiday.jpg');
    const exact = `${'a'.repeat(FILE_NAME_MAX_CHARS - 4)}.jpg`;
    expect(shortenFileName(exact)).toBe(exact);
  });

  it('shortens a long name and keeps the extension', () => {
    const result = shortenFileName(`${'a'.repeat(300)}.jpg`);

    expect(result).toHaveLength(FILE_NAME_MAX_CHARS);
    expect(result.endsWith('.jpg')).toBe(true);
    expect(result.startsWith('aaa')).toBe(true);
  });

  it('keeps only the last extension', () => {
    const result = shortenFileName(`${'b'.repeat(300)}.tar.gz`);

    expect(result).toHaveLength(FILE_NAME_MAX_CHARS);
    expect(result.endsWith('.gz')).toBe(true);
  });

  it('truncates a long name without an extension', () => {
    expect(shortenFileName('c'.repeat(400))).toBe('c'.repeat(FILE_NAME_MAX_CHARS));
  });

  it('truncates when the extension alone is too long', () => {
    const result = shortenFileName(`a.${'x'.repeat(400)}`);

    expect(result).toHaveLength(FILE_NAME_MAX_CHARS);
  });
});
