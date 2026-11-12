import {
  decodeBytes,
  encodeBytes,
  entryOutboxPayloadSchemas,
  revisionConflictDetails,
} from '@kodakiko/contract';
import { bytesEqual, type PushFailure } from '@kodakiko/core';
import { ApiError } from '../../../adapters/http/http';
import type { EntryRow } from '../../../adapters/rowstore/rowstore';
import { HTTP_STATUS } from '../../../constants/http-constants';
import { CONFLICT_INLINE_RETRIES, UPDATE_RERUN_LIMIT } from '../../../constants/sync-constants';
import { SYNC_LOG_EVENTS } from '../../../keys/sync-keys';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor, ExecutorDeps, ExecutorResult } from './executor-types';
import { guarded } from './guard';

const CONFLICT_FAILURE: PushFailure = {
  kind: 'http',
  status: HTTP_STATUS.conflict,
  code: 'REVISION_CONFLICT',
  retryAfterSeconds: null,
};

type ServerCopy = { readonly revision: number; readonly metadataBlob: Uint8Array };

type Step = ExecutorResult | 'rerun' | 'resolved';

function storedServerCopy(entry: EntryRow, baseRevision: number): ServerCopy | null {
  if (
    entry.serverPendingBlob !== null &&
    entry.serverPendingRevision !== null &&
    entry.serverPendingRevision > baseRevision
  ) {
    return { revision: entry.serverPendingRevision, metadataBlob: entry.serverPendingBlob };
  }
  return null;
}

async function recordPosted(
  deps: ExecutorDeps,
  entryId: string,
  posted: Uint8Array,
  revision: number,
  updatedAt: string,
): Promise<boolean> {
  return deps.rowStore.transaction(async (tx) => {
    const current = await tx.entries.get(entryId);
    if (current === null) return true;
    const inSync = bytesEqual(current.metadataBlob, posted);
    await tx.entries.put({
      ...current,
      revision,
      baseMetadataBlob: posted,
      baseRevision: revision,
      serverPendingBlob: null,
      serverPendingRevision: null,
      updatedAt,
      localState: inSync ? 'synced' : 'pending_update',
    });
    return inSync;
  });
}

async function resolveConflict(
  deps: ExecutorDeps,
  entryId: string,
  server: ServerCopy,
  conflicts: number,
): Promise<Step> {
  if (conflicts >= CONFLICT_INLINE_RETRIES) return { failure: CONFLICT_FAILURE };
  const outcome = await deps.conflict.resolve(entryId, server);
  return outcome === 'adopted' ? 'done' : 'resolved';
}

async function markSynced(deps: ExecutorDeps, entryId: string): Promise<void> {
  await deps.rowStore.transaction(async (tx) => {
    const current = await tx.entries.get(entryId);
    if (current !== null && current.localState !== 'synced') {
      await tx.entries.put({ ...current, localState: 'synced' });
    }
  });
}

async function patch(deps: ExecutorDeps, entry: EntryRow, base: number, conflicts: number): Promise<Step> {
  const posted = entry.metadataBlob;
  try {
    const dto = await deps.http.request(
      SYNC_CALLS.patchEntry(entry.id, { metadataBlob: encodeBytes(posted), baseRevision: base }),
    );
    const inSync = await recordPosted(deps, entry.id, posted, dto.revision, dto.updatedAt);
    return inSync ? 'done' : 'rerun';
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'REVISION_CONFLICT') throw error;
    const details = revisionConflictDetails.safeParse(error.details);
    if (!details.success) throw error;
    if (details.data.revision <= base) {
      deps.log.warn(SYNC_LOG_EVENTS.rollbackRejected, {
        entryId: entry.id,
        pulledRevision: details.data.revision,
        baseRevision: base,
      });
      return { failure: CONFLICT_FAILURE };
    }
    return resolveConflict(
      deps,
      entry.id,
      { revision: details.data.revision, metadataBlob: decodeBytes(details.data.metadataBlob) },
      conflicts,
    );
  }
}

async function attempt(deps: ExecutorDeps, entryId: string, conflicts: number): Promise<Step> {
  const entry = await deps.rowStore.transaction((tx) => tx.entries.get(entryId));
  if (entry === null) return 'done';
  const base = entry.baseRevision ?? 0;
  if (base < 1 || entry.baseMetadataBlob === null) return 'waiting';
  const stored = storedServerCopy(entry, base);
  if (stored !== null) return resolveConflict(deps, entryId, stored, conflicts);
  if (bytesEqual(entry.metadataBlob, entry.baseMetadataBlob)) {
    await markSynced(deps, entryId);
    return 'done';
  }
  return patch(deps, entry, base, conflicts);
}

const run: Executor = async (item, deps) => {
  const { entryId } = entryOutboxPayloadSchemas.updateEntryMeta.parse(item.payload);
  let conflicts = 0;
  for (let round = 0; round < UPDATE_RERUN_LIMIT; round += 1) {
    const step = await attempt(deps, entryId, conflicts);
    if (step === 'resolved') conflicts += 1;
    else if (step !== 'rerun') return step;
  }
  return 'waiting';
};

export const updateEntryMetaExecutor: Executor = guarded(run);
