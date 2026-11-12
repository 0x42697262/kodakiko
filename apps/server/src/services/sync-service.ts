import type { Kysely } from 'kysely';
import { createCollectionsRepository } from '../adapters/db/collections-repository.ts';
import {
  diffRepository,
  type DiffEntryRow,
  type DiffMediaRow,
  type DiffMembershipRow,
  type DiffObjectRow,
  type DiffTombstoneRow,
} from '../adapters/db/diff-repository.ts';
import type { ReadableCollection } from '../adapters/db/collections-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { DIFF_PAGE_DEFAULT } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { clampLimit } from '../domain/collections.ts';
import { AppError } from '../domain/errors.ts';
import { mergeDiffRows, needsResync, nextCursor } from '../domain/sync.ts';

export type DiffMembership = {
  readonly entryId: string;
  readonly entryKeyByCollection: Buffer | null;
  readonly seq: number;
  readonly removed: boolean;
};

export type DiffView = {
  readonly collectionId: string;
  readonly memberships: readonly DiffMembership[];
  readonly entries: readonly DiffEntryRow[];
  readonly media: readonly DiffMediaRow[];
  readonly objects: readonly DiffObjectRow[];
  readonly nextCursor: number;
  readonly hasMore: boolean;
  readonly resyncRequired: boolean;
};

export type SyncStateView = readonly ReadableCollection[];

type FeedRow = DiffMembership;

export type SyncServiceDeps = { readonly db: Kysely<Database>; readonly settings: Settings };

function resyncView(collectionId: string): DiffView {
  return {
    collectionId,
    memberships: [],
    entries: [],
    media: [],
    objects: [],
    nextCursor: 0,
    hasMore: false,
    resyncRequired: true,
  };
}

function inOrder<Row>(
  rows: readonly Row[],
  keyOf: (row: Row) => string,
  order: readonly string[],
): Row[] {
  const byKey = new Map<string, Row[]>();
  for (const row of rows) {
    const group = byKey.get(keyOf(row)) ?? [];
    group.push(row);
    byKey.set(keyOf(row), group);
  }
  return order.flatMap((key) => byKey.get(key) ?? []);
}

export function createSyncService(deps: SyncServiceDeps) {
  const { db, settings } = deps;
  const { maxDiffPageSize } = settings.sync;
  const collections = createCollectionsRepository(db);

  function membershipFeedRow(row: DiffMembershipRow, collection: ReadableCollection): FeedRow {
    const hidden = collection.role === 'viewer' && row.trashedAt !== null;
    return {
      entryId: row.entryId,
      entryKeyByCollection: hidden ? null : row.entryKeyByCollection,
      seq: row.seq,
      removed: hidden,
    };
  }

  function tombstoneFeedRow(row: DiffTombstoneRow): FeedRow {
    return { entryId: row.entryId, entryKeyByCollection: null, seq: row.seq, removed: true };
  }

  async function diff(
    userId: string,
    collectionId: string,
    since: number,
    limit: number | undefined,
  ): Promise<DiffView> {
    const pageLimit = clampLimit(limit, Math.min(DIFF_PAGE_DEFAULT, maxDiffPageSize), maxDiffPageSize);
    return db
      .transaction()
      .setIsolationLevel('repeatable read')
      .setAccessMode('read only')
      .execute(async (trx) => {
        const collection = await collections.findReadable(trx, userId, collectionId);
        if (collection === undefined) throw new AppError('NOT_FOUND');
        if (needsResync(since, collection.tombstonesPrunedThroughSeq)) {
          return resyncView(collectionId);
        }
        const memberships = await diffRepository.membershipsSince(trx, collectionId, since, pageLimit);
        const tombstones = await diffRepository.tombstonesSince(trx, collectionId, since, pageLimit);
        const page = mergeDiffRows(
          memberships.map((row) => membershipFeedRow(row, collection)),
          tombstones.map(tombstoneFeedRow),
          pageLimit,
        );
        const liveIds = page.rows.filter((row) => !row.removed).map((row) => row.entryId);
        const entryRows = await diffRepository.entriesByIds(trx, liveIds);
        const entries = inOrder(entryRows, (row) => row.id, liveIds);
        const mediaRows = await diffRepository.mediaByEntryIds(trx, liveIds);
        const media = inOrder(mediaRows, (row) => row.entryId, liveIds);
        const objectRows = await diffRepository.uploadedObjectsByMediaIds(
          trx,
          media.map((row) => row.id),
        );
        const objects = inOrder(
          objectRows,
          (row) => row.mediaId ?? '',
          media.map((row) => row.id),
        );
        return {
          collectionId,
          memberships: page.rows,
          entries,
          media,
          objects,
          nextCursor: nextCursor(page, since, collection.lastSeq),
          hasMore: page.hasMore,
          resyncRequired: false,
        };
      });
  }

  async function state(userId: string): Promise<SyncStateView> {
    return collections.syncState(db, userId);
  }

  return { diff, state };
}

export type SyncService = ReturnType<typeof createSyncService>;
