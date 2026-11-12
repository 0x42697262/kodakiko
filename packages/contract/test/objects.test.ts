import { describe, expect, it } from 'vitest';
import {
  MIN_OBJECT_BYTES,
  OBJECT_STATES,
  OBJECT_VARIANTS,
  ROUTES,
  ROUTE_PATTERNS,
  downloadResponse,
  objectDto,
  partsResponse,
  presignRequest,
  presignResponse,
} from '../src/index.ts';

const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_B = 'BBBBBBBBBBBBBBBBBBBBBB';
const ID_C = 'CCCCCCCCCCCCCCCCCCCCCC';
const EXPIRES_AT = '2026-11-12T10:00:00.000Z';
const URL_ONE = 'https://s3.example.com/bucket/key?X-Amz-Signature=abc';

describe('object constants', () => {
  it('lists the variants and states of the plan', () => {
    expect([...OBJECT_VARIANTS]).toEqual(['original', 'thumbnail']);
    expect([...OBJECT_STATES]).toEqual(['pending', 'uploaded', 'deleting']);
  });

  it('sets the smallest object to the 40-byte STREAM minimum', () => {
    expect(MIN_OBJECT_BYTES).toBe(40);
  });
});

describe('presignRequest', () => {
  const valid = { id: ID_A, mediaId: ID_B, variant: 'original', sizeBytes: 1000 };

  it('accepts a valid request', () => {
    expect(presignRequest.safeParse(valid).success).toBe(true);
  });

  it('accepts exactly the minimum size and rejects one byte less', () => {
    expect(presignRequest.safeParse({ ...valid, sizeBytes: MIN_OBJECT_BYTES }).success).toBe(true);
    expect(presignRequest.safeParse({ ...valid, sizeBytes: MIN_OBJECT_BYTES - 1 }).success).toBe(
      false,
    );
  });

  it('rejects a fractional size, a bad variant and a malformed id', () => {
    expect(presignRequest.safeParse({ ...valid, sizeBytes: 100.5 }).success).toBe(false);
    expect(presignRequest.safeParse({ ...valid, variant: 'preview' }).success).toBe(false);
    expect(presignRequest.safeParse({ ...valid, id: 'short' }).success).toBe(false);
    expect(presignRequest.safeParse({ ...valid, mediaId: 'short' }).success).toBe(false);
  });
});

describe('presignResponse', () => {
  it('parses the single mode', () => {
    const parsed = presignResponse.parse({ mode: 'single', url: URL_ONE, expiresAt: EXPIRES_AT });
    expect(parsed.mode).toBe('single');
  });

  it('parses the multipart mode', () => {
    const parsed = presignResponse.parse({
      mode: 'multipart',
      partSizeBytes: 8_388_608,
      partUrls: [{ partNumber: 1, url: URL_ONE }],
      expiresAt: EXPIRES_AT,
    });
    expect(parsed.mode).toBe('multipart');
  });

  it('rejects a single response that carries parts and an unknown mode', () => {
    expect(
      presignResponse.safeParse({ mode: 'multipart', url: URL_ONE, expiresAt: EXPIRES_AT }).success,
    ).toBe(false);
    expect(
      presignResponse.safeParse({ mode: 'chunked', url: URL_ONE, expiresAt: EXPIRES_AT }).success,
    ).toBe(false);
  });

  it('rejects part number zero', () => {
    expect(
      presignResponse.safeParse({
        mode: 'multipart',
        partSizeBytes: 8_388_608,
        partUrls: [{ partNumber: 0, url: URL_ONE }],
        expiresAt: EXPIRES_AT,
      }).success,
    ).toBe(false);
  });
});

describe('partsResponse, objectDto and downloadResponse', () => {
  it('parses a parts response', () => {
    const parsed = partsResponse.parse({
      partSizeBytes: 5_242_880,
      uploadedParts: [{ partNumber: 1, sizeBytes: 5_242_880 }],
      partUrls: [{ partNumber: 2, url: URL_ONE }],
      expiresAt: EXPIRES_AT,
    });
    expect(parsed.uploadedParts).toHaveLength(1);
  });

  it('parses an object dto and rejects an unknown state', () => {
    const dto = { id: ID_A, mediaId: ID_C, variant: 'thumbnail', sizeBytes: 40, state: 'pending' };
    expect(objectDto.safeParse(dto).success).toBe(true);
    expect(objectDto.safeParse({ ...dto, state: 'gone' }).success).toBe(false);
  });

  it('parses a download response', () => {
    expect(downloadResponse.safeParse({ url: URL_ONE, expiresAt: EXPIRES_AT }).success).toBe(true);
    expect(downloadResponse.safeParse({ url: 'nope', expiresAt: EXPIRES_AT }).success).toBe(false);
  });
});

describe('object routes', () => {
  it('names the four object routes under the api prefix', () => {
    expect(ROUTE_PATTERNS.objectsPresign).toBe('/api/v1/objects/presign');
    expect(ROUTE_PATTERNS.objectParts).toBe('/api/v1/objects/:id/parts');
    expect(ROUTE_PATTERNS.objectConfirm).toBe('/api/v1/objects/:id/confirm');
    expect(ROUTE_PATTERNS.objectDownload).toBe('/api/v1/objects/:id/download');
  });

  it('builds the parametrized routes from an id', () => {
    expect(ROUTES.objectsPresign()).toBe('/api/v1/objects/presign');
    expect(ROUTES.objectParts(ID_A)).toBe(`/api/v1/objects/${ID_A}/parts`);
    expect(ROUTES.objectConfirm(ID_A)).toBe(`/api/v1/objects/${ID_A}/confirm`);
    expect(ROUTES.objectDownload(ID_A)).toBe(`/api/v1/objects/${ID_A}/download`);
  });
});
