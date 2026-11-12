import { describe, expect, it } from 'vitest';
import {
  COLLECTION_KINDS,
  COLLECTION_ROLES,
  ROUTES,
  ROUTE_PATTERNS,
  collectionDto,
  collectionIdParams,
  collectionListQuery,
  collectionListResponse,
  collectionPatchRequest,
} from '../src/index.ts';

const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const BYTES = 'AAECAwQF';
const TIME = '2026-11-12T10:00:00.000Z';

const validDto = {
  id: ID_A,
  kind: 'home',
  role: 'owner',
  ownerId: ID_B,
  ownerUsername: 'alice',
  keyMaterial: BYTES,
  metadataBlob: BYTES,
  lastSeq: 0,
  createdAt: TIME,
  metaUpdatedAt: TIME,
};

describe('collection constants', () => {
  it('lists kinds and roles of the plan', () => {
    expect([...COLLECTION_KINDS]).toEqual(['home', 'shared', 'link']);
    expect([...COLLECTION_ROLES]).toEqual(['owner', 'viewer']);
  });
});

describe('collectionDto', () => {
  it('accepts a valid dto and rejects a bad kind, role and negative seq', () => {
    expect(collectionDto.safeParse(validDto).success).toBe(true);
    expect(collectionDto.safeParse({ ...validDto, kind: 'other' }).success).toBe(false);
    expect(collectionDto.safeParse({ ...validDto, role: 'editor' }).success).toBe(false);
    expect(collectionDto.safeParse({ ...validDto, lastSeq: -1 }).success).toBe(false);
  });
});

describe('collection requests and responses', () => {
  it('parses a list response with and without a next cursor', () => {
    expect(collectionListResponse.safeParse({ items: [validDto], nextCursor: null }).success).toBe(
      true,
    );
    expect(collectionListResponse.safeParse({ items: [], nextCursor: 'abc' }).success).toBe(true);
    expect(collectionListResponse.safeParse({ items: [] }).success).toBe(false);
  });

  it('requires a decodable metadata blob for a patch', () => {
    expect(collectionPatchRequest.safeParse({ metadataBlob: BYTES }).success).toBe(true);
    expect(collectionPatchRequest.safeParse({ metadataBlob: '' }).success).toBe(false);
    expect(collectionPatchRequest.safeParse({ metadataBlob: 'a b' }).success).toBe(false);
  });

  it('coerces the list query and rejects a limit below 1', () => {
    expect(collectionListQuery.parse({ limit: '5' })).toEqual({ limit: 5 });
    expect(collectionListQuery.parse({ cursor: 'abc' })).toEqual({ cursor: 'abc' });
    expect(collectionListQuery.safeParse({ limit: '0' }).success).toBe(false);
  });

  it('validates the id params', () => {
    expect(collectionIdParams.safeParse({ id: ID_A }).success).toBe(true);
    expect(collectionIdParams.safeParse({ id: 'short' }).success).toBe(false);
  });
});

describe('collection routes', () => {
  it('builds the collection route patterns', () => {
    expect(ROUTE_PATTERNS.collections).toBe('/api/v1/collections');
    expect(ROUTES.collection(ID_A)).toBe(`/api/v1/collections/${ID_A}`);
    expect(ROUTES.collectionDiff(ID_A)).toBe(`/api/v1/collections/${ID_A}/diff`);
    expect(ROUTES.syncState()).toBe('/api/v1/sync/state');
  });
});
