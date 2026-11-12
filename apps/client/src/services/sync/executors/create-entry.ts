import { decodeBytes, encodeBytes, entryCreateRequest, entryOutboxPayloadSchemas } from '@kodakiko/contract';
import { bytesEqual, type PushFailure } from '@kodakiko/core';
import { ApiError } from '../../../adapters/http/http';
import type { EntryRow, MediaRow, MembershipRow } from '../../../adapters/rowstore/rowstore';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor, ExecutorDeps, ExecutorResult } from './executor-types';
import { guarded } from './guard';
import { advanceUploads } from './upload-step';

const CREATED_REVISION = 1;


type Snapshot = {
  readonly entry: EntryRow;
  readonly memberships: readonly MembershipRow[];
  readonly media: readonly MediaRow[];
};

async function adoptServerCopy(
  deps: ExecutorDeps,
  entryId: string,
  blob: Uint8Array,
  revision: number,
): Promise<void> {
  await deps.rowStore.transaction(async (tx) => {
    const current = await tx.entries.get(entryId);
    if (current === null) return;
    await tx.entries.put({
      ...current,
      baseMetadataBlob: blob,
      baseRevision: revision,
      revision,
      serverPendingBlob: null,
      serverPendingRevision: null,
    });
  });
}

async function adoptExistingEntry(
  deps: ExecutorDeps,
  entry: EntryRow,
  error: ApiError,
): Promise<'adopted' | ExecutorResult> {
  const existing = await deps.http.request(SYNC_CALLS.getEntry(entry.id));
  if (existing.ownerId !== entry.ownerId) return { failure: toFailure(error) };
  await adoptServerCopy(deps, entry.id, decodeBytes(existing.metadataBlob), existing.revision);
  return 'adopted';
}

function toFailure(error: ApiError): PushFailure {
  return { kind: 'http', status: error.status, code: error.code, retryAfterSeconds: error.retryAfterSeconds };
}

async function postEntry(
  deps: ExecutorDeps,
  snapshot: Snapshot,
): Promise<'posted' | ExecutorResult> {
  const { entry } = snapshot;
  const posted = entry.metadataBlob;
  try {
    await deps.http.request(
      SYNC_CALLS.createEntry(
        entryCreateRequest.parse({
          id: entry.id,
          purpose: entry.purpose,
          metadataBlob: encodeBytes(posted),
          memberships: snapshot.memberships.map((membership) => ({
            collectionId: membership.collectionId,
            entryKeyByCollection: encodeBytes(membership.entryKeyWrap),
          })),
          media: snapshot.media.map((media) => ({
            mediaId: media.id,
            mediaKeyByEntry: encodeBytes(media.mediaKeyWrap),
          })),
        }),
      ),
    );
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'ID_CONFLICT') throw error;
    const outcome = await adoptExistingEntry(deps, entry, error);
    return outcome === 'adopted' ? 'posted' : outcome;
  }
  await adoptServerCopy(deps, entry.id, posted, CREATED_REVISION);
  return 'posted';
}

const run: Executor = async (item, deps) => {
  const { entryId } = entryOutboxPayloadSchemas.createEntry.parse(item.payload);
  const snapshot = await deps.rowStore.transaction(async (tx) => ({
    entry: await tx.entries.get(entryId),
    memberships: await tx.memberships.byEntry(entryId),
    media: await tx.media.byEntry(entryId),
  }));
  const { entry } = snapshot;
  if (entry === null) return 'done';
  if ((entry.baseRevision ?? 0) < CREATED_REVISION) {
    if (entry.serverPendingBlob !== null && (entry.serverPendingRevision ?? 0) >= CREATED_REVISION) {
      await adoptServerCopy(deps, entryId, entry.serverPendingBlob, entry.serverPendingRevision ?? CREATED_REVISION);
    } else {
      const outcome = await postEntry(deps, { ...snapshot, entry });
      if (outcome !== 'posted') return outcome;
    }
  }
  const uploaded = await advanceUploads(
    deps,
    snapshot.media.map((media) => media.id),
  );
  if (!uploaded) return 'waiting';
  await deps.rowStore.transaction(async (tx) => {
    const current = await tx.entries.get(entryId);
    if (current === null) return;
    const unchanged =
      current.baseMetadataBlob !== null && bytesEqual(current.metadataBlob, current.baseMetadataBlob);
    await tx.entries.put({ ...current, localState: unchanged ? 'synced' : 'pending_update' });
  });
  deps.events.emit('entriesChanged', [entryId]);
  return 'done';
};

export const createEntryExecutor: Executor = guarded(run);
