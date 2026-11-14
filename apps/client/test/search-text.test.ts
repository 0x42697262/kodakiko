import { describe, expect, it } from 'vitest';
import { searchTextOf } from '../src/services/index/search-text';

describe('searchTextOf', () => {
  it('joins the note and every caption so search finds captions', () => {
    expect(searchTextOf({ note: 'Lake day', media: [{ caption: 'Sunset' }, {}, { caption: 'Swim' }] })).toBe('Lake day\nSunset\nSwim');
  });

  it('is the note alone without captions', () => {
    expect(searchTextOf({ note: 'Only note', media: [{}] })).toBe('Only note');
  });
});
