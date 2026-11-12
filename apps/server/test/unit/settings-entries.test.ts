import { describe, expect, it } from 'vitest';
import { SettingsError } from '../../src/domain/errors.ts';
import { loadSettings } from '../../src/config/settings.ts';

const MINUTE_MS = 60_000;
const BASE_ENV = {
  FAKE_PARAMS_SECRET: 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU',
  PUBLIC_BASE_URL: 'https://kodakiko.example.com',
  DATABASE_URL: 'postgres://kodakiko:db-password@db.example.com:5432/kodakiko',
  STORAGE_PROVIDER: 'garage',
  S3_ENDPOINT: 'http://garage:3900',
  S3_PUBLIC_ENDPOINT: 'https://s3.kodakiko.example.com',
  S3_BUCKET: 'kodakiko',
  S3_ACCESS_KEY_ID: 'GKexampleaccesskey',
  S3_SECRET_ACCESS_KEY: 'secret',
};

function messageOf(env: Record<string, string | undefined>): string {
  try {
    loadSettings(env);
  } catch (error) {
    expect(error).toBeInstanceOf(SettingsError);
    return (error as Error).message;
  }
  throw new Error('loadSettings did not throw');
}

describe('entries, sync and job settings', () => {
  it('applies the defaults', () => {
    const settings = loadSettings(BASE_ENV);
    expect(settings.limits.maxEntryMetadataBytes).toBe(524_288);
    expect(settings.sync).toEqual({ maxDiffPageSize: 500, tombstoneRetentionDays: 90 });
    expect(settings.entries).toEqual({ trashDays: 30 });
    expect(settings.jobs.purgeTrashIntervalMs).toBe(60 * MINUTE_MS);
    expect(settings.jobs.eraseAccountsIntervalMs).toBe(60 * MINUTE_MS);
    expect(settings.jobs.pruneTombstonesIntervalMs).toBe(1440 * MINUTE_MS);
  });

  it('reads overrides and converts minutes to milliseconds', () => {
    const settings = loadSettings({
      ...BASE_ENV,
      MAX_ENTRY_METADATA_BYTES: '1024',
      MAX_DIFF_PAGE_SIZE: '10',
      TRASH_DAYS: '1',
      TOMBSTONE_RETENTION_DAYS: '7',
      JOB_PURGE_TRASH_INTERVAL_MINUTES: '5',
      JOB_ERASE_ACCOUNTS_INTERVAL_MINUTES: '6',
      JOB_PRUNE_TOMBSTONES_INTERVAL_MINUTES: '7',
    });
    expect(settings.limits.maxEntryMetadataBytes).toBe(1024);
    expect(settings.sync).toEqual({ maxDiffPageSize: 10, tombstoneRetentionDays: 7 });
    expect(settings.entries.trashDays).toBe(1);
    expect(settings.jobs.purgeTrashIntervalMs).toBe(5 * MINUTE_MS);
    expect(settings.jobs.eraseAccountsIntervalMs).toBe(6 * MINUTE_MS);
    expect(settings.jobs.pruneTombstonesIntervalMs).toBe(7 * MINUTE_MS);
  });

  it.each([
    ['MAX_ENTRY_METADATA_BYTES', '1023'],
    ['MAX_DIFF_PAGE_SIZE', '9'],
    ['MAX_DIFF_PAGE_SIZE', '2001'],
    ['TRASH_DAYS', '0'],
    ['TOMBSTONE_RETENTION_DAYS', '6'],
    ['JOB_PURGE_TRASH_INTERVAL_MINUTES', '0'],
    ['JOB_ERASE_ACCOUNTS_INTERVAL_MINUTES', '0'],
    ['JOB_PRUNE_TOMBSTONES_INTERVAL_MINUTES', '0'],
  ])('rejects %s=%s and names the variable', (name, value) => {
    expect(messageOf({ ...BASE_ENV, [name]: value })).toContain(name);
  });
});
