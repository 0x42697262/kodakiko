import { describe, expect, it } from 'vitest';
import { diffPage, diffQuery, syncStateResponse } from '../src/index.ts';

const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const BYTES = 'AAECAwQF';
const TIME = '2026-11-12T10:00:00.000Z';

describe('diffQuery', () => {
  it('coerces since and limit from query strings', () => {
    expect(diffQuery.parse({ since: '3', limit: '10' })).toEqual({ since: 3, limit: 10 });
    expect(diffQuery.parse({ since: '0' })).toEqual({ since: 0 });
  });

  it('requires since and rejects negative since or a limit below 1', () => {
    expect(diffQuery.safeParse({}).success).toBe(false);
    expect(diffQuery.safeParse({ since: '-1' }).success).toBe(false);
    expect(diffQuery.safeParse({ since: '0', limit: '0' }).success).toBe(false);
    expect(diffQuery.safeParse({ since: '1.5' }).success).toBe(false);
  });

  it('does not cap the limit in the schema', () => {
    expect(diffQuery.parse({ since: '0', limit: '100000' }).limit).toBe(100_000);
  });
});

describe('diffPage', () => {
  const page = {
    collectionId: ID_A,
    memberships: [
      { entryId: ID_B, entryKeyByCollection: BYTES, seq: 1, removed: false },
      { entryId: ID_A, entryKeyByCollection: null, seq: 2, removed: true },
    ],
    entries: [],
    media: [],
    objects: [],
    nextCursor: 2,
    hasMore: false,
    resyncRequired: false,
  };

  it('parses a page with a removed membership', () => {
    expect(diffPage.safeParse(page).success).toBe(true);
  });

  it('rejects a missing resyncRequired and a negative cursor', () => {
    const { resyncRequired: _ignored, ...incomplete } = page;
    expect(diffPage.safeParse(incomplete).success).toBe(false);
    expect(diffPage.safeParse({ ...page, nextCursor: -1 }).success).toBe(false);
  });
});

describe('syncStateResponse', () => {
  it('parses the collection list', () => {
    const parsed = syncStateResponse.safeParse({
      collections: [
        {
          id: ID_A,
          kind: 'home',
          role: 'owner',
          lastSeq: 4,
          tombstonesPrunedThroughSeq: 0,
          metaUpdatedAt: TIME,
        },
      ],
    });
    expect(parsed.success).toBe(true);
  });
});
