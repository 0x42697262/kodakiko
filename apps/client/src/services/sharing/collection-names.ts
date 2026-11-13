import { AD_CONTEXTS, openJson, type Sodium } from '@kodakiko/core';
import { CollectionMetadataV1 } from '@kodakiko/contract';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import { EN } from '../../text/en';
import type { EntryKeys } from '../keys/entry-keys';

const COLLECTION_METADATA_VERSION = 1;
const NO_MIGRATIONS = new Map<number, (raw: Record<string, unknown>) => Record<string, unknown>>();

export type CollectionNames = {
  readonly get: (collectionId: string) => Promise<string>;
  readonly clear: () => void;
};

export type CollectionNamesDeps = {
  readonly sodium: Sodium;
  readonly rowStore: RowStore;
  readonly entryKeys: Pick<EntryKeys, 'collectionKey'>;
};

export function createCollectionNames(deps: CollectionNamesDeps): CollectionNames {
  const cache = new Map<string, string>();
  let generation = 0;

  async function open(collectionId: string): Promise<string> {
    try {
      const row = await deps.rowStore.transaction((tx) => tx.collections.get(collectionId));
      if (row === null) return EN['shared.unnamed'];
      const key = await deps.entryKeys.collectionKey(collectionId);
      return openJson(
        deps.sodium,
        key,
        row.metadataBlob,
        CollectionMetadataV1,
        COLLECTION_METADATA_VERSION,
        NO_MIGRATIONS,
        AD_CONTEXTS.collectionMetadata,
        collectionId,
      ).name;
    } catch {
      return EN['shared.unnamed'];
    }
  }

  return {
    get: async (collectionId) => {
      const cached = cache.get(collectionId);
      if (cached !== undefined) return cached;
      const started = generation;
      const name = await open(collectionId);
      if (started === generation) cache.set(collectionId, name);
      return name;
    },
    clear: () => {
      generation += 1;
      cache.clear();
    },
  };
}
