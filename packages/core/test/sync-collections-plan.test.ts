import { describe, expect, it } from 'vitest';
import {
  planCollections,
  type LocalCollectionState,
  type RemoteCollectionState,
} from '../src/index.ts';

const META = '2026-11-12T10:00:00.000Z';
const META_NEW = '2026-11-13T10:00:00.000Z';

function remote(id: string, overrides: Partial<RemoteCollectionState> = {}): RemoteCollectionState {
  return {
    id,
    kind: 'home',
    role: 'owner',
    lastSeq: 0,
    tombstonesPrunedThroughSeq: 0,
    metaUpdatedAt: META,
    ...overrides,
  };
}

function local(id: string, overrides: Partial<LocalCollectionState> = {}): LocalCollectionState {
  return { id, cursorSeq: 0, metaUpdatedAt: META, ...overrides };
}

describe('planCollections', () => {
  it('does nothing when everything is current', () => {
    expect(planCollections([remote('a', { lastSeq: 4 })], [local('a', { cursorSeq: 4 })])).toEqual({
      refetch: [],
      pull: [],
      resync: [],
      drop: [],
    });
  });

  it('ignores remote link collections entirely', () => {
    const plan = planCollections([remote('l', { kind: 'link', lastSeq: 9 })], []);
    expect(plan).toEqual({ refetch: [], pull: [], resync: [], drop: [] });
  });

  it('refetches and pulls a remote collection that is absent locally', () => {
    const plan = planCollections([remote('a', { lastSeq: 3 })], []);
    expect(plan.refetch).toEqual(['a']);
    expect(plan.pull).toEqual(['a']);
  });

  it('refetches but does not pull a new empty collection', () => {
    const plan = planCollections([remote('a', { lastSeq: 0 })], []);
    expect(plan.refetch).toEqual(['a']);
    expect(plan.pull).toEqual([]);
  });

  it('refetches when metaUpdatedAt differs', () => {
    const plan = planCollections([remote('a', { metaUpdatedAt: META_NEW })], [local('a')]);
    expect(plan.refetch).toEqual(['a']);
  });

  it('drops local collections that are absent remotely', () => {
    const plan = planCollections([remote('a')], [local('a'), local('gone')]);
    expect(plan.drop).toEqual(['gone']);
  });

  it('drops a local collection whose remote counterpart is a link collection', () => {
    const plan = planCollections([remote('x', { kind: 'link' })], [local('x')]);
    expect(plan.drop).toEqual(['x']);
  });

  it('pulls when lastSeq is ahead of the cursor', () => {
    const plan = planCollections([remote('a', { lastSeq: 8 })], [local('a', { cursorSeq: 5 })]);
    expect(plan.pull).toEqual(['a']);
    expect(plan.resync).toEqual([]);
  });

  it('resyncs when 0 < cursor < tombstonesPrunedThroughSeq', () => {
    const plan = planCollections(
      [remote('a', { lastSeq: 20, tombstonesPrunedThroughSeq: 10 })],
      [local('a', { cursorSeq: 4 })],
    );
    expect(plan.resync).toEqual(['a']);
    expect(plan.pull).toEqual([]);
  });

  it('never resyncs a cursor of 0', () => {
    const plan = planCollections(
      [remote('a', { lastSeq: 20, tombstonesPrunedThroughSeq: 10 })],
      [local('a', { cursorSeq: 0 })],
    );
    expect(plan.resync).toEqual([]);
    expect(plan.pull).toEqual(['a']);
  });

  it('does not resync a cursor equal to the pruned seq', () => {
    const plan = planCollections(
      [remote('a', { lastSeq: 20, tombstonesPrunedThroughSeq: 10 })],
      [local('a', { cursorSeq: 10 })],
    );
    expect(plan.resync).toEqual([]);
    expect(plan.pull).toEqual(['a']);
  });

  it('keeps remote order in the result lists', () => {
    const plan = planCollections([remote('b', { lastSeq: 1 }), remote('a', { lastSeq: 1 })], []);
    expect(plan.refetch).toEqual(['b', 'a']);
    expect(plan.pull).toEqual(['b', 'a']);
  });
});
