import { describe, expect, it } from 'vitest';
import {
  abandonedDeleteAfter,
  chooseUploadMode,
  deleteAfter,
  objectKey,
  partsComplete,
  quotaAllows,
  reissueUrlTtlSeconds,
  staleUploadCutoff,
  type UploadPlan,
} from '../../src/domain/objects.ts';

const MIB = 1_048_576;
const SIZING = { singlePartMaxBytes: 8 * MIB, multipartPartBytes: 8 * MIB };
const OWNER = 'OOOOOOOOOOOOOOOOOOOOOO';
const OBJECT = 'JJJJJJJJJJJJJJJJJJJJJJ';
const DAY_MS = 86_400_000;
const HOUR_MS = 3_600_000;
const NOW = new Date('2026-11-12T10:00:00.000Z');

function multipart(size: number): Extract<UploadPlan, { mode: 'multipart' }> {
  const plan = chooseUploadMode(size, SIZING);
  if (plan.mode !== 'multipart') throw new Error('expected multipart');
  return plan;
}

function parts(sizes: readonly number[]): { partNumber: number; sizeBytes: number }[] {
  return sizes.map((sizeBytes, position) => ({ partNumber: position + 1, sizeBytes }));
}

describe('objectKey', () => {
  it('is v1/<ownerId>/<objectId>', () => {
    expect(objectKey(OWNER, OBJECT)).toBe(`v1/${OWNER}/${OBJECT}`);
  });
});

describe('chooseUploadMode', () => {
  it('is single at singlePartMaxBytes and multipart one byte above', () => {
    expect(chooseUploadMode(SIZING.singlePartMaxBytes, SIZING)).toEqual({ mode: 'single' });
    expect(chooseUploadMode(SIZING.singlePartMaxBytes + 1, SIZING).mode).toBe('multipart');
  });

  it('counts parts as ceil(size / partSize)', () => {
    expect(multipart(8 * MIB + 1)).toMatchObject({ partSizeBytes: 8 * MIB, partCount: 2 });
    expect(multipart(16 * MIB)).toMatchObject({ partCount: 2 });
    expect(multipart(16 * MIB + 1)).toMatchObject({ partCount: 3 });
  });
});

describe('partsComplete', () => {
  const size = 2 * 8 * MIB + 123;

  it('accepts exactly the planned parts', () => {
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB, 123]))).toBe(true);
  });

  it('accepts parts in any order', () => {
    const shuffled = parts([8 * MIB, 8 * MIB, 123]).reverse();
    expect(partsComplete(multipart(size), shuffled)).toBe(true);
  });

  it('rejects a missing part', () => {
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB]))).toBe(false);
    const gap = [
      { partNumber: 1, sizeBytes: 8 * MIB },
      { partNumber: 3, sizeBytes: 123 },
    ];
    expect(partsComplete(multipart(size), gap)).toBe(false);
  });

  it('rejects a wrong middle part size', () => {
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB - 1, 123]))).toBe(false);
  });

  it('rejects a wrong last part size', () => {
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB, 122]))).toBe(false);
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB, 124]))).toBe(false);
  });

  it('rejects an extra part', () => {
    expect(partsComplete(multipart(size), parts([8 * MIB, 8 * MIB, 123, 5]))).toBe(false);
  });

  it('rejects any parts for a single plan', () => {
    expect(partsComplete({ mode: 'single' }, parts([100]))).toBe(false);
  });
});

describe('quotaAllows', () => {
  const DEFAULT_QUOTA = 1000;

  it('uses the default when the quota is null', () => {
    expect(quotaAllows(0, null, DEFAULT_QUOTA, 1000)).toBe(true);
    expect(quotaAllows(1, null, DEFAULT_QUOTA, 1000)).toBe(false);
  });

  it('uses an explicit quota instead of the default', () => {
    expect(quotaAllows(500, 1500, DEFAULT_QUOTA, 1000)).toBe(true);
    expect(quotaAllows(501, 1500, DEFAULT_QUOTA, 1000)).toBe(false);
  });

  it('treats an explicit zero quota as zero, not as the default', () => {
    expect(quotaAllows(0, 0, DEFAULT_QUOTA, 1)).toBe(false);
  });
});

describe('time rules', () => {
  it('adds grace days to now', () => {
    expect(deleteAfter(NOW, 7).getTime()).toBe(NOW.getTime() + 7 * DAY_MS);
    expect(deleteAfter(NOW, 0).getTime()).toBe(NOW.getTime());
  });

  it('subtracts the pending upload hours from now', () => {
    expect(staleUploadCutoff(NOW, 48).getTime()).toBe(NOW.getTime() - 48 * HOUR_MS);
  });
});

describe('abandonedDeleteAfter', () => {
  const URL_TTL_SECONDS = 86_400;

  it('uses the grace period when it is longer than the URL lifetime', () => {
    expect(abandonedDeleteAfter(NOW, 7, URL_TTL_SECONDS).getTime()).toBe(NOW.getTime() + 7 * DAY_MS);
  });

  it('uses the URL lifetime when the grace period is shorter, including zero grace', () => {
    expect(abandonedDeleteAfter(NOW, 0, URL_TTL_SECONDS).getTime()).toBe(
      NOW.getTime() + URL_TTL_SECONDS * 1000,
    );
    expect(abandonedDeleteAfter(NOW, 1, 2 * URL_TTL_SECONDS).getTime()).toBe(
      NOW.getTime() + 2 * URL_TTL_SECONDS * 1000,
    );
  });

  it('is never now', () => {
    expect(abandonedDeleteAfter(NOW, 0, 60).getTime()).toBeGreaterThan(NOW.getTime());
  });
});

describe('reissueUrlTtlSeconds', () => {
  const URL_TTL_SECONDS = 86_400;
  const PENDING_HOURS = 48;
  const SECOND_MS = 1000;

  it('keeps the full lifetime for a young row', () => {
    expect(reissueUrlTtlSeconds(NOW, NOW, PENDING_HOURS, URL_TTL_SECONDS)).toBe(URL_TTL_SECONDS);
  });

  it('caps the lifetime at the time left until the row expires', () => {
    const createdAt = new Date(NOW.getTime() - PENDING_HOURS * HOUR_MS + 100 * SECOND_MS);
    expect(reissueUrlTtlSeconds(NOW, createdAt, PENDING_HOURS, URL_TTL_SECONDS)).toBe(100);
  });

  it('is zero or negative at and after the expiry', () => {
    const atExpiry = new Date(NOW.getTime() - PENDING_HOURS * HOUR_MS);
    expect(reissueUrlTtlSeconds(NOW, atExpiry, PENDING_HOURS, URL_TTL_SECONDS)).toBeLessThanOrEqual(0);
    const past = new Date(atExpiry.getTime() - SECOND_MS);
    expect(reissueUrlTtlSeconds(NOW, past, PENDING_HOURS, URL_TTL_SECONDS)).toBeLessThanOrEqual(0);
  });
});
