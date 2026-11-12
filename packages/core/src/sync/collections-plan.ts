import type { COLLECTION_KINDS, COLLECTION_ROLES } from '@kodakiko/contract';

export type CollectionKind = (typeof COLLECTION_KINDS)[number];
export type CollectionRole = (typeof COLLECTION_ROLES)[number];

export type RemoteCollectionState = {
  readonly id: string;
  readonly kind: CollectionKind;
  readonly role: CollectionRole;
  readonly lastSeq: number;
  readonly tombstonesPrunedThroughSeq: number;
  readonly metaUpdatedAt: string;
};

export type LocalCollectionState = {
  readonly id: string;
  readonly cursorSeq: number;
  readonly metaUpdatedAt: string;
};

export type CollectionsPlan = {
  readonly refetch: readonly string[];
  readonly pull: readonly string[];
  readonly resync: readonly string[];
  readonly drop: readonly string[];
};

export function planCollections(
  remote: readonly RemoteCollectionState[],
  local: readonly LocalCollectionState[],
): CollectionsPlan {
  const readable = remote.filter((collection) => collection.kind !== 'link');
  const remoteIds = new Set(readable.map((collection) => collection.id));
  const localById = new Map(local.map((collection) => [collection.id, collection]));
  const refetch: string[] = [];
  const pull: string[] = [];
  const resync: string[] = [];
  for (const collection of readable) {
    const known = localById.get(collection.id);
    const cursorSeq = known?.cursorSeq ?? 0;
    if (known === undefined || known.metaUpdatedAt !== collection.metaUpdatedAt) {
      refetch.push(collection.id);
    }
    if (known !== undefined && cursorSeq > 0 && cursorSeq < collection.tombstonesPrunedThroughSeq) {
      resync.push(collection.id);
    } else if (collection.lastSeq > cursorSeq) {
      pull.push(collection.id);
    }
  }
  const drop = local.filter((collection) => !remoteIds.has(collection.id)).map((collection) => collection.id);
  return { refetch, pull, resync, drop };
}
