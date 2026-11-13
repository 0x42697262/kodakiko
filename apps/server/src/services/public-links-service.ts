import type { FastifyBaseLogger } from 'fastify';
import type { Kysely, Transaction } from 'kysely';
import { decodeBytes } from '@kodakiko/contract';
import { createCollectionsRepository } from '../adapters/db/collections-repository.ts';
import type { EntryRow, MediaRow } from '../adapters/db/entries-repository.ts';
import type { ObjectRow } from '../adapters/db/objects-repository.ts';
import { publicLinksRepository, type PublicLinkRow } from '../adapters/db/public-links-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import type { ObjectStore, ObjectStream, ByteRange } from '../adapters/storage/object-store.ts';
import {
  LINK_ACCESS_TOKEN_BYTES,
  LINK_ENTRIES_PAGE_DEFAULT,
  LINK_ENTRIES_PAGE_MAX,
  LINK_EXPIRY_DURATIONS_MS,
  LINK_ONE_VIEW,
  SECOND_MS,
} from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { clampLimit, decodeCursor, encodeCursor } from '../domain/collections.ts';
import { AppError } from '../domain/errors.ts';
import { createViewToken, verifyViewToken } from '../domain/link-tokens.ts';
import type { Clock } from '../platform/clock.ts';
import { constantTimeEqual, sha256 } from '../platform/hash.ts';
import { randomId } from '../platform/ids.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { removeEntryInTransaction } from './entries-service.ts';

export type LinkDownloadView =
  | { readonly kind: 'presigned'; readonly url: string; readonly expiresAt: Date }
  | { readonly kind: 'proxy'; readonly path: string };

export type LinkEntryView = {
  readonly entry: EntryRow;
  readonly entryKeyByCollection: Buffer;
  readonly media: readonly MediaRow[];
  readonly objects: readonly { readonly row: ObjectRow; readonly download: LinkDownloadView }[];
};

export type LinkOpenView = {
  readonly collectionId: string;
  readonly metadataBlob: Buffer;
  readonly expiresAt: Date | null;
  readonly maxViews: number | null;
  readonly viewToken: string;
};

export type PublicLinksServiceDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly clock: Clock;
  readonly store: ObjectStore;
  readonly logger: FastifyBaseLogger;
  readonly proxyPath: (linkId: string, objectId: string) => string;
};

function tokenBytes(encoded: string | undefined): Uint8Array | null {
  if (encoded === undefined) return null;
  try {
    const bytes = decodeBytes(encoded as never);
    return bytes.length === LINK_ACCESS_TOKEN_BYTES ? bytes : null;
  } catch {
    return null;
  }
}

