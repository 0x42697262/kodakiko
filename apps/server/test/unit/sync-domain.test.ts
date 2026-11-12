import { describe, expect, it } from 'vitest';
import {
  mergeDiffRows,
  needsResync,
  nextCursor,
  sortedForLocking,
} from '../../src/domain/sync.ts';

function rows(...seqs: number[]) {
  return seqs.map((seq) => ({ seq, entryId: `e${seq}` }));
}

function seqsOf(result: { rows: readonly { seq: number }[] }): number[] {
  return result.rows.map((row) => row.seq);
}

describe('sortedForLocking', () => {
  it('sorts ascending by plain string comparison and removes duplicates', () => {
    expect(sortedForLocking(['b', 'a', 'B', 'a', '_', '-'])).toEqual(['-', 'B', '_', 'a', 'b']);
  });

  it('returns an empty list for no ids and does not mutate the input', () => {
    const input = ['z', 'y'];
    expect(sortedForLocking([])).toEqual([]);
    sortedForLocking(input);
    expect(input).toEqual(['z', 'y']);
  });
});

describe('mergeDiffRows', () => {
  it('interleaves two ascending inputs by seq', () => {
    const merged = mergeDiffRows(rows(1, 4, 6), rows(2, 3, 5), 10);
    expect(seqsOf(merged)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(merged.hasMore).toBe(false);
  });

  it('is exact at the limit boundary', () => {
    const exact = mergeDiffRows(rows(1, 3), rows(2), 3);
    expect(seqsOf(exact)).toEqual([1, 2, 3]);
    expect(exact.hasMore).toBe(false);
    const over = mergeDiffRows(rows(1, 3), rows(2, 4), 3);
    expect(seqsOf(over)).toEqual([1, 2, 3]);
    expect(over.hasMore).toBe(true);
  });

  it('reports hasMore for the limit + 1 fetch of one side', () => {
    const merged = mergeDiffRows(rows(1, 2, 3, 4), [], 3);
    expect(seqsOf(merged)).toEqual([1, 2, 3]);
    expect(merged.hasMore).toBe(true);
  });

  it('handles empty inputs', () => {
    expect(mergeDiffRows([], [], 5)).toEqual({ rows: [], hasMore: false });
    expect(seqsOf(mergeDiffRows([], rows(7), 5))).toEqual([7]);
  });

  it('keeps the row payload', () => {
    expect(mergeDiffRows(rows(2), rows(1), 5).rows[0]).toEqual({ seq: 1, entryId: 'e1' });
  });
});

describe('nextCursor', () => {
  it('uses the last returned seq when more rows exist', () => {
    const page = { rows: rows(4, 5, 6), hasMore: true };
    expect(nextCursor(page, 3, 20)).toBe(6);
  });

  it('uses the collection last_seq on the last page', () => {
    expect(nextCursor({ rows: rows(4, 5), hasMore: false }, 3, 9)).toBe(9);
    expect(nextCursor({ rows: [], hasMore: false }, 3, 9)).toBe(9);
  });
});

describe('needsResync', () => {
  it('needs a resync only for a cursor above zero and below the pruned seq', () => {
    expect(needsResync(5, 10)).toBe(true);
    expect(needsResync(10, 10)).toBe(false);
    expect(needsResync(11, 10)).toBe(false);
    expect(needsResync(0, 10)).toBe(false);
    expect(needsResync(1, 0)).toBe(false);
  });
});
