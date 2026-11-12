import {
  GetBucketCorsCommand,
  PutBucketCorsCommand,
  type CORSRule,
} from '@aws-sdk/client-s3';
import { BUCKET_CORS_RULE } from '../../config/constants.ts';
import type { StorageSettings } from '../../config/settings.ts';
import { createS3Clients } from './s3-object-store.ts';

export function bucketCorsRule(origin: string): CORSRule {
  return {
    AllowedOrigins: [origin],
    AllowedMethods: [...BUCKET_CORS_RULE.methods],
    AllowedHeaders: [...BUCKET_CORS_RULE.headers],
    ExposeHeaders: [...BUCKET_CORS_RULE.exposeHeaders],
    MaxAgeSeconds: BUCKET_CORS_RULE.maxAgeSeconds,
  };
}

export async function applyBucketCors(
  config: StorageSettings,
  origin: string,
): Promise<readonly CORSRule[]> {
  const { internalClient, presignClient } = createS3Clients(config);
  try {
    await internalClient.send(
      new PutBucketCorsCommand({
        Bucket: config.bucket,
        CORSConfiguration: { CORSRules: [bucketCorsRule(origin)] },
      }),
    );
    const applied = await internalClient.send(new GetBucketCorsCommand({ Bucket: config.bucket }));
    return applied.CORSRules ?? [];
  } finally {
    internalClient.destroy();
    presignClient.destroy();
  }
}
