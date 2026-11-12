import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { GetBucketCorsCommand, type CORSRule } from '@aws-sdk/client-s3';
import { describe, expect, it } from 'vitest';
import { applyBucketCors } from '../../src/adapters/storage/bucket-cors.ts';
import { createS3Clients } from '../../src/adapters/storage/s3-object-store.ts';
import { garageStorageEnv, testEnv, testSettings } from './helpers.ts';

const ORIGIN = 'https://kodakiko.example.com';
const CLI_ENTRY = new URL('../../src/cli.ts', import.meta.url).pathname;
const CLI_TIMEOUT_MS = 30_000;
const CORS_MAX_AGE_SECONDS = 3600;

const run = promisify(execFile);

async function readRules() {
  const settings = testSettings(garageStorageEnv());
  const { internalClient } = createS3Clients(settings.storage);
  const response = await internalClient.send(
    new GetBucketCorsCommand({ Bucket: settings.storage.bucket }),
  );
  return response.CORSRules ?? [];
}

function normalized(rule: CORSRule) {
  return {
    origins: rule.AllowedOrigins,
    methods: [...(rule.AllowedMethods ?? [])].sort(),
    headers: [...(rule.AllowedHeaders ?? [])].map((header) => header.toLowerCase()).sort(),
    expose: [...(rule.ExposeHeaders ?? [])].map((header) => header.toLowerCase()).sort(),
    maxAge: rule.MaxAgeSeconds,
  };
}

const EXPECTED = {
  origins: [ORIGIN],
  methods: ['GET', 'HEAD', 'PUT'],
  headers: ['content-type', 'range'],
  expose: ['accept-ranges', 'content-length', 'content-range'],
  maxAge: CORS_MAX_AGE_SECONDS,
};

describe('apply-bucket-cors', () => {
  it('applies the deployment rule and can be applied twice', async () => {
    const settings = testSettings(garageStorageEnv());
    const first = await applyBucketCors(settings.storage, ORIGIN);
    const second = await applyBucketCors(settings.storage, ORIGIN);
    expect(first).toHaveLength(1);
    expect(normalized(first[0]!)).toEqual(EXPECTED);
    expect(normalized(second[0]!)).toEqual(EXPECTED);
    const stored = await readRules();
    expect(stored).toHaveLength(1);
    expect(normalized(stored[0]!)).toEqual(EXPECTED);
  });

  it('runs as a CLI command with PUBLIC_BASE_URL as the origin and exits 0', async () => {
    const env = testEnv({ ...garageStorageEnv(), PUBLIC_BASE_URL: 'http://localhost:8080' });
    const { stdout } = await run('node', [CLI_ENTRY, 'apply-bucket-cors'], {
      env: { PATH: process.env['PATH'] ?? '', ...env },
      timeout: CLI_TIMEOUT_MS,
    });
    expect(stdout).toContain('http://localhost:8080');
    const stored = await readRules();
    expect(stored[0]?.AllowedOrigins).toEqual(['http://localhost:8080']);
    expect(stdout).not.toContain(env['S3_SECRET_ACCESS_KEY']);
  });
});
