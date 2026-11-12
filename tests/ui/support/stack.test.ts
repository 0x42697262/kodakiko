import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  ANDROID_API_ENV_OVERRIDES,
  ANDROID_S3_ORIGIN,
  ANDROID_WEB_ORIGIN,
  ENV_NAMES,
} from './constants.ts';
import { serverEnvironment } from './stack.ts';

const SAVED_NAMES = [
  ENV_NAMES.s3AccessKeyId,
  ENV_NAMES.s3SecretAccessKey,
  ENV_NAMES.publicBaseUrl,
  ENV_NAMES.s3PublicEndpoint,
];
const saved: Record<string, string | undefined> = {};

beforeEach(() => {
  for (const name of SAVED_NAMES) saved[name] = process.env[name];
  process.env[ENV_NAMES.s3AccessKeyId] = 'key-id';
  process.env[ENV_NAMES.s3SecretAccessKey] = 'key-secret';
  delete process.env[ENV_NAMES.publicBaseUrl];
  delete process.env[ENV_NAMES.s3PublicEndpoint];
});

afterEach(() => {
  for (const name of SAVED_NAMES) {
    const value = saved[name];
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
});

describe('serverEnvironment', () => {
  it('maps the UI S3 keys to the server names', () => {
    const environment = serverEnvironment({});
    expect(environment[ENV_NAMES.serverS3AccessKeyId]).toBe('key-id');
    expect(environment[ENV_NAMES.serverS3SecretAccessKey]).toBe('key-secret');
    expect(environment[ENV_NAMES.publicBaseUrl]).toBeUndefined();
  });

  it('applies the Android overrides for the emulator host alias', () => {
    const environment = serverEnvironment(ANDROID_API_ENV_OVERRIDES);
    expect(environment[ENV_NAMES.publicBaseUrl]).toBe(ANDROID_WEB_ORIGIN);
    expect(environment[ENV_NAMES.s3PublicEndpoint]).toBe(ANDROID_S3_ORIGIN);
    expect(ANDROID_WEB_ORIGIN).toBe('http://10.0.2.2:9580');
    expect(ANDROID_S3_ORIGIN).toBe('http://10.0.2.2:9581');
  });

  it('keeps the S3 keys when an override tries to replace them', () => {
    const environment = serverEnvironment({ [ENV_NAMES.serverS3AccessKeyId]: 'other' });
    expect(environment[ENV_NAMES.serverS3AccessKeyId]).toBe('key-id');
  });
});
