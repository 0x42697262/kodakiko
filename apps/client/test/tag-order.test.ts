import { describe, expect, it } from 'vitest';
import { moveTag, orderTags } from '../src/features/tags/tag-order';

describe('tag order', () => {
  const counted = [
    { tag: 'beach', count: 3 },
    { tag: 'city', count: 1 },
    { tag: 'zoo', count: 2 },
  ];

  it('puts ordered tags first, keeps empty ordered tags with a zero count, and appends the rest alphabetically', () => {
    expect(orderTags(counted, ['zoo', 'later', 'beach'])).toEqual([
      { tag: 'zoo', count: 2 },
      { tag: 'later', count: 0 },
      { tag: 'beach', count: 3 },
      { tag: 'city', count: 1 },
    ]);
  });

  it('moves a tag up or down within the full order and ignores moves past the ends', () => {
    const order = ['zoo', 'beach', 'city'];
    expect(moveTag(order, 'beach', -1)).toEqual(['beach', 'zoo', 'city']);
    expect(moveTag(order, 'beach', 1)).toEqual(['zoo', 'city', 'beach']);
    expect(moveTag(order, 'zoo', -1)).toEqual(order);
  });
});
