import { encodeBytes } from '@kodakiko/contract';
import type { Kysely, Transaction } from 'kysely';
import { createCollectionsRepository } from '../adapters/db/collections-repository.ts';
import {
  entriesRepository,
  type EntryRow,
  type MediaRow,
} from '../adapters/db/entries-repository.ts';
import { objectsRepository as objectsModule } from '../adapters/db/objects-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import type { Settings, StorageSettings } from '../config/settings.ts';
import type { EntryPurpose } from '../domain/enums.ts';
import {
  canWriteEntry,
  isIdenticalCreate,
  validateCreateMemberships,
  type MembershipTarget,
} from '../domain/entries.ts';
import { AppError } from '../domain/errors.ts';
import { objectDeleteAfter } from '../domain/objects.ts';
import type { Clock } from '../platform/clock.ts';
import type { EventLogger } from '../platform/event-logger.ts';
import { ENTRY_ISSUE_PATHS, ENTRY_TEXT } from '../text/entries.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { allocateSeqs, type SequenceService } from './sequence-service.ts';

export type CreateEntryCommand = {
  readonly id: string;
  readonly purpose: EntryPurpose;
  readonly metadataBlob: Buffer;
  readonly memberships: readonly {
    readonly collectionId: string;
    readonly entryKeyByCollection: Buffer;
  }[];
  readonly media: readonly { readonly mediaId: string; readonly mediaKeyByEntry: Buffer }[];
};

export type PatchEntryCommand = { readonly metadataBlob: Buffer; readonly baseRevision: number };
export type AddMediaCommand = { readonly mediaId: string; readonly mediaKeyByEntry: Buffer };
export type CreatedEntry = { readonly entry: EntryRow; readonly created: boolean };
export type AddedMedia = { readonly media: MediaRow; readonly created: boolean };
export type PurgeGuard = { readonly onlyIfTrashedBefore: Date };

export type EntriesServiceDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly clock: Clock;
  readonly objectsRepository: typeof objectsModule;
  readonly sequenceService: SequenceService;
  readonly logger: EventLogger;
};

type DeleteSettings = Pick<StorageSettings, 'objectGraceDays' | 'uploadUrlTtlSeconds'>;

export async function removeEntryInTransaction(
  trx: Transaction<Database>,
  entryId: string,
  now: Date,
  storage: DeleteSettings,
): Promise<boolean> {
  const entry = await entriesRepository.findEntry(trx, entryId, { forUpdate: true });
  if (entry === undefined) return false;
  const mediaIds = await entriesRepository.mediaIdsOfEntry(trx, entryId);
  await objectsModule.markDeletingByMediaIds(trx, mediaIds, objectDeleteAfter(now, storage));
  const collectionIds = await entriesRepository.membershipCollectionIds(trx, entryId);
  const seqByCollection = await allocateSeqs(trx, collectionIds, now);
  await entriesRepository.insertTombstones(
    trx,
    [...seqByCollection].map(([collectionId, seq]) => ({
      collectionId,
      entryId,
      seq,
      removedAt: now,
    })),
  );
  await entriesRepository.deleteEntry(trx, entryId);
  return true;
}

