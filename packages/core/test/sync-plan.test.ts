import { describe, expect, it } from 'vitest';
import { planPushBatch, type OutboxItemView } from '../src/index.ts';

const NOW = 1_000;

function item(
  id: number,
  entryId: string | null,
  overrides: Partial<OutboxItemView> = {},
): OutboxItemView {
  return { id, kind: 'createEntry', entryId, state: 'queued', nextAttemptAt: 0, ...overrides };
}

describe('planPushBatch', () => {
  it('returns due queued items in ascending id order', () => {
    const items = [item(3, 'c'), item(1, 'a'), item(2, 'b')];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([1, 2, 3]);
  });

  it('skips items that are not due yet', () => {
    const items = [item(1, 'a', { nextAttemptAt: NOW + 1 }), item(2, 'b', { nextAttemptAt: NOW })];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([2]);
  });

  it('skips dead items', () => {
    const items = [item(1, 'a', { state: 'dead' }), item(2, 'b')];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([2]);
  });

  it('blocks a later item of the same entry while the earlier one is queued', () => {
    const items = [item(1, 'a'), item(2, 'a', { kind: 'updateEntryMeta' }), item(3, 'b')];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([1, 3]);
  });

  it('blocks a later item of the same entry while the earlier one is dead', () => {
    const items = [item(1, 'a', { state: 'dead' }), item(2, 'a', { kind: 'updateEntryMeta' })];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([]);
  });

  it('blocks a later item of the same entry while the earlier one is not due yet', () => {
    const items = [item(1, 'a', { nextAttemptAt: NOW + 5 }), item(2, 'a', { kind: 'trashEntry' })];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([]);
  });

  it('skips entries that are in flight', () => {
    const items = [item(1, 'a'), item(2, 'b')];
    expect(planPushBatch(items, new Set(['a']), NOW, 10)).toEqual([2]);
  });

  it('never blocks items without an entry id on each other', () => {
    const items = [item(1, null), item(2, null)];
    expect(planPushBatch(items, new Set(), NOW, 10)).toEqual([1, 2]);
  });

  it('returns at most capacity ids', () => {
    const items = [item(1, 'a'), item(2, 'b'), item(3, 'c')];
    expect(planPushBatch(items, new Set(), NOW, 2)).toEqual([1, 2]);
    expect(planPushBatch(items, new Set(), NOW, 0)).toEqual([]);
  });

  it('does not mutate its input', () => {
    const items = [item(2, 'b'), item(1, 'a')];
    const inFlight = new Set<string>();
    planPushBatch(items, inFlight, NOW, 10);
    expect(items.map((entry) => entry.id)).toEqual([2, 1]);
    expect(inFlight.size).toBe(0);
  });
});
