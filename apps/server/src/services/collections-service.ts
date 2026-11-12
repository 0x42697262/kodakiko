import type { Kysely } from 'kysely';
import {
  createCollectionsRepository,
  type ReadableCollection,
} from '../adapters/db/collections-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { COLLECTIONS_PAGE_DEFAULT, COLLECTIONS_PAGE_MAX } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { clampLimit, decodeCursor, encodeCursor } from '../domain/collections.ts';
import { AppError } from '../domain/errors.ts';
import type { Clock } from '../platform/clock.ts';
import { ENTRY_ISSUE_PATHS, ENTRY_TEXT } from '../text/entries.ts';

export type CollectionPage = {
  readonly items: readonly ReadableCollection[];
  readonly nextCursor: string | null;
};

export type CollectionsServiceDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly clock: Clock;
};

function invalidCursor(): AppError {
  return new AppError('VALIDATION_FAILED', {
    issues: [{ path: ENTRY_ISSUE_PATHS.cursor, message: ENTRY_TEXT.invalidCursor }],
  });
}

export function createCollectionsService(deps: CollectionsServiceDeps) {
  const { db, settings, clock } = deps;
  const collections = createCollectionsRepository(db);

  async function list(
    userId: string,
    cursor: string | undefined,
    limit: number | undefined,
  ): Promise<CollectionPage> {
    const afterId = cursor === undefined ? null : decodeCursor(cursor);
    if (cursor !== undefined && afterId === null) throw invalidCursor();
    const pageLimit = clampLimit(limit, COLLECTIONS_PAGE_DEFAULT, COLLECTIONS_PAGE_MAX);
    const rows = await collections.listReadable(db, userId, afterId, pageLimit + 1);
    const items = rows.slice(0, pageLimit);
    const last = items[items.length - 1];
    const nextCursor = rows.length > pageLimit && last !== undefined ? encodeCursor(last.id) : null;
    return { items, nextCursor };
  }

  async function get(userId: string, id: string): Promise<ReadableCollection> {
    const collection = await collections.findReadable(db, userId, id);
    if (collection === undefined) throw new AppError('NOT_FOUND');
    return collection;
  }

  async function rename(
    userId: string,
    id: string,
    metadataBlob: Buffer,
  ): Promise<ReadableCollection> {
    if (metadataBlob.length > settings.limits.maxCollectionMetadataBytes) {
      throw new AppError('PAYLOAD_TOO_LARGE');
    }
    const collection = await collections.findReadable(db, userId, id);
    if (collection === undefined || collection.role !== 'owner') throw new AppError('NOT_FOUND');
    await collections.updateMetadata(db, id, metadataBlob, clock.now());
    return get(userId, id);
  }

  return { list, get, rename };
}

export type CollectionsService = ReturnType<typeof createCollectionsService>;
