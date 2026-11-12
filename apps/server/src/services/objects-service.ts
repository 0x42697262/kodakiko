import type { Kysely, Transaction } from 'kysely';
import { createUsersRepository } from '../adapters/db/users-repository.ts';
import { objectsRepository, type ObjectRow } from '../adapters/db/objects-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { StorageUnavailableError, UploadMissingError } from '../adapters/storage/errors.ts';
import type { ObjectStore } from '../adapters/storage/object-store.ts';
import { PRESIGN_RATE_WINDOW_MS, SECOND_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import type { ObjectState, ObjectVariant } from '../domain/enums.ts';
import { AppError } from '../domain/errors.ts';
import {
  chooseUploadMode,
  deleteAfter,
  expectedPartBytes,
  objectDeleteAfter,
  objectKey,
  partsComplete,
  quotaAllows,
  reissueUrlTtlSeconds,
  storedUploadPlan,
  type UploadPlan,
} from '../domain/objects.ts';
import type { Clock } from '../platform/clock.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';

export type PresignCommand = {
  readonly id: string;
  readonly mediaId: string;
  readonly variant: ObjectVariant;
  readonly sizeBytes: number;
};

export type PartUrl = { readonly partNumber: number; readonly url: string };

export type PresignView =
  | { readonly mode: 'single'; readonly url: string; readonly expiresAt: Date }
  | {
      readonly mode: 'multipart';
      readonly partSizeBytes: number;
      readonly partUrls: readonly PartUrl[];
      readonly expiresAt: Date;
    };

export type PartsView = {
  readonly partSizeBytes: number;
  readonly uploadedParts: readonly { readonly partNumber: number; readonly sizeBytes: number }[];
  readonly partUrls: readonly PartUrl[];
  readonly expiresAt: Date;
};

export type ObjectView = {
  readonly id: string;
  readonly mediaId: string;
  readonly variant: ObjectVariant;
  readonly sizeBytes: number;
  readonly state: ObjectState;
};

export type DownloadView = { readonly url: string; readonly expiresAt: Date };

export type ObjectUploadedHook = (trx: Transaction<Database>, entryId: string) => Promise<void>;

export type ObjectsServiceDeps = {
  readonly db: Kysely<Database>;
  readonly store: ObjectStore;
  readonly settings: Settings;
  readonly clock: Clock;
  readonly onObjectUploaded: ObjectUploadedHook;
  readonly logger: EventLogger;
};

class ReservationRaced extends Error {}

function partNumbersOf(partCount: number): number[] {
  return Array.from({ length: partCount }, (_, position) => position + 1);
}

function matchesPending(row: ObjectRow, userId: string, request: PresignCommand): boolean {
  return (
    row.ownerId === userId &&
    row.state === 'pending' &&
    row.mediaId === request.mediaId &&
    row.variant === request.variant &&
    row.sizeBytes === request.sizeBytes
  );
}

function objectView(row: ObjectRow): ObjectView {
  if (row.mediaId === null) throw new AppError('NOT_FOUND');
  return {
    id: row.id,
    mediaId: row.mediaId,
    variant: row.variant,
    sizeBytes: row.sizeBytes,
    state: row.state,
  };
}

async function viaStore<Result>(operation: () => Promise<Result>): Promise<Result> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof StorageUnavailableError) throw new AppError('STORAGE_UNAVAILABLE');
    throw error;
  }
}

