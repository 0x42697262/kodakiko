import { describe, expect, it } from 'vitest';
import {
  ENTRY_PURPOSES,
  MEDIA_PER_ENTRY_HARD_MAX,
  ROUTES,
  ROUTE_PATTERNS,
  entryCreateRequest,
  entryDto,
  entryIdParams,
  entryPatchRequest,
  mediaCreateRequest,
  mediaDto,
  mediaIdParams,
  revisionConflictDetails,
} from '../src/index.ts';

const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const BYTES = 'AAECAwQF';
const TIME = '2026-11-12T10:00:00.000Z';
const MEMBERSHIPS_MAX = 10;

function membership(position: number) {
  return { collectionId: ID_A.slice(1) + String(position % 10), entryKeyByCollection: BYTES };
}

function media(position: number) {
  return { mediaId: ID_B.slice(2) + String(position).padStart(2, '0'), mediaKeyByEntry: BYTES };
}

const validCreate = {
  id: ID_A,
  purpose: 'journal',
  metadataBlob: BYTES,
  memberships: [membership(0)],
  media: [media(0)],
};

describe('entry constants', () => {
  it('lists the purposes', () => {
    expect([...ENTRY_PURPOSES]).toEqual(['journal', 'link_copy']);
  });
});

describe('entryCreateRequest', () => {
  it('accepts a valid request', () => {
    expect(entryCreateRequest.safeParse(validCreate).success).toBe(true);
  });

  it('requires at least one membership and at most ten', () => {
    expect(entryCreateRequest.safeParse({ ...validCreate, memberships: [] }).success).toBe(false);
    const ten = Array.from({ length: MEMBERSHIPS_MAX }, (_, position) => membership(position));
    expect(entryCreateRequest.safeParse({ ...validCreate, memberships: ten }).success).toBe(true);
    expect(
      entryCreateRequest.safeParse({ ...validCreate, memberships: [...ten, membership(0)] })
        .success,
    ).toBe(false);
  });

  it('requires at least one media item and at most the hard maximum', () => {
    expect(entryCreateRequest.safeParse({ ...validCreate, media: [] }).success).toBe(false);
    const hard = Array.from({ length: MEDIA_PER_ENTRY_HARD_MAX }, (_, position) => media(position));
    expect(entryCreateRequest.safeParse({ ...validCreate, media: hard }).success).toBe(true);
    expect(
      entryCreateRequest.safeParse({ ...validCreate, media: [...hard, media(0)] }).success,
    ).toBe(false);
  });

  it('rejects a bad purpose, blob and id', () => {
    expect(entryCreateRequest.safeParse({ ...validCreate, purpose: 'x' }).success).toBe(false);
    expect(entryCreateRequest.safeParse({ ...validCreate, metadataBlob: '' }).success).toBe(false);
    expect(entryCreateRequest.safeParse({ ...validCreate, id: 'short' }).success).toBe(false);
  });
});

describe('entry patch, dto and conflict details', () => {
  it('requires a base revision of at least 1', () => {
    expect(entryPatchRequest.safeParse({ metadataBlob: BYTES, baseRevision: 1 }).success).toBe(
      true,
    );
    expect(entryPatchRequest.safeParse({ metadataBlob: BYTES, baseRevision: 0 }).success).toBe(
      false,
    );
    expect(entryPatchRequest.safeParse({ metadataBlob: BYTES }).success).toBe(false);
  });

  it('parses an entry dto with and without trashedAt', () => {
    const dto = {
      id: ID_A,
      ownerId: ID_B,
      purpose: 'journal',
      revision: 1,
      metadataBlob: BYTES,
      trashedAt: null,
      createdAt: TIME,
      updatedAt: TIME,
    };
    expect(entryDto.safeParse(dto).success).toBe(true);
    expect(entryDto.safeParse({ ...dto, trashedAt: TIME }).success).toBe(true);
    expect(entryDto.safeParse({ ...dto, revision: 0 }).success).toBe(false);
  });

  it('parses conflict details', () => {
    expect(revisionConflictDetails.safeParse({ revision: 2, metadataBlob: BYTES }).success).toBe(
      true,
    );
  });
});

describe('media schemas', () => {
  it('parses create requests and dtos', () => {
    expect(mediaCreateRequest.safeParse({ mediaId: ID_A, mediaKeyByEntry: BYTES }).success).toBe(
      true,
    );
    expect(mediaCreateRequest.safeParse({ mediaId: ID_A }).success).toBe(false);
    expect(
      mediaDto.safeParse({ id: ID_A, entryId: ID_B, mediaKeyByEntry: BYTES, createdAt: TIME })
        .success,
    ).toBe(true);
  });

  it('validates the id params', () => {
    expect(entryIdParams.safeParse({ id: ID_A }).success).toBe(true);
    expect(mediaIdParams.safeParse({ id: 'short' }).success).toBe(false);
  });
});

describe('entry routes', () => {
  it('builds the entry and media route patterns', () => {
    expect(ROUTE_PATTERNS.entries).toBe('/api/v1/entries');
    expect(ROUTES.entry(ID_A)).toBe(`/api/v1/entries/${ID_A}`);
    expect(ROUTES.entryTrash(ID_A)).toBe(`/api/v1/entries/${ID_A}/trash`);
    expect(ROUTES.entryRestore(ID_A)).toBe(`/api/v1/entries/${ID_A}/restore`);
    expect(ROUTES.entryMedia(ID_A)).toBe(`/api/v1/entries/${ID_A}/media`);
    expect(ROUTES.media(ID_A)).toBe(`/api/v1/media/${ID_A}`);
  });
});
