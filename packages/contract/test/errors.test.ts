import { describe, expect, it } from 'vitest';
import { ERROR_CODES, errorResponseSchema, publicConfigSchema } from '../src/index.ts';

describe('errors', () => {
  it('lists every code of api.md section 2 once', () => {
    expect(new Set(ERROR_CODES).size).toBe(ERROR_CODES.length);
    expect(ERROR_CODES).toContain('HTTPS_REQUIRED');
    expect(ERROR_CODES).toContain('REVISION_CONFLICT');
    expect(ERROR_CODES).toContain('STORAGE_UNAVAILABLE');
  });

  it('parses the error envelope and rejects unknown codes', () => {
    const body = { error: { code: 'NOT_FOUND', message: 'm', requestId: 'r', details: null } };
    expect(errorResponseSchema.safeParse(body).success).toBe(true);
    const unknown = { error: { ...body.error, code: 'NOPE' } };
    expect(errorResponseSchema.safeParse(unknown).success).toBe(false);
  });
});

describe('publicConfigSchema', () => {
  it('parses the public config', () => {
    const config = {
      apiVersion: 1,
      signupMode: 'invite',
      sharingEnabled: false,
      publicLinksEnabled: false,
      maxMediaPerEntry: 20,
      maxUploadBytes: 1024,
      defaultQuotaBytes: 0,
      trashDays: 30,
      accountDeletionGraceDays: 7,
      serverVersion: 'dev',
    };
    expect(publicConfigSchema.safeParse(config).success).toBe(true);
    expect(publicConfigSchema.safeParse({ ...config, apiVersion: 2 }).success).toBe(false);
  });
});
