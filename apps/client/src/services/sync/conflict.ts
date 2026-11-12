import { EntryMetadataV1 } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  bytesEqual,
  mergeMetadata,
  metadataEquivalent,
  openJson,
  sealJson,
  type Sodium,
} from '@kodakiko/core';
import type { Clock } from '../../adapters/clock';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import { CONFLICT_RESOLVE_ATTEMPTS } from '../../constants/sync-constants';
import type { EntryKeys } from '../keys/entry-keys';

const METADATA_SCHEMA_VERSION = 1;
const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();

export type ServerEntryVersion = { readonly revision: number; readonly metadataBlob: Uint8Array };

export type ConflictOutcome = 'adopted' | 'retry';

export type ConflictResolver = {
  readonly resolve: (entryId: string, server: ServerEntryVersion) => Promise<ConflictOutcome>;
};

export type ConflictResolverDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly entryKeys: EntryKeys;
  readonly clock: Clock;
};

export function createConflictResolver(deps: ConflictResolverDeps): ConflictResolver {
  const { sodium, rowStore } = deps;

  function open(entryKey: Uint8Array, entryId: string, blob: Uint8Array) {
    return openJson(
      sodium,
      entryKey,
      blob,
      EntryMetadataV1,
      METADATA_SCHEMA_VERSION,
      NO_MIGRATIONS,
      AD_CONTEXTS.entryMetadata,
      entryId,
    );
  }

  async function attempt(
    entryId: string,
    server: ServerEntryVersion,
  ): Promise<ConflictOutcome | 'changed'> {
    const entry = await rowStore.transaction((tx) => tx.entries.get(entryId));
    if (entry === null) return 'adopted';
    const entryKey = await deps.entryKeys.entryKey(entryId);
    const serverMetadata = open(entryKey, entryId, server.metadataBlob);
    const localMetadata = open(entryKey, entryId, entry.metadataBlob);
    const ancestor =
      entry.baseMetadataBlob === null
        ? serverMetadata
        : open(entryKey, entryId, entry.baseMetadataBlob);
    const merged = mergeMetadata(ancestor, localMetadata, serverMetadata, deps.clock.nowIso());
    const adopt = metadataEquivalent(merged, serverMetadata);
    const mergedBlob = adopt
      ? server.metadataBlob
      : sealJson(sodium, entryKey, merged, AD_CONTEXTS.entryMetadata, entryId);
    const stored = await rowStore.transaction(async (tx) => {
      const current = await tx.entries.get(entryId);
      if (current === null) return true;
      if (
        !bytesEqual(current.metadataBlob, entry.metadataBlob) ||
        current.baseRevision !== entry.baseRevision
      ) {
        return false;
      }
      await tx.entries.put({
        ...current,
        revision: server.revision,
        metadataBlob: mergedBlob,
        baseMetadataBlob: server.metadataBlob,
        baseRevision: server.revision,
        serverPendingBlob: null,
        serverPendingRevision: null,
        localState: adopt ? 'synced' : 'pending_update',
      });
      return true;
    });
    if (!stored) return 'changed';
    return adopt ? 'adopted' : 'retry';
  }

  async function resolve(entryId: string, server: ServerEntryVersion): Promise<ConflictOutcome> {
    for (let round = 0; round < CONFLICT_RESOLVE_ATTEMPTS; round += 1) {
      const outcome = await attempt(entryId, server);
      if (outcome !== 'changed') return outcome;
    }
    return 'retry';
  }

  return { resolve };
}