export function createPublicLinksService(deps: PublicLinksServiceDeps) {
  const { db, settings, clock, store, logger } = deps;
  const collections = createCollectionsRepository(db);
  const secret = settings.auth.fakeParamsSecret;

  function requireFlag(): void {
    if (!settings.flags.publicLinks) throw new AppError('FEATURE_DISABLED');
  }

  async function deleteLinkCollection(trx: Transaction<Database>, collectionId: string, now: Date): Promise<void> {
    const entryIds = await trx
      .selectFrom('entryMemberships')
      .innerJoin('entries', 'entries.id', 'entryMemberships.entryId')
      .select('entries.id')
      .where('entryMemberships.collectionId', '=', collectionId)
      .where('entries.purpose', '=', 'link_copy')
      .execute();
    for (const { id } of entryIds) await removeEntryInTransaction(trx, id, now, settings.storage);
    await trx.deleteFrom('collections').where('id', '=', collectionId).execute();
  }

  async function create(
    userId: string,
    input: { readonly collectionId: string; readonly accessToken: Uint8Array; readonly expiry: string },
  ): Promise<PublicLinkRow> {
    requireFlag();
    const collection = await collections.findReadable(db, userId, input.collectionId);
    if (collection === undefined || collection.role !== 'owner' || collection.kind !== 'link') throw new AppError('NOT_FOUND');
    if (input.accessToken.length !== LINK_ACCESS_TOKEN_BYTES) throw new AppError('VALIDATION_FAILED');
    if ((await publicLinksRepository.findByCollection(db, input.collectionId)) !== undefined) throw new AppError('ID_CONFLICT');
    const entryIds = (
      await db.selectFrom('entryMemberships').select('entryId').where('collectionId', '=', input.collectionId).execute()
    ).map((row) => row.entryId);
    const maxEntries = settings.links.maxEntriesPerLink;
    if (entryIds.length === 0 || entryIds.length > maxEntries) {
      throw new AppError('VALIDATION_FAILED', { limit: maxEntries });
    }
    const maxLinks = settings.links.maxPerUser;
    if ((await publicLinksRepository.countByOwner(db, userId)) >= maxLinks) {
      throw new AppError('VALIDATION_FAILED', { limit: maxLinks });
    }
    const objects = await db
      .selectFrom('media')
      .leftJoin('objects', 'objects.mediaId', 'media.id')
      .select(['media.id as mediaId', 'objects.variant', 'objects.state'])
      .where('media.entryId', 'in', entryIds)
      .execute();
    const uploadedVariants = new Map<string, Set<string>>();
    for (const row of objects) {
      const set = uploadedVariants.get(row.mediaId) ?? new Set<string>();
      if (row.state === 'uploaded' && row.variant !== null) set.add(row.variant);
      uploadedVariants.set(row.mediaId, set);
    }
    if ([...uploadedVariants.values()].some((variants) => !variants.has('original') || !variants.has('thumbnail'))) {
      throw new AppError('UPLOAD_INCOMPLETE');
    }
    const now = clock.now();
    const duration = LINK_EXPIRY_DURATIONS_MS[input.expiry];
    const row = await publicLinksRepository.insert(db, {
      id: randomId(),
      collectionId: input.collectionId,
      accessTokenHash: sha256(input.accessToken),
      expiresAt: duration === undefined ? null : new Date(now.getTime() + duration),
      maxViews: input.expiry === LINK_ONE_VIEW ? 1 : null,
      createdAt: now,
    });
    logger.info({ event: LOG_EVENTS.linkCreated, userId, linkId: row.id, entryCount: entryIds.length, expiry: input.expiry });
    return row;
  }

  async function list(userId: string): Promise<PublicLinkRow[]> {
    requireFlag();
    return publicLinksRepository.listByOwner(db, userId);
  }

  async function revoke(userId: string, linkId: string): Promise<void> {
    requireFlag();
    const link = await publicLinksRepository.findById(db, linkId);
    const collection = link === undefined ? undefined : await collections.findById(db, link.collectionId);
    if (link === undefined || collection === undefined || collection.ownerId !== userId) throw new AppError('NOT_FOUND');
    await db.transaction().execute((trx) => deleteLinkCollection(trx, link.collectionId, clock.now()));
    logger.info({ event: LOG_EVENTS.linkRevoked, userId, linkId });
  }

  async function deleteOwnedLinkCollection(userId: string, collectionId: string): Promise<void> {
    requireFlag();
    const collection = await collections.findById(db, collectionId);
    if (collection === undefined || collection.ownerId !== userId || collection.kind !== 'link') throw new AppError('NOT_FOUND');
    await db.transaction().execute((trx) => deleteLinkCollection(trx, collectionId, clock.now()));
  }

  async function matchingLink(linkId: string, encodedToken: string | undefined): Promise<PublicLinkRow> {
    const token = tokenBytes(encodedToken);
    const link = await publicLinksRepository.findById(db, linkId);
    const expected = link?.accessTokenHash ?? Buffer.alloc(LINK_ACCESS_TOKEN_BYTES);
    const matches = constantTimeEqual(sha256(token ?? new Uint8Array(LINK_ACCESS_TOKEN_BYTES)), expected);
    if (link === undefined || token === null || !matches) throw new AppError('LINK_TOKEN_INVALID');
    return link;
  }

  function timeExpired(link: PublicLinkRow, now: Date): boolean {
    return link.expiresAt !== null && link.expiresAt.getTime() <= now.getTime();
  }

  async function open(linkId: string, encodedToken: string | undefined): Promise<LinkOpenView> {
    requireFlag();
    const link = await matchingLink(linkId, encodedToken);
    const now = clock.now();
    if (timeExpired(link, now)) throw new AppError('LINK_EXPIRED');
    if (link.maxViews !== null && link.viewCount >= link.maxViews) throw new AppError('LINK_EXPIRED');
    if (!(await publicLinksRepository.countView(db, linkId))) throw new AppError('LINK_EXPIRED');
    const collection = await collections.findById(db, link.collectionId);
    if (collection === undefined) throw new AppError('LINK_TOKEN_INVALID');
    return {
      collectionId: link.collectionId,
      metadataBlob: collection.metadataBlob,
      expiresAt: link.expiresAt,
      maxViews: link.maxViews,
      viewToken: createViewToken(secret, linkId, now, settings.links.viewTokenMinutes),
    };
  }

  async function viewerLink(linkId: string, encodedToken: string | undefined, viewToken: string | undefined): Promise<PublicLinkRow> {
    requireFlag();
    const link = await matchingLink(linkId, encodedToken);
    const now = clock.now();
    if (!verifyViewToken(secret, linkId, viewToken, now)) throw new AppError('LINK_VIEW_EXPIRED');
    if (timeExpired(link, now)) throw new AppError('LINK_EXPIRED');
    return link;
  }

  async function downloadFor(linkId: string, row: ObjectRow): Promise<LinkDownloadView> {
    if (settings.links.proxyDownloads) return { kind: 'proxy', path: deps.proxyPath(linkId, row.id) };
    const ttl = settings.storage.downloadUrlTtlSeconds;
    const url = await store.presignGet(row.objectKey, ttl);
    return { kind: 'presigned', url, expiresAt: new Date(clock.now().getTime() + ttl * SECOND_MS) };
  }

  async function entries(
    linkId: string,
    encodedToken: string | undefined,
    viewToken: string | undefined,
    cursor: string | undefined,
    limit: number | undefined,
  ): Promise<{ readonly items: readonly LinkEntryView[]; readonly nextCursor: string | null }> {
    const link = await viewerLink(linkId, encodedToken, viewToken);
    const afterId = cursor === undefined ? null : decodeCursor(cursor);
    if (cursor !== undefined && afterId === null) throw new AppError('VALIDATION_FAILED');
    const pageLimit = clampLimit(limit, LINK_ENTRIES_PAGE_DEFAULT, LINK_ENTRIES_PAGE_MAX);
    let query = db
      .selectFrom('entryMemberships')
      .innerJoin('entries', 'entries.id', 'entryMemberships.entryId')
      .selectAll('entries')
      .select('entryMemberships.entryKeyByCollection')
      .where('entryMemberships.collectionId', '=', link.collectionId)
      .where('entries.purpose', '=', 'link_copy')
      .orderBy('entries.id')
      .limit(pageLimit + 1);
    if (afterId !== null) query = query.where('entries.id', '>', afterId);
    const rows = await query.execute();
    const page = rows.slice(0, pageLimit);
    const items: LinkEntryView[] = [];
    for (const { entryKeyByCollection, ...entry } of page) {
      const media = await db.selectFrom('media').selectAll().where('entryId', '=', entry.id).execute();
      const objectRows =
        media.length === 0
          ? []
          : await db
              .selectFrom('objects')
              .selectAll()
              .where('mediaId', 'in', media.map((row) => row.id))
              .where('state', '=', 'uploaded')
              .execute();
      const objects = [];
      for (const row of objectRows) objects.push({ row, download: await downloadFor(linkId, row) });
      items.push({ entry, entryKeyByCollection, media, objects });
    }
    const last = page[page.length - 1];
    return { items, nextCursor: rows.length > pageLimit && last !== undefined ? encodeCursor(last.id) : null };
  }

  async function content(
    linkId: string,
    encodedToken: string | undefined,
    viewToken: string | undefined,
    objectId: string,
    range: ByteRange | null,
  ): Promise<ObjectStream> {
    if (!settings.links.proxyDownloads) throw new AppError('NOT_FOUND');
    const link = await viewerLink(linkId, encodedToken, viewToken);
    const row = await db
      .selectFrom('objects')
      .innerJoin('media', 'media.id', 'objects.mediaId')
      .innerJoin('entryMemberships', 'entryMemberships.entryId', 'media.entryId')
      .selectAll('objects')
      .where('objects.id', '=', objectId)
      .where('objects.state', '=', 'uploaded')
      .where('entryMemberships.collectionId', '=', link.collectionId)
      .executeTakeFirst();
    if (row === undefined) throw new AppError('NOT_FOUND');
    return store.getObjectStream(row.objectKey, range);
  }

  return { create, list, revoke, deleteOwnedLinkCollection, deleteLinkCollection, open, entries, content };
}

export type PublicLinksService = ReturnType<typeof createPublicLinksService>;
