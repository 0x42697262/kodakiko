import { describe, expect, it } from 'vitest';
import { MAX_INDEX_TOKENS_PER_ENTRY, TOKEN_MIN_CHARS, tokenize } from '../../src/index.ts';

describe('tokenize', () => {
  it.each([
    ['Beach Sunset', ['beach', 'sunset']],
    ['  beach,sunset!! beach ', ['beach', 'sunset']],
    ['a b cd', ['cd']],
    ['ＢＥＡＣＨ', ['beach']],
    ['Caf\u0065\u0301 2026', ['caf\u00e9', '2026']],
    ['**bold** _it_ #tag', ['bold', 'it', 'tag']],
    ['', []],
    ['日本 東京', ['日本', '東京']],
  ])('%j → %j', (text, expected) => {
    expect(tokenize(text)).toEqual(expected);
  });

  it('drops tokens shorter than TOKEN_MIN_CHARS', () => {
    expect(TOKEN_MIN_CHARS).toBe(2);
    expect(tokenize('x yy z')).toEqual(['yy']);
  });

  it('keeps at most MAX_INDEX_TOKENS_PER_ENTRY tokens, the first ones in note order', () => {
    const words = Array.from({ length: MAX_INDEX_TOKENS_PER_ENTRY + 20 }, (_, index) => `w${index}`);
    const tokens = tokenize(words.join(' '));
    expect(tokens).toHaveLength(MAX_INDEX_TOKENS_PER_ENTRY);
    expect(tokens[0]).toBe('w0');
    expect(tokens[MAX_INDEX_TOKENS_PER_ENTRY - 1]).toBe(`w${MAX_INDEX_TOKENS_PER_ENTRY - 1}`);
  });

  it('deduplicates before applying the cap', () => {
    const text = `${'same '.repeat(MAX_INDEX_TOKENS_PER_ENTRY)} other`;
    expect(tokenize(text)).toEqual(['same', 'other']);
  });
});
