import { BUCKET_CHECK_NAME, READY_CHECK_TIMEOUT_MS } from '../config/constants.ts';
import type { ObjectStore } from '../adapters/storage/object-store.ts';
import { SYSTEM_TEXT } from '../text/system.ts';
import { STORAGE_TEXT } from '../text/storage.ts';

export type ReadinessCheck = { name: string; check(): Promise<void> };

export async function failedChecks(checks: readonly ReadinessCheck[]): Promise<string[]> {
  const outcomes = await Promise.allSettled(checks.map((entry) => entry.check()));
  return checks.flatMap((entry, position) =>
    outcomes[position]?.status === 'rejected' ? [entry.name] : [],
  );
}

export async function withReadinessTimeout(operation: () => Promise<unknown>): Promise<void> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(SYSTEM_TEXT.readinessTimeout)), READY_CHECK_TIMEOUT_MS);
  });
  try {
    await Promise.race([operation(), timeout]);
  } finally {
    clearTimeout(timer);
  }
}

export function bucketCheck(store: ObjectStore): ReadinessCheck {
  return {
    name: BUCKET_CHECK_NAME,
    async check() {
      await withReadinessTimeout(async () => {
        if (!(await store.headBucket())) throw new Error(STORAGE_TEXT.bucketNotFound);
      });
    },
  };
}
