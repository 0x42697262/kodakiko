import { describe, expect, it } from 'vitest';
import { EMPTY_TAG_SUGGESTIONS } from '../src/services/tags/tag-suggestions';

describe('the empty tag suggestion source', () => {
  it('suggests nothing for any prefix', () => {
    expect(EMPTY_TAG_SUGGESTIONS.suggest('')).toEqual([]);
    expect(EMPTY_TAG_SUGGESTIONS.suggest('fam')).toEqual([]);
  });
});
