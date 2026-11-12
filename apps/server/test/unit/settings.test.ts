import { describe, expect, it } from 'vitest';
import { SettingsError } from '../../src/domain/errors.ts';
import { loadSettings } from '../../src/config/settings.ts';

const SECRET_PASSWORD = 'hunter2-not-a-real-password';
const FAKE_PARAMS_SECRET = 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU';
const STORAGE_ENV = {
  STORAGE_PROVIDER: 'garage',
  S3_ENDPOINT: 'http://garage:3900',
  S3_PUBLIC_ENDPOINT: 'https://s3.kodakiko.example.com',
  S3_BUCKET: 'kodakiko',
  S3_ACCESS_KEY_ID: 'GKexampleaccesskey',
  S3_SECRET_ACCESS_KEY: 'example-secret-not-real',
};
const BASE_ENV = {
  ...STORAGE_ENV,
  FAKE_PARAMS_SECRET,
  PUBLIC_BASE_URL: 'https://kodakiko.example.com',
  DATABASE_URL: `postgres://kodakiko:${SECRET_PASSWORD}@db.example.com:5432/kodakiko`,
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

describe('loadSettings', () => {
  it('applies the defaults', () => {
    const settings = loadSettings(BASE_ENV);
    expect(settings.server).toEqual({
      port: 8080,
      host: '0.0.0.0',
      publicBaseUrl: 'https://kodakiko.example.com',
      trustedProxies: ['loopback', 'uniquelocal'],
      requireHttps: true,
      rateLimitsEnabled: true,
      version: 'dev',
      shutdownTimeoutMs: 15000,
    });
    expect(settings.database).toEqual({
      url: BASE_ENV.DATABASE_URL,
      poolMax: 10,
      statementTimeoutMs: 10000,
      connectTimeoutMs: 5000,
    });
    expect(settings.flags).toEqual({ sharing: false, publicLinks: false, signupMode: 'invite' });
    expect(settings.limits).toEqual({
      maxMediaPerEntry: 20,
      maxUploadBytes: 3758096384,
      defaultQuotaBytes: 107374182400,
      maxCollectionMetadataBytes: 4096,
      maxEntryMetadataBytes: 524288,
    });
    expect(settings.log).toEqual({ level: 'info' });
    expect(settings.jobs).toEqual({
      enabled: true,
      expireSessionsIntervalMs: 86_400_000,
      collectObjectsIntervalMs: 600_000,
      abortStaleUploadsIntervalMs: 3_600_000,
      reconcileUsageIntervalMs: 86_400_000,
      purgeTrashIntervalMs: 3_600_000,
      eraseAccountsIntervalMs: 3_600_000,
      pruneTombstonesIntervalMs: 86_400_000,
    });
  });

  it('reads overrides', () => {
    const settings = loadSettings({
      ...BASE_ENV,
      PORT: '9000',
      FLAG_SHARING: 'true',
      SIGNUP_MODE: 'open',
      MAX_MEDIA_PER_ENTRY: '100',
      TRUSTED_PROXIES: '10.0.0.0/8, fd00::/8 ,192.168.1.5,linklocal',
    });
    expect(settings.server.port).toBe(9000);
    expect(settings.flags.sharing).toBe(true);
    expect(settings.flags.signupMode).toBe('open');
    expect(settings.limits.maxMediaPerEntry).toBe(100);
    expect(settings.server.trustedProxies).toEqual([
      '10.0.0.0/8',
      'fd00::/8',
      '192.168.1.5',
      'linklocal',
    ]);
  });

  it('rejects an http base URL while HTTPS is required and accepts it otherwise', () => {
    const env = { ...BASE_ENV, PUBLIC_BASE_URL: 'http://kodakiko.example.com' };
    expect(messageOf(env)).toContain('PUBLIC_BASE_URL');
    expect(messageOf({ ...env, REQUIRE_HTTPS: 'true' })).toContain('PUBLIC_BASE_URL');
    expect(loadSettings({ ...env, REQUIRE_HTTPS: 'false' }).server.publicBaseUrl).toBe(
      env.PUBLIC_BASE_URL,
    );
  });

  it('rejects a base URL with a trailing slash', () => {
    expect(messageOf({ ...BASE_ENV, PUBLIC_BASE_URL: 'https://kodakiko.example.com/' })).toContain(
      'PUBLIC_BASE_URL',
    );
  });

  it('allows disabled rate limits only for local plain-http hosts', () => {
    const local = { ...BASE_ENV, REQUIRE_HTTPS: 'false', RATE_LIMITS_ENABLED: 'false' };
    expect(messageOf({ ...BASE_ENV, RATE_LIMITS_ENABLED: 'false' })).toContain(
      'RATE_LIMITS_ENABLED',
    );
    expect(messageOf({ ...local, PUBLIC_BASE_URL: 'http://kodakiko.example.com' })).toContain(
      'RATE_LIMITS_ENABLED',
    );
    expect(
      messageOf({ ...BASE_ENV, RATE_LIMITS_ENABLED: 'false', REQUIRE_HTTPS: 'true' }),
    ).toContain('RATE_LIMITS_ENABLED');
    for (const host of ['localhost', '127.0.0.1']) {
      const settings = loadSettings({ ...local, PUBLIC_BASE_URL: `http://${host}:8080` });
      expect(settings.server.rateLimitsEnabled).toBe(false);
    }
  });

  it('names each missing required variable', () => {
    expect(messageOf({ DATABASE_URL: BASE_ENV.DATABASE_URL })).toContain('PUBLIC_BASE_URL');
    expect(messageOf({ PUBLIC_BASE_URL: BASE_ENV.PUBLIC_BASE_URL })).toContain('DATABASE_URL');
  });

  it('lists every invalid variable in one error', () => {
    const message = messageOf({ PORT: '0', LOG_LEVEL: 'loud' });
    for (const name of ['PORT', 'LOG_LEVEL', 'PUBLIC_BASE_URL', 'DATABASE_URL']) {
      expect(message).toContain(name);
    }
  });

  it('rejects invalid values', () => {
    expect(messageOf({ ...BASE_ENV, MAX_MEDIA_PER_ENTRY: '101' })).toContain('MAX_MEDIA_PER_ENTRY');
    expect(messageOf({ ...BASE_ENV, FLAG_SHARING: 'TRUE' })).toContain('FLAG_SHARING');
    expect(messageOf({ ...BASE_ENV, SIGNUP_MODE: 'closed' })).toContain('SIGNUP_MODE');
    expect(messageOf({ ...BASE_ENV, MAX_UPLOAD_BYTES: '3758096385' })).toContain(
      'MAX_UPLOAD_BYTES',
    );
    expect(messageOf({ ...BASE_ENV, SHUTDOWN_TIMEOUT_MS: '999' })).toContain('SHUTDOWN_TIMEOUT_MS');
  });

  it('reports a missing variable together with other invalid ones', () => {
    const message = messageOf({
      DATABASE_URL: BASE_ENV.DATABASE_URL,
      TRUSTED_PROXIES: 'bad',
    });
    expect(message).toContain('PUBLIC_BASE_URL');
    expect(message).toContain('TRUSTED_PROXIES');
  });

  it('accepts only an origin as PUBLIC_BASE_URL', () => {
    for (const value of [
      'https://u:p@a.example',
      'https://u@a.example',
      'https://a.example?x=1',
      'https://a.example#frag',
      'https://a.example/path',
      'https://a.example/',
    ]) {
      expect(messageOf({ ...BASE_ENV, PUBLIC_BASE_URL: value })).toContain('PUBLIC_BASE_URL');
    }
    expect(
      loadSettings({ ...BASE_ENV, PUBLIC_BASE_URL: 'https://a.example:8443' }).server.publicBaseUrl,
    ).toBe('https://a.example:8443');
  });

  it('accepts an empty TRUSTED_PROXIES as no trusted proxy', () => {
    expect(loadSettings({ ...BASE_ENV, TRUSTED_PROXIES: '' }).server.trustedProxies).toEqual([]);
  });

  it('names an invalid TRUSTED_PROXIES entry', () => {
    const message = messageOf({ ...BASE_ENV, TRUSTED_PROXIES: 'loopback,not-an-ip' });
    expect(message).toContain('TRUSTED_PROXIES');
    expect(message).toContain('not-an-ip');
    for (const entry of ['10.0.0.0/33', 'fd00::/129', '10.0.0.0/', '10.0.0.0/-1', 'loopback/8']) {
      expect(messageOf({ ...BASE_ENV, TRUSTED_PROXIES: entry })).toContain(entry);
    }
  });

  it('accepts IP, CIDR and preset entries', () => {
    const entries =
      '127.0.0.1,::1,10.0.0.0/0,10.0.0.0/32,fd00::/128,uniquelocal,loopback,linklocal';
    const settings = loadSettings({ ...BASE_ENV, TRUSTED_PROXIES: entries });
    expect(settings.server.trustedProxies).toHaveLength(8);
  });

  it('returns a deeply frozen object', () => {
    const settings = loadSettings(BASE_ENV);
    expect(Object.isFrozen(settings)).toBe(true);
    expect(Object.isFrozen(settings.server)).toBe(true);
    expect(Object.isFrozen(settings.database)).toBe(true);
    expect(Object.isFrozen(settings.flags)).toBe(true);
  });

  it('never puts the DATABASE_URL value in an error message', () => {
    const invalidUrl = `not-a-postgres-url-${SECRET_PASSWORD}`;
    const message = messageOf({ ...BASE_ENV, DATABASE_URL: invalidUrl, PORT: 'x' });
    expect(message).toContain('DATABASE_URL');
    expect(message).not.toContain(SECRET_PASSWORD);
  });

  it('applies the auth and account defaults and decodes the fake params secret', () => {
    const settings = loadSettings(BASE_ENV);
    expect(settings.auth).toMatchObject({
      sessionIdleDays: 30,
      maxFailedSignIns: 10,
      failedSignInWindowMinutes: 60,
      inviteDefaultExpiryDays: 7,
    });
    expect(settings.auth.fakeParamsSecret).toBeInstanceOf(Uint8Array);
    expect(settings.auth.fakeParamsSecret.length).toBe(32);
    expect(settings.accounts).toEqual({ deletionGraceDays: 7 });
    expect(Object.isFrozen(settings.auth)).toBe(true);
  });

  it('reads the auth overrides and converts minutes to milliseconds', () => {
    const settings = loadSettings({
      ...BASE_ENV,
      SESSION_IDLE_DAYS: '365',
      MAX_FAILED_SIGN_INS: '1',
      FAILED_SIGN_IN_WINDOW_MINUTES: '1440',
      INVITE_DEFAULT_EXPIRY_DAYS: '90',
      ACCOUNT_DELETION_GRACE_DAYS: '1',
      JOB_EXPIRE_SESSIONS_INTERVAL_MINUTES: '2',
      MAX_COLLECTION_METADATA_BYTES: '256',
    });
    expect(settings.auth.sessionIdleDays).toBe(365);
    expect(settings.auth.maxFailedSignIns).toBe(1);
    expect(settings.accounts.deletionGraceDays).toBe(1);
    expect(settings.jobs.expireSessionsIntervalMs).toBe(120_000);
    expect(settings.limits.maxCollectionMetadataBytes).toBe(256);
  });

  it('rejects out-of-range auth values and a weak or missing fake params secret', () => {
    expect(messageOf({ ...BASE_ENV, SESSION_IDLE_DAYS: '366' })).toContain('SESSION_IDLE_DAYS');
    expect(messageOf({ ...BASE_ENV, MAX_FAILED_SIGN_INS: '0' })).toContain('MAX_FAILED_SIGN_INS');
    expect(messageOf({ ...BASE_ENV, INVITE_DEFAULT_EXPIRY_DAYS: '91' })).toContain(
      'INVITE_DEFAULT_EXPIRY_DAYS',
    );
    expect(messageOf({ ...BASE_ENV, ACCOUNT_DELETION_GRACE_DAYS: '0' })).toContain(
      'ACCOUNT_DELETION_GRACE_DAYS',
    );
    expect(messageOf({ ...BASE_ENV, MAX_COLLECTION_METADATA_BYTES: '255' })).toContain(
      'MAX_COLLECTION_METADATA_BYTES',
    );
    const { FAKE_PARAMS_SECRET: _omitted, ...withoutSecret } = BASE_ENV;
    expect(messageOf(withoutSecret)).toContain('FAKE_PARAMS_SECRET');
    const shortSecret = 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo';
    const message = messageOf({ ...BASE_ENV, FAKE_PARAMS_SECRET: shortSecret });
    expect(message).toContain('FAKE_PARAMS_SECRET');
    expect(message).not.toContain(shortSecret);
  });

  it('names FAKE_PARAMS_SECRET for malformed values without echoing them', () => {
    for (const malformed of ['AAAAA', 'not base64url!!', '', 'a']) {
      const message = messageOf({ ...BASE_ENV, FAKE_PARAMS_SECRET: malformed });
      expect(message).toContain('FAKE_PARAMS_SECRET');
      if (malformed.length > 1) expect(message).not.toContain(malformed);
    }
  });
});
