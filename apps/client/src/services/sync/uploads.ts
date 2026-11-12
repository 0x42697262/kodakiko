import type { PresignResponse } from '@kodakiko/contract';
import { ApiError, NetworkError, type HttpClient } from '../../adapters/http/http';
import type { Logger } from '../../adapters/logger';
import type { ObjectRow, RowStore } from '../../adapters/rowstore/rowstore';
import type { UploadEvent, UploadJob, UploadPart, Uploader } from '../../adapters/uploader/uploader';
import type { AppEventBus } from '../../composition/events';
import { FIRST_PART_NUMBER, SINGLE_PART_NUMBER } from '../../constants/sync-constants';
import { HTTP_STATUS } from '../../constants/http-constants';
import { MISSING_FILE_ERROR_CODE, SYNC_LOG_EVENTS } from '../../keys/sync-keys';
import { isUploadItem, mediaIdOfPayload, entryIdOfPayload } from './outbox-rows';
import { SYNC_CALLS } from './sync-calls';

export type UploadPolicy = { readonly allowed: (sizeBytes: number) => boolean };

export type ThumbnailCache = {
  readonly afterUpload: (objectId: string) => void | Promise<void>;
};

export type NotificationPromptPort = { readonly ensureAsked: () => Promise<void> };

export type UploadCoordinatorDeps = {
  readonly http: HttpClient;
  readonly rowStore: RowStore;
  readonly uploader: Uploader;
  readonly events: AppEventBus;
  readonly uploadPolicy: UploadPolicy;
  readonly thumbnailCache: ThumbnailCache;
  readonly notificationPrompt: NotificationPromptPort;
  readonly log: Logger;
};

export type UploadCoordinator = {
  readonly enqueueObjectUploads: (objectIds: readonly string[]) => Promise<void>;
  readonly reconcileUploads: () => Promise<void>;
  readonly handleEvent: (event: UploadEvent) => Promise<void>;
  readonly heldBackCount: () => number;
  readonly onObjectUploaded: (listener: (entryId: string) => void) => () => void;
};

type ObjectContext = { readonly object: ObjectRow; readonly entryId: string | null };

type PreparedUpload = { readonly objectId: string; readonly job: UploadJob };

function partsFromPresign(response: PresignResponse, sizeBytes: number): readonly UploadPart[] {
  if (response.mode === 'single') {
    return [{ partNumber: SINGLE_PART_NUMBER, url: response.url, offset: 0, length: sizeBytes }];
  }
  return response.partUrls.map((part) => partFromNumber(part.partNumber, part.url, response.partSizeBytes, sizeBytes));
}

function partFromNumber(
  partNumber: number,
  url: string,
  partSizeBytes: number,
  sizeBytes: number,
): UploadPart {
  const offset = (partNumber - FIRST_PART_NUMBER) * partSizeBytes;
  return { partNumber, url, offset, length: Math.min(partSizeBytes, sizeBytes - offset) };
}

function isTransient(error: unknown): boolean {
  return (
    error instanceof NetworkError ||
    (error instanceof ApiError &&
      (error.status >= HTTP_STATUS.serverErrorMin || error.status === HTTP_STATUS.tooManyRequests))
  );
}