export function createEntriesService(deps: EntriesServiceDeps) {
  const { db, settings, clock, objectsRepository, sequenceService, logger } = deps;
  const { limits, flags, storage } = settings;
  const collections = createCollectionsRepository(db);

  function assertMetadataSize(blob: Buffer): void {
    if (blob.length > limits.maxEntryMetadataBytes) throw new AppError('PAYLOAD_TOO_LARGE');
  }

  function mediaLimitError(): AppError {
    return new AppError('VALIDATION_FAILED', {
      issues: [
        { path: ENTRY_ISSUE_PATHS.media, message: ENTRY_TEXT.mediaLimit(limits.maxMediaPerEntry) },
      ],
    });
  }

  function assertDistinctMedia(command: CreateEntryCommand): void {
    const seen = new Set<string>();
    command.media.forEach((item, index) => {
      if (seen.has(item.mediaId)) {
        throw new AppError('VALIDATION_FAILED', {
          issues: [
            { path: ENTRY_ISSUE_PATHS.duplicateMedia(index), message: ENTRY_TEXT.duplicateMedia },
          ],
        });
      }
      seen.add(item.mediaId);
    });
  }

  async function loadOwnedEntry(
    trx: Transaction<Database>,
    userId: string,
    id: string,
  ): Promise<EntryRow> {
    const entry = await entriesRepository.findEntry(trx, id, { forUpdate: true });
    if (entry === undefined || !canWriteEntry(userId, entry.ownerId)) {
      throw new AppError('NOT_FOUND');
    }
    return entry;
  }

  async function loadTargets(
    command: CreateEntryCommand,
  ): Promise<readonly MembershipTarget[]> {
    const rows = await Promise.all(
      command.memberships.map((membership) =>
        collections.findById(db, membership.collectionId),
      ),
    );
    return rows.map((row) => {
      if (row === undefined) throw new AppError('NOT_FOUND');
      return { collectionId: row.id, kind: row.kind, ownerId: row.ownerId };
    });
  }

  async function createInTransaction(
    userId: string,
    command: CreateEntryCommand,
    now: Date,
  ): Promise<CreatedEntry> {
    return db.transaction().execute(async (trx) => {
      const row: EntryRow = {
        id: command.id,
        ownerId: userId,
        purpose: command.purpose,
        revision: 1,
        metadataBlob: command.metadataBlob,
        trashedAt: null,
        createdAt: now,
        updatedAt: now,
      };
      const inserted = await entriesRepository.insertEntry(trx, row);
      if (inserted === 'idConflict') {
        const existing = await entriesRepository.findEntrySnapshot(trx, command.id);
        if (existing === undefined) throw new AppError('ID_CONFLICT');
        const identical = isIdenticalCreate(
          {
            ownerId: existing.entry.ownerId,
            purpose: existing.entry.purpose,
            metadataBlob: existing.entry.metadataBlob,
            collectionIds: existing.collectionIds,
            mediaIds: existing.mediaIds,
          },
          {
            ownerId: userId,
            purpose: command.purpose,
            metadataBlob: command.metadataBlob,
            collectionIds: command.memberships.map((membership) => membership.collectionId),
            mediaIds: command.media.map((item) => item.mediaId),
          },
        );
        if (!identical) throw new AppError('ID_CONFLICT');
        return { entry: existing.entry, created: false };
      }
      const seqByCollection = await allocateSeqs(
        trx,
        command.memberships.map((membership) => membership.collectionId),
        now,
      );
      await entriesRepository.insertMemberships(
        trx,
        command.memberships.map((membership) => ({
          collectionId: membership.collectionId,
          entryId: command.id,
          entryKeyByCollection: membership.entryKeyByCollection,
          seq: seqByCollection.get(membership.collectionId) as number,
          addedAt: now,
        })),
      );
      for (const item of command.media) {
        const outcome = await entriesRepository.insertMedia(trx, {
          id: item.mediaId,
          entryId: command.id,
          mediaKeyByEntry: item.mediaKeyByEntry,
          createdAt: now,
        });
        if (outcome === 'idConflict') throw new AppError('ID_CONFLICT');
      }
      return { entry: row, created: true };
    });
  }

  async function create(userId: string, command: CreateEntryCommand): Promise<CreatedEntry> {
    const now = clock.now();
    assertMetadataSize(command.metadataBlob);
    if (command.media.length > limits.maxMediaPerEntry) throw mediaLimitError();
    assertDistinctMedia(command);
    const targets = await loadTargets(command);
    const decision = validateCreateMemberships({
      userId,
      purpose: command.purpose,
      targets,
      flags,
    });
    if (!decision.ok) throw new AppError(decision.code);
    const result = await createInTransaction(userId, command, now);
    if (result.created) {
      logger.info({
        event: LOG_EVENTS.entryCreated,
        userId,
        entryId: command.id,
        purpose: command.purpose,
        mediaCount: command.media.length,
        collectionCount: command.memberships.length,
      });
    }
    return result;
  }

  async function get(userId: string, id: string): Promise<EntryRow> {
    const entry = await entriesRepository.findReadable(db, userId, id);
    if (entry === undefined) throw new AppError('NOT_FOUND');
    if (entry.trashedAt !== null && entry.ownerId !== userId) throw new AppError('NOT_FOUND');
    return entry;
  }

  async function patch(userId: string, id: string, command: PatchEntryCommand): Promise<EntryRow> {
    assertMetadataSize(command.metadataBlob);
    const now = clock.now();
    return db.transaction().execute(async (trx) => {
      const entry = await loadOwnedEntry(trx, userId, id);
      if (command.baseRevision !== entry.revision) {
        throw new AppError('REVISION_CONFLICT', {
          revision: entry.revision,
          metadataBlob: encodeBytes(entry.metadataBlob),
        });
      }
      const revision = entry.revision + 1;
      await entriesRepository.updateMetadata(trx, id, command.metadataBlob, revision, now);
      await sequenceService.bumpEntry(trx, id, now);
      return { ...entry, metadataBlob: command.metadataBlob, revision, updatedAt: now };
    });
  }

  async function setTrashed(userId: string, id: string, trashed: boolean): Promise<EntryRow> {
    const now = clock.now();
    return db.transaction().execute(async (trx) => {
      const entry = await loadOwnedEntry(trx, userId, id);
      if ((entry.trashedAt !== null) === trashed) return entry;
      const trashedAt = trashed ? now : null;
      await entriesRepository.setTrashedAt(trx, id, trashedAt, now);
      await sequenceService.bumpEntry(trx, id, now);
      return { ...entry, trashedAt, updatedAt: now };
    });
  }

  const trash = (userId: string, id: string): Promise<EntryRow> => setTrashed(userId, id, true);
  const restore = (userId: string, id: string): Promise<EntryRow> => setTrashed(userId, id, false);

  async function remove(userId: string, id: string): Promise<void> {
    const now = clock.now();
    const removed = await db.transaction().execute(async (trx) => {
      const entry = await entriesRepository.findEntry(trx, id, { forUpdate: true });
      if (entry === undefined) return false;
      if (!canWriteEntry(userId, entry.ownerId)) throw new AppError('NOT_FOUND');
      return removeEntryInTransaction(trx, id, now, storage);
    });
    if (removed) logger.info({ event: LOG_EVENTS.entryDeleted, userId, entryId: id });
  }

  async function removeAsSystem(id: string, guard: PurgeGuard): Promise<boolean> {
    const now = clock.now();
    return db.transaction().execute(async (trx) => {
      const entry = await entriesRepository.findEntry(trx, id, { forUpdate: true });
      if (entry === undefined || entry.trashedAt === null) return false;
      if (entry.trashedAt > guard.onlyIfTrashedBefore) return false;
      return removeEntryInTransaction(trx, id, now, storage);
    });
  }

  async function addMedia(
    userId: string,
    entryId: string,
    command: AddMediaCommand,
  ): Promise<AddedMedia> {
    const now = clock.now();
    return db.transaction().execute(async (trx) => {
      await loadOwnedEntry(trx, userId, entryId);
      const existing = await entriesRepository.findMedia(trx, command.mediaId);
      if (existing !== undefined) {
        const same =
          existing.entryId === entryId && existing.mediaKeyByEntry.equals(command.mediaKeyByEntry);
        if (!same) throw new AppError('ID_CONFLICT');
        return { media: existing, created: false };
      }
      if ((await entriesRepository.countMedia(trx, entryId)) >= limits.maxMediaPerEntry) {
        throw mediaLimitError();
      }
      const media: MediaRow = {
        id: command.mediaId,
        entryId,
        mediaKeyByEntry: command.mediaKeyByEntry,
        createdAt: now,
      };
      if ((await entriesRepository.insertMedia(trx, media)) === 'idConflict') {
        throw new AppError('ID_CONFLICT');
      }
      await sequenceService.bumpEntry(trx, entryId, now);
      return { media, created: true };
    });
  }

  async function removeMedia(userId: string, mediaId: string): Promise<void> {
    const now = clock.now();
    await db.transaction().execute(async (trx) => {
      const found = await entriesRepository.findMedia(trx, mediaId);
      if (found === undefined) throw new AppError('NOT_FOUND');
      await loadOwnedEntry(trx, userId, found.entryId);
      const media = await entriesRepository.findMedia(trx, mediaId, { forUpdate: true });
      if (media === undefined) throw new AppError('NOT_FOUND');
      await objectsRepository.markDeletingByMediaIds(trx, [mediaId], objectDeleteAfter(now, storage));
      await entriesRepository.deleteMedia(trx, mediaId);
      await sequenceService.bumpEntry(trx, media.entryId, now);
    });
  }

  async function onObjectUploaded(trx: Transaction<Database>, entryId: string): Promise<void> {
    await sequenceService.bumpEntry(trx, entryId, clock.now());
  }

  return {
    create,
    get,
    patch,
    trash,
    restore,
    remove,
    removeAsSystem,
    addMedia,
    removeMedia,
    onObjectUploaded,
  };
}

export type EntriesService = ReturnType<typeof createEntriesService>;
