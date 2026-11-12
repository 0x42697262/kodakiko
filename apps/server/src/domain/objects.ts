import {
  DAY_MS,
  HOUR_MS,
  OBJECT_KEY_SEPARATOR,
  OBJECT_KEY_VERSION,
  SECOND_MS,
} from '../config/constants.ts';
import type { StorageSettings } from '../config/settings.ts';

export type UploadSizing = Pick<StorageSettings, 'singlePartMaxBytes' | 'multipartPartBytes'>;

export type UploadPlan =
  | { readonly mode: 'single' }
  | {
      readonly mode: 'multipart';
      readonly partSizeBytes: number;
      readonly partCount: number;
      readonly sizeBytes: number;
    };

export type PartSummary = { readonly partNumber: number; readonly sizeBytes: number };

export function objectKey(ownerId: string, objectId: string): string {
  return [OBJECT_KEY_VERSION, ownerId, objectId].join(OBJECT_KEY_SEPARATOR);
}

export function chooseUploadMode(sizeBytes: number, settings: UploadSizing): UploadPlan {
  if (sizeBytes <= settings.singlePartMaxBytes) return { mode: 'single' };
  const partSizeBytes = settings.multipartPartBytes;
  return {
    mode: 'multipart',
    partSizeBytes,
    partCount: Math.ceil(sizeBytes / partSizeBytes),
    sizeBytes,
  };
}

export function quotaAllows(
  usedBytes: number,
  quotaBytes: number | null,
  defaultQuotaBytes: number,
  requestedBytes: number,
): boolean {
  const limit = quotaBytes ?? defaultQuotaBytes;
  return usedBytes + requestedBytes <= limit;
}

export function storedUploadPlan(sizeBytes: number, partSizeBytes: number | null): UploadPlan {
  if (partSizeBytes === null) return { mode: 'single' };
  return {
    mode: 'multipart',
    partSizeBytes,
    partCount: Math.ceil(sizeBytes / partSizeBytes),
    sizeBytes,
  };
}

export function expectedPartBytes(
  plan: Extract<UploadPlan, { mode: 'multipart' }>,
  partNumber: number,
): number {
  if (partNumber < plan.partCount) return plan.partSizeBytes;
  return plan.sizeBytes - plan.partSizeBytes * (plan.partCount - 1);
}

export function partsComplete(plan: UploadPlan, uploaded: readonly PartSummary[]): boolean {
  if (plan.mode !== 'multipart') return false;
  if (uploaded.length !== plan.partCount) return false;
  const sizeByNumber = new Map(uploaded.map((part) => [part.partNumber, part.sizeBytes]));
  if (sizeByNumber.size !== plan.partCount) return false;
  for (let partNumber = 1; partNumber <= plan.partCount; partNumber += 1) {
    if (sizeByNumber.get(partNumber) !== expectedPartBytes(plan, partNumber)) return false;
  }
  return true;
}

export function deleteAfter(now: Date, graceDays: number): Date {
  return new Date(now.getTime() + graceDays * DAY_MS);
}

export function staleUploadCutoff(now: Date, pendingUploadHours: number): Date {
  return new Date(now.getTime() - pendingUploadHours * HOUR_MS);
}

export function abandonedDeleteAfter(
  now: Date,
  graceDays: number,
  uploadUrlTtlSeconds: number,
): Date {
  return new Date(now.getTime() + Math.max(graceDays * DAY_MS, uploadUrlTtlSeconds * SECOND_MS));
}

export function reissueUrlTtlSeconds(
  now: Date,
  createdAt: Date,
  pendingUploadHours: number,
  uploadUrlTtlSeconds: number,
): number {
  const expiresAtMs = createdAt.getTime() + pendingUploadHours * HOUR_MS;
  return Math.min(uploadUrlTtlSeconds, Math.floor((expiresAtMs - now.getTime()) / SECOND_MS));
}

export function objectDeleteAfter(
  now: Date,
  storage: Pick<StorageSettings, 'objectGraceDays' | 'uploadUrlTtlSeconds'>,
): Date {
  return abandonedDeleteAfter(now, storage.objectGraceDays, storage.uploadUrlTtlSeconds);
}