export function createUploadCoordinator(deps: UploadCoordinatorDeps): UploadCoordinator {
  const { http, rowStore, uploader, events } = deps;
  const heldBack = new Set<string>();
  const uploadedListeners = new Set<(entryId: string) => void>();

  async function contextOf(objectId: string): Promise<ObjectContext | null> {
    return rowStore.transaction(async (tx) => {
      const object = await tx.objects.get(objectId);
      if (object === null) return null;
      const media = await tx.media.get(object.mediaId);
      return { object, entryId: media?.entryId ?? null };
    });
  }

  async function setUploadState(
    objectId: string,
    state: ObjectRow['uploadState'],
    entryId: string | null,
  ): Promise<void> {
    const changed = await rowStore.transaction(async (tx) => {
      const object = await tx.objects.get(objectId);
      if (object === null || object.uploadState === state || object.uploadState === 'uploaded') {
        return false;
      }
      await tx.objects.put({ ...object, uploadState: state });
      return true;
    });
    if (changed && entryId !== null) events.emit('entriesChanged', [entryId]);
  }

  async function prepare(context: ObjectContext): Promise<PreparedUpload> {
    const { object } = context;
    const response = await http.request(
      SYNC_CALLS.presign({
        id: object.id,
        mediaId: object.mediaId,
        variant: object.variant,
        sizeBytes: object.sizeBytes,
      } as Parameters<typeof SYNC_CALLS.presign>[0]),
    );
    return {
      objectId: object.id,
      job: { objectId: object.id, parts: partsFromPresign(response, object.sizeBytes) },
    };
  }

  async function submit(
    prepared: readonly PreparedUpload[],
    contexts: ReadonlyMap<string, ObjectContext>,
  ): Promise<void> {
    if (prepared.length === 0) return;
    for (const item of prepared) {
      await setUploadState(item.objectId, 'uploading', contexts.get(item.objectId)?.entryId ?? null);
    }
    deps.notificationPrompt.ensureAsked().catch(() => undefined);
    try {
      await uploader.enqueue(prepared.map((item) => item.job));
    } catch (error) {
      for (const item of prepared) {
        await setUploadState(item.objectId, 'queued', contexts.get(item.objectId)?.entryId ?? null);
      }
      throw error;
    }
  }

  async function enqueueObjectUploads(objectIds: readonly string[]): Promise<void> {
    const contexts = new Map<string, ObjectContext>();
    const prepared: PreparedUpload[] = [];
    for (const objectId of objectIds) {
      const context = await contextOf(objectId);
      if (context === null || context.object.uploadState === 'uploaded' || context.object.uploadState === 'dead') {
        continue;
      }
      contexts.set(objectId, context);
      await setUploadState(objectId, 'queued', context.entryId);
      if (!deps.uploadPolicy.allowed(context.object.sizeBytes)) {
        heldBack.add(objectId);
        continue;
      }
      heldBack.delete(objectId);
      prepared.push(await prepare(context));
    }
    await submit(prepared, contexts);
  }

  async function markDead(context: ObjectContext, code: string): Promise<void> {
    const { object, entryId } = context;
    await rowStore.transaction(async (tx) => {
      await tx.objects.put({ ...object, uploadState: 'dead' });
      for (const row of await tx.outbox.all()) {
        const sameEntry = entryIdOfPayload(row.payload) === entryId;
        const mediaId = mediaIdOfPayload(row.payload);
        const sameMedia = mediaId === null || mediaId === object.mediaId;
        if (entryId !== null && sameEntry && sameMedia && isUploadItem(row) && row.state === 'queued') {
          await tx.outbox.update({ ...row, state: 'dead', lastErrorCode: code });
        }
      }
    });
    if (entryId !== null) events.emit('entriesChanged', [entryId]);
  }

  async function resume(context: ObjectContext): Promise<void> {
    const { object } = context;
    let missing;
    try {
      missing = await http.request(SYNC_CALLS.parts(object.id));
    } catch (error) {
      if (error instanceof ApiError && error.status === HTTP_STATUS.notFound) {
        const fresh = await prepare(context);
        await submit([fresh], new Map([[object.id, context]]));
        return;
      }
      throw error;
    }
    if (missing.partUrls.length === 0) return;
    const parts = missing.partUrls.map((part) =>
      partFromNumber(part.partNumber, part.url, missing.partSizeBytes, object.sizeBytes),
    );
    await submit([{ objectId: object.id, job: { objectId: object.id, parts } }], new Map([[object.id, context]]));
  }

  async function confirmOrResume(context: ObjectContext): Promise<void> {
    const { object, entryId } = context;
    try {
      await http.request(SYNC_CALLS.confirm(object.id));
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UPLOAD_INCOMPLETE') {
        await resume(context);
        return;
      }
      if (error instanceof ApiError && error.status === HTTP_STATUS.notFound) {
        const fresh = await prepare(context);
        await submit([fresh], new Map([[object.id, context]]));
        return;
      }
      if (error instanceof ApiError && error.status === HTTP_STATUS.unauthorized) return;
      if (isTransient(error)) {
        deps.log.warn(SYNC_LOG_EVENTS.uploadConfirmFailed, { objectId: object.id });
        return;
      }
      if (error instanceof ApiError) {
        await markDead(context, error.code);
        return;
      }
      throw error;
    }
    await setUploadState(object.id, 'uploaded', entryId);
    heldBack.delete(object.id);
    if (object.variant === 'thumbnail') await deps.thumbnailCache.afterUpload(object.id);
    if (entryId !== null) {
      events.emit('entriesChanged', [entryId]);
      for (const listener of [...uploadedListeners]) listener(entryId);
    }
  }

  async function handleEvent(event: UploadEvent): Promise<void> {
    if (event.type === 'progress' || event.type === 'stopped') return;
    const context = await contextOf(event.objectId);
    if (context === null) return;
    if (event.type === 'completed') {
      await confirmOrResume(context);
      return;
    }
    if (event.reason === 'expired') {
      const fresh = await prepare(context);
      await submit([fresh], new Map([[context.object.id, context]]));
    } else if (event.reason === 'missingFile') {
      await markDead(context, MISSING_FILE_ERROR_CODE);
    }
  }

  async function reconcileObject(context: ObjectContext, status: string | undefined): Promise<void> {
    if (context.object.uploadState === 'uploading') {
      if (status === 'uploading' || status === 'queued') return;
      await confirmOrResume(context);
      return;
    }
    await enqueueObjectUploads([context.object.id]);
  }

  async function reconcileUploads(): Promise<void> {
    const pending = await rowStore.transaction(async (tx) =>
      (await tx.objects.all()).filter(
        (object) => object.uploadState === 'uploading' || object.uploadState === 'queued',
      ),
    );
    if (pending.length === 0) return;
    const statuses = await uploader.statuses();
    for (const object of pending) {
      const context = await contextOf(object.id);
      if (context === null) continue;
      try {
        await reconcileObject(context, statuses.get(object.id));
      } catch {
        deps.log.warn(SYNC_LOG_EVENTS.uploadConfirmFailed, { objectId: object.id });
      }
    }
  }

  return {
    enqueueObjectUploads,
    reconcileUploads,
    handleEvent,
    heldBackCount: () => heldBack.size,
    onObjectUploaded: (listener) => {
      uploadedListeners.add(listener);
      return () => {
        uploadedListeners.delete(listener);
      };
    },
  };
}
