import { describe, expect, it } from 'vitest';
import Fastify from 'fastify';
import { SettingsError } from '../../src/domain/errors.ts';
import { loadSettings } from '../../src/config/settings.ts';
import { loggerOptions, REDACT_PATHS } from '../../src/http/log-redaction.ts';

const SECRET_KEY = 'storage-secret-value-never-shown';
const MIB = 1_048_576;
const BASE_ENV = {
  FAKE_PARAMS_SECRET: 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU',
  PUBLIC_BASE_URL: 'https://kodakiko.example.com',
  DATABASE_URL: 'postgres://kodakiko:db-password@db.example.com:5432/kodakiko',
  STORAGE_PROVIDER: 'garage',
  S3_ENDPOINT: 'http://garage:3900',
  S3_PUBLIC_ENDPOINT: 'https://s3.kodakiko.example.com',
  S3_BUCKET: 'kodakiko',
  S3_ACCESS_KEY_ID: 'GKexampleaccesskey',
  S3_SECRET_ACCESS_KEY: SECRET_KEY,
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

describe('storage settings', () => {
  it('applies the defaults', () => {
    const { storage } = loadSettings(BASE_ENV);
    expect(storage).toEqual({
      provider: 'garage',
      endpoint: 'http://garage:3900',
      publicEndpoint: 'https://s3.kodakiko.example.com',
      region: 'garage',
      bucket: 'kodakiko',
      accessKeyId: 'GKexampleaccesskey',
      secretAccessKey: SECRET_KEY,
      forcePathStyle: true,
      requestTimeoutMs: 15000,
      singlePartMaxBytes: 8_388_608,
      multipartPartBytes: 8_388_608,
      uploadUrlTtlSeconds: 86400,
      downloadUrlTtlSeconds: 900,
      pendingUploadHours: 48,
      objectGraceDays: 7,
      maxPresignsPerHour: 1000,
    });
  });

  it('converts the job intervals from minutes to milliseconds', () => {
    const { jobs } = loadSettings({
      ...BASE_ENV,
      JOB_COLLECT_OBJECTS_INTERVAL_MINUTES: '2',
      JOB_ABORT_STALE_UPLOADS_INTERVAL_MINUTES: '3',
      JOB_RECONCILE_USAGE_INTERVAL_MINUTES: '4',
    });
    expect(jobs.collectObjectsIntervalMs).toBe(120_000);
    expect(jobs.abortStaleUploadsIntervalMs).toBe(180_000);
    expect(jobs.reconcileUsageIntervalMs).toBe(240_000);
  });

  it('reads overrides', () => {
    const { storage } = loadSettings({
      ...BASE_ENV,
      STORAGE_PROVIDER: 'b2',
      S3_REGION: 'us-west-004',
      S3_FORCE_PATH_STYLE: 'false',
      OBJECT_GRACE_DAYS: '0',
    });
    expect(storage.provider).toBe('b2');
    expect(storage.region).toBe('us-west-004');
    expect(storage.forcePathStyle).toBe(false);
    expect(storage.objectGraceDays).toBe(0);
  });

  it('names each missing required storage variable', () => {
    for (const name of [
      'STORAGE_PROVIDER',
      'S3_ENDPOINT',
      'S3_PUBLIC_ENDPOINT',
      'S3_BUCKET',
      'S3_ACCESS_KEY_ID',
      'S3_SECRET_ACCESS_KEY',
    ]) {
      const { [name]: _removed, ...rest } = BASE_ENV as Record<string, string>;
      expect(messageOf(rest)).toContain(name);
    }
  });

  it('rejects out-of-range values and names the variable', () => {
    const cases: Record<string, string> = {
      STORAGE_PROVIDER: 'ftp',
      S3_BUCKET: 'ab',
      STORAGE_REQUEST_TIMEOUT_MS: '999',
      SINGLE_PART_MAX_BYTES: String(5 * MIB - 1),
      MULTIPART_PART_BYTES: String(5 * MIB - 1),
      UPLOAD_URL_TTL_SECONDS: '59',
      DOWNLOAD_URL_TTL_SECONDS: '86401',
      PENDING_UPLOAD_HOURS: '0',
      OBJECT_GRACE_DAYS: '-1',
      MAX_PRESIGNS_PER_HOUR: '0',
      S3_FORCE_PATH_STYLE: 'yes',
      JOB_COLLECT_OBJECTS_INTERVAL_MINUTES: '0',
      JOB_ABORT_STALE_UPLOADS_INTERVAL_MINUTES: '0',
      JOB_RECONCILE_USAGE_INTERVAL_MINUTES: '0',
    };
    for (const [name, value] of Object.entries(cases)) {
      expect(messageOf({ ...BASE_ENV, [name]: value }), name).toContain(name);
    }
    expect(messageOf({ ...BASE_ENV, UPLOAD_URL_TTL_SECONDS: '604801' })).toContain(
      'UPLOAD_URL_TTL_SECONDS',
    );
    expect(messageOf({ ...BASE_ENV, S3_BUCKET: 'x'.repeat(64) })).toContain('S3_BUCKET');
  });

  it('accepts the bounds themselves', () => {
    const settings = loadSettings({
      ...BASE_ENV,
      S3_BUCKET: 'abc',
      UPLOAD_URL_TTL_SECONDS: '60',
      DOWNLOAD_URL_TTL_SECONDS: '86400',
      PENDING_UPLOAD_HOURS: '1',
      SINGLE_PART_MAX_BYTES: String(5 * MIB),
      MULTIPART_PART_BYTES: String(5 * MIB),
    });
    expect(settings.storage.uploadUrlTtlSeconds).toBe(60);
  });

  it('requires an https public endpoint unless REQUIRE_HTTPS is false', () => {
    const plain = { ...BASE_ENV, S3_PUBLIC_ENDPOINT: 'http://127.0.0.1:53900' };
    expect(messageOf(plain)).toContain('S3_PUBLIC_ENDPOINT');
    const relaxed = loadSettings({
      ...plain,
      REQUIRE_HTTPS: 'false',
      PUBLIC_BASE_URL: 'http://localhost:8080',
    });
    expect(relaxed.storage.publicEndpoint).toBe('http://127.0.0.1:53900');
  });

  it('rejects an endpoint that is not an origin', () => {
    expect(messageOf({ ...BASE_ENV, S3_ENDPOINT: 'garage:3900' })).toContain('S3_ENDPOINT');
    expect(messageOf({ ...BASE_ENV, S3_ENDPOINT: 'http://garage:3900/' })).toContain('S3_ENDPOINT');
    expect(messageOf({ ...BASE_ENV, S3_PUBLIC_ENDPOINT: 'ftp://s3.example.com' })).toContain(
      'S3_PUBLIC_ENDPOINT',
    );
  });

  it('never puts the secret access key in an error message', () => {
    const message = messageOf({ ...BASE_ENV, S3_BUCKET: 'ab', PORT: 'x' });
    expect(message).not.toContain(SECRET_KEY);
    const missing = messageOf({ ...BASE_ENV, S3_ACCESS_KEY_ID: '' });
    expect(missing).not.toContain(SECRET_KEY);
  });

  it('returns a frozen storage group', () => {
    expect(Object.isFrozen(loadSettings(BASE_ENV).storage)).toBe(true);
  });
});

describe('storage settings cross-field rules', () => {
  it('rejects a pending window that is not longer than the upload URL lifetime', () => {
    const message = messageOf({
      ...BASE_ENV,
      PENDING_UPLOAD_HOURS: '1',
      UPLOAD_URL_TTL_SECONDS: '3600',
    });
    expect(message).toContain('PENDING_UPLOAD_HOURS');
    expect(message).toContain('UPLOAD_URL_TTL_SECONDS');
    const accepted = loadSettings({
      ...BASE_ENV,
      PENDING_UPLOAD_HOURS: '1',
      UPLOAD_URL_TTL_SECONDS: '3599',
    });
    expect(accepted.storage.pendingUploadHours).toBe(1);
  });

  it('rejects a single-part limit above the maximum upload size', () => {
    const message = messageOf({ ...BASE_ENV, MAX_UPLOAD_BYTES: String(MIB) });
    expect(message).toContain('SINGLE_PART_MAX_BYTES');
    expect(message).toContain('MAX_UPLOAD_BYTES');
  });

  it('rejects a single-part limit above the multipart part size', () => {
    const message = messageOf({
      ...BASE_ENV,
      SINGLE_PART_MAX_BYTES: String(10 * MIB),
      MULTIPART_PART_BYTES: String(5 * MIB),
    });
    expect(message).toContain('SINGLE_PART_MAX_BYTES');
    expect(message).toContain('MULTIPART_PART_BYTES');
  });

  it('accepts a single-part limit equal to the multipart part size', () => {
    const settings = loadSettings({
      ...BASE_ENV,
      SINGLE_PART_MAX_BYTES: String(5 * MIB),
      MULTIPART_PART_BYTES: String(5 * MIB),
    });
    expect(settings.storage.singlePartMaxBytes).toBe(settings.storage.multipartPartBytes);
  });
});

describe('storage secret redaction', () => {
  it('has secretAccessKey on the redaction list', () => {
    expect(REDACT_PATHS).toContain('secretAccessKey');
    expect(REDACT_PATHS).toContain('*.secretAccessKey');
  });

  it('redacts the secret in a logged settings object', () => {
    const lines: string[] = [];
    const app = Fastify({
      logger: { ...loggerOptions(loadSettings(BASE_ENV)), stream: { write: (line) => lines.push(line) } },
    });
    app.log.info({ storage: loadSettings(BASE_ENV).storage });
    app.log.info({ secretAccessKey: SECRET_KEY });
    expect(lines.join('')).not.toContain(SECRET_KEY);
  });
});