export function createObjectsService(deps: ObjectsServiceDeps) {
  const { db, store, settings, clock, onObjectUploaded, logger } = deps;
  const { storage, limits } = settings;

  function expiryAfter(now: Date, ttlSeconds: number): Date {
    return new Date(now.getTime() + ttlSeconds * SECOND_MS);
  }

  async function findOwned(userId: string, id: string): Promise<ObjectRow> {
    const row = await objectsRepository.findById(db, id);
    if (row === undefined || row.ownerId !== userId) throw new AppError('NOT_FOUND');
    return row;
  }

  async function reserve(
    userId: string,
    request: PresignCommand,
    plan: UploadPlan,
    now: Date,
  ): Promise<ObjectRow> {
    const row: ObjectRow = {
      id: request.id,
      ownerId: userId,
      mediaId: request.mediaId,
      variant: request.variant,
      objectKey: objectKey(userId, request.id),
      sizeBytes: request.sizeBytes,
      state: 'pending',
      multipartUploadId: null,
      partSizeBytes: plan.mode === 'multipart' ? plan.partSizeBytes : null,
      createdAt: now,
      uploadedAt: null,
      deleteAfter: null,
    };
    await db.transaction().execute(async (trx) => {
      const user = await createUsersRepository(trx).findByIdForUpdate(userId);
      if (user === undefined) throw new AppError('NOT_FOUND');
      const since = new Date(now.getTime() - PRESIGN_RATE_WINDOW_MS);
      const recent = await objectsRepository.countPresignsSince(trx, userId, since);
      if (recent >= storage.maxPresignsPerHour) throw new AppError('RATE_LIMITED');
      if (
        !quotaAllows(user.usedBytes, user.quotaBytes, limits.defaultQuotaBytes, request.sizeBytes)
      ) {
        logger.warn({
          event: LOG_EVENTS.objectQuotaRefused,
          userId,
          requested: request.sizeBytes,
          used: user.usedBytes,
          quota: user.quotaBytes ?? limits.defaultQuotaBytes,
        });
        throw new AppError('QUOTA_EXCEEDED');
      }
      const inserted = await objectsRepository.insertPending(trx, row);
      if (inserted === 'idConflict') throw new ReservationRaced();
      if (inserted === 'pendingExists') throw new AppError('ID_CONFLICT');
      await objectsRepository.adjustUsedBytes(trx, userId, request.sizeBytes);
    });
    return row;
  }

  async function ensureUploadId(row: ObjectRow, now: Date): Promise<string> {
    if (row.multipartUploadId !== null) return row.multipartUploadId;
    let uploadId: string;
    let stored: boolean;
    try {
      uploadId = await store.createMultipartUpload(row.objectKey);
      stored = await objectsRepository.setMultipartUploadId(db, row.id, uploadId);
    } catch (error) {
      await objectsRepository.markDeleting(db, row.id, now);
      if (error instanceof StorageUnavailableError) throw new AppError('STORAGE_UNAVAILABLE');
      throw error;
    }
    if (stored) return uploadId;
    await store.abortMultipartUpload(row.objectKey, uploadId).catch((error: unknown) => {
      logger.warn({
        event: LOG_EVENTS.objectAbortFailed,
        objectId: row.id,
        message: error instanceof Error ? error.message : String(error),
      });
    });
    const winner = await objectsRepository.findById(db, row.id);
    if (winner === undefined || winner.multipartUploadId === null) throw new AppError('NOT_FOUND');
    return winner.multipartUploadId;
  }

  async function partUrlsFor(
    row: ObjectRow,
    plan: Extract<UploadPlan, { mode: 'multipart' }>,
    uploadId: string,
    partNumbers: readonly number[],
    ttlSeconds: number,
  ): Promise<PartUrl[]> {
    return viaStore(() =>
      Promise.all(
        partNumbers.map(async (partNumber) => ({
          partNumber,
          url: await store.presignPart(
            row.objectKey,
            uploadId,
            partNumber,
            expectedPartBytes(plan, partNumber),
            ttlSeconds,
          ),
        })),
      ),
    );
  }

  async function issueUrls(
    row: ObjectRow,
    plan: UploadPlan,
    now: Date,
    ttlSeconds: number,
  ): Promise<PresignView> {
    const expiresAt = expiryAfter(now, ttlSeconds);
    if (plan.mode === 'single') {
      const url = await viaStore(() => store.presignPut(row.objectKey, row.sizeBytes, ttlSeconds));
      return { mode: 'single', url, expiresAt };
    }
    const uploadId = await ensureUploadId(row, now);
    const partUrls = await partUrlsFor(
      row,
      plan,
      uploadId,
      partNumbersOf(plan.partCount),
      ttlSeconds,
    );
    return { mode: 'multipart', partSizeBytes: plan.partSizeBytes, partUrls, expiresAt };
  }

  async function presign(userId: string, request: PresignCommand): Promise<PresignView> {
    const now = clock.now();
    if (request.sizeBytes > limits.maxUploadBytes) throw new AppError('PAYLOAD_TOO_LARGE');
    const owner = await objectsRepository.findMediaOwner(db, request.mediaId);
    if (owner === undefined || owner.ownerId !== userId) throw new AppError('NOT_FOUND');
    const existing = await objectsRepository.findById(db, request.id);
    if (existing !== undefined) return reissue(existing, userId, request, now);
    const plan = chooseUploadMode(request.sizeBytes, storage);
    let row: ObjectRow;
    try {
      row = await reserve(userId, request, plan, now);
    } catch (error) {
      if (!(error instanceof ReservationRaced)) throw error;
      const raced = await objectsRepository.findById(db, request.id);
      if (raced === undefined) throw new AppError('ID_CONFLICT');
      return reissue(raced, userId, request, now);
    }
    const view = await issueUrls(row, plan, now, storage.uploadUrlTtlSeconds);
    logger.info({
      event: LOG_EVENTS.objectPresigned,
      userId,
      objectId: row.id,
      sizeBytes: row.sizeBytes,
      mode: plan.mode,
    });
    return view;
  }

  async function reissue(
    existing: ObjectRow,
    userId: string,
    request: PresignCommand,
    now: Date,
  ): Promise<PresignView> {
    if (!matchesPending(existing, userId, request)) throw new AppError('ID_CONFLICT');
    const ttlSeconds = reissueUrlTtlSeconds(
      now,
      existing.createdAt,
      storage.pendingUploadHours,
      storage.uploadUrlTtlSeconds,
    );
    if (ttlSeconds <= 0) throw new AppError('ID_CONFLICT');
    return issueUrls(existing, storedUploadPlan(existing.sizeBytes, existing.partSizeBytes), now, ttlSeconds);
  }

  async function parts(userId: string, id: string): Promise<PartsView> {
    const now = clock.now();
    const row = await findOwned(userId, id);
    const plan = storedUploadPlan(row.sizeBytes, row.partSizeBytes);
    if (row.state !== 'pending' || plan.mode !== 'multipart' || row.multipartUploadId === null) {
      throw new AppError('NOT_FOUND');
    }
    const uploadId = row.multipartUploadId;
    const listed = await viaStore(() => store.listParts(row.objectKey, uploadId)).catch(
      (error: unknown) => {
        if (error instanceof UploadMissingError) throw new AppError('NOT_FOUND');
        throw error;
      },
    );
    const uploadedNumbers = new Set(listed.map((part) => part.partNumber));
    const missing = partNumbersOf(plan.partCount).filter((number) => !uploadedNumbers.has(number));
    return {
      partSizeBytes: plan.partSizeBytes,
      uploadedParts: [...listed]
        .sort((left, right) => left.partNumber - right.partNumber)
        .map((part) => ({ partNumber: part.partNumber, sizeBytes: part.sizeBytes })),
      partUrls: await partUrlsFor(row, plan, uploadId, missing, storage.uploadUrlTtlSeconds),
      expiresAt: expiryAfter(now, storage.uploadUrlTtlSeconds),
    };
  }

  async function headOrIncomplete(row: ObjectRow): Promise<{ sizeBytes: number }> {
    const head = await viaStore(() => store.headObject(row.objectKey));
    if (head === null) throw new AppError('UPLOAD_INCOMPLETE');
    return head;
  }

  async function completeMultipart(row: ObjectRow, plan: UploadPlan): Promise<void> {
    if (row.multipartUploadId === null) throw new AppError('UPLOAD_INCOMPLETE');
    const uploadId = row.multipartUploadId;
    try {
      const listed = await viaStore(() => store.listParts(row.objectKey, uploadId));
      if (!partsComplete(plan, listed)) throw new AppError('UPLOAD_INCOMPLETE');
      await viaStore(() =>
        store.completeMultipartUpload(
          row.objectKey,
          uploadId,
          listed.map((part) => ({ partNumber: part.partNumber, etag: part.etag })),
        ),
      );
    } catch (error) {
      if (!(error instanceof UploadMissingError)) throw error;
    }
  }

  async function confirm(userId: string, id: string): Promise<ObjectView> {
    const now = clock.now();
    const row = await findOwned(userId, id);
    if (row.state === 'uploaded') return objectView(row);
    if (row.state === 'deleting' || row.mediaId === null) throw new AppError('NOT_FOUND');
    const mediaId = row.mediaId;
    const plan = storedUploadPlan(row.sizeBytes, row.partSizeBytes);
    if (plan.mode === 'multipart') await completeMultipart(row, plan);
    const head = await headOrIncomplete(row);
    if (head.sizeBytes !== row.sizeBytes) {
      await objectsRepository.markDeleting(
        db,
        row.id,
        objectDeleteAfter(now, storage),
      );
      throw new AppError('UPLOAD_SIZE_MISMATCH');
    }
    const uploaded = await db.transaction().execute(async (trx) => {
      const marked = await objectsRepository.markUploaded(
        trx,
        row.id,
        now,
        deleteAfter(now, storage.objectGraceDays),
      );
      const owner = await objectsRepository.findMediaOwner(trx, mediaId);
      if (marked === undefined || owner === undefined) throw new AppError('NOT_FOUND');
      await onObjectUploaded(trx, owner.entryId);
      return marked;
    });
    logger.info({
      event: LOG_EVENTS.objectConfirmed,
      userId,
      objectId: uploaded.id,
      sizeBytes: uploaded.sizeBytes,
    });
    return objectView(uploaded);
  }

  async function download(userId: string, id: string): Promise<DownloadView> {
    const now = clock.now();
    const row = await objectsRepository.findById(db, id);
    if (
      row === undefined ||
      row.state !== 'uploaded' ||
      !(await objectsRepository.canRead(db, userId, id))
    ) {
      throw new AppError('NOT_FOUND');
    }
    const url = await viaStore(() => store.presignGet(row.objectKey, storage.downloadUrlTtlSeconds));
    return { url, expiresAt: expiryAfter(now, storage.downloadUrlTtlSeconds) };
  }

  return { presign, parts, confirm, download };
}

export type ObjectsService = ReturnType<typeof createObjectsService>;
