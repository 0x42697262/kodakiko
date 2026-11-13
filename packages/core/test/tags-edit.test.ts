import { describe, expect, it } from 'vitest';
import { TAGS_PER_ENTRY_MAX, TAG_MAX_CHARS } from '@kodakiko/contract';
import { addTag, removeTag, sortTags } from '../src/index.ts';

const FULL_LIST = Array.from({ length: TAGS_PER_ENTRY_MAX }, (_, index) =>
  `tag-${String(index).padStart(2, '0')}`,
);

describe('sortTags', () => {
  it.each([
    { name: 'already sorted', input: ['a', 'b'], expected: ['a', 'b'] },
    { name: 'unsorted', input: ['b', 'a', 'c'], expected: ['a', 'b', 'c'] },
    { name: 'code unit order puts digits before letters', input: ['a', '1', 'B'], expected: ['1', 'B', 'a'] },
    { name: 'empty', input: [], expected: [] },
  ])('orders $name by code unit', ({ input, expected }) => {
    expect(sortTags(input)).toEqual(expected);
  });

  it('does not change its input', () => {
    const input = ['b', 'a'];
    sortTags(input);
    expect(input).toEqual(['b', 'a']);
  });
});

describe('addTag', () => {
  it.each([
    { name: 'a plain tag', tags: [], raw: 'family', expected: ['family'] },
    { name: 'a tag that needs normalizing', tags: ['b'], raw: '  Road   Trip ', expected: ['b', 'road trip'] },
    { name: 'a tag that sorts first', tags: ['b', 'c'], raw: 'a', expected: ['a', 'b', 'c'] },
    { name: 'a full-width tag', tags: [], raw: 'ＦＡＭＩＬＹ', expected: ['family'] },
  ])('adds $name and returns the sorted list', ({ tags, raw, expected }) => {
    expect(addTag(tags, raw)).toEqual({ tags: expected, rejected: null });
  });

  it.each([
    { name: 'empty text', tags: ['a'], raw: '', rejected: 'empty' },
    { name: 'blank text', tags: ['a'], raw: '   ', rejected: 'empty' },
    { name: 'text over the limit', tags: ['a'], raw: 'x'.repeat(TAG_MAX_CHARS + 1), rejected: 'tooLong' },
    { name: 'a duplicate', tags: ['a'], raw: 'a', rejected: 'duplicate' },
    { name: 'a duplicate after normalizing', tags: ['road trip'], raw: ' ROAD  trip', rejected: 'duplicate' },
    { name: 'a full list', tags: FULL_LIST, raw: 'one more', rejected: 'tooMany' },
  ] as const)('rejects $name and leaves the list unchanged', ({ tags, raw, rejected }) => {
    expect(addTag(tags, raw)).toEqual({ tags, rejected });
  });

  it('accepts a tag of exactly the maximum length', () => {
    const longest = 'x'.repeat(TAG_MAX_CHARS);
    expect(addTag([], longest)).toEqual({ tags: [longest], rejected: null });
  });

  it('accepts the last free slot', () => {
    const almostFull = FULL_LIST.slice(1);
    expect(addTag(almostFull, 'zzz').rejected).toBeNull();
    expect(addTag(almostFull, 'zzz').tags).toHaveLength(TAGS_PER_ENTRY_MAX);
  });

  it('reports a duplicate before a full list', () => {
    expect(addTag(FULL_LIST, 'tag-00').rejected).toBe('duplicate');
  });
});

describe('removeTag', () => {
  it.each([
    { name: 'a present tag', tags: ['a', 'b', 'c'], tag: 'b', expected: ['a', 'c'] },
    { name: 'a tag typed in another case', tags: ['a', 'b'], tag: ' B ', expected: ['a'] },
    { name: 'an absent tag', tags: ['a'], tag: 'z', expected: ['a'] },
    { name: 'blank text', tags: ['a'], tag: ' ', expected: ['a'] },
    { name: 'the only tag', tags: ['a'], tag: 'a', expected: [] },
  ])('removes $name', ({ tags, tag, expected }) => {
    expect(removeTag(tags, tag)).toEqual(expected);
  });
});
