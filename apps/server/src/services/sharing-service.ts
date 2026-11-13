import type { FastifyBaseLogger } from 'fastify';
import type { Kysely } from 'kysely';
import {
  createCollectionsRepository,
  type ReadableCollection,
} from '../adapters/db/collections-repository.ts';
import { entriesRepository } from '../adapters/db/entries-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { sharesRepository, type ShareListItem } from '../adapters/db/shares-repository.ts';
import { createUsersRepository } from '../adapters/db/users-repository.ts';
import { SEALED_SHARE_BYTES, WRAPPED_KEY_BYTES } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { AppError } from '../domain/errors.ts';
import type { Clock } from '../platform/clock.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { SHARING_ISSUE_PATHS, SHARING_TEXT } from '../text/sharing.ts';
import { allocateSeqs } from './sequence-service.ts';

export type CreateCollectionCommand = {
  readonly id: string;
  readonly kind: 'shared' | 'link';
  readonly keyByOwner: Buffer;
  readonly metadataBlob: Buffer;
};

export type Created<T> = { readonly value: T; readonly created: boolean };

export type PublicKey = { readonly userId: string; readonly username: string; readonly publicKey: Buffer };

export type SharingServiceDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly clock: Clock;
  readonly logger: FastifyBaseLogger;
  readonly deleteLinkCollection?: (userId: string, collectionId: string) => Promise<void>;
};

function invalid(path: readonly string[], message: string, extra: Record<string, unknown> = {}): AppError {
  return new AppError('VALIDATION_FAILED', { issues: [{ path, message }], ...extra });
}

export function createSharingService(deps: SharingServiceDeps) {
  const { db, settings, clock, logger } = deps;
  const collections = createCollectionsRepository(db);
  const users = createUsersRepository(db);

  function requireSharing(): void {
    if (!settings.flags.sharing) throw new AppError('FEATURE_DISABLED');
  }

  function requireFlagFor(kind: string): void {
    if (kind === 'link') {
      if (!settings.flags.publicLinks) throw new AppError('FEATURE_DISABLED');
      return;
    }
    requireSharing();
  }

  async function readable(userId: string, id: string): Promise<ReadableCollection> {
    const found = await collections.findReadable(db, userId, id);
    if (found === undefined) throw new AppError('NOT_FOUND');
    return found;
  }

  async function ownedShared(userId: string, id: string): Promise<ReadableCollection> {
    const found = await collections.findReadable(db, userId, id);
    if (found === undefined || found.role !== 'owner' || found.kind !== 'shared') throw new AppError('NOT_FOUND');
    return found;
  }

  async function createCollection(userId: string, command: CreateCollectionCommand): Promise<Created<ReadableCollection>> {
    requireFlagFor(command.kind);
    const existing = await collections.findById(db, command.id);
    if (existing !== undefined) {
      const identical =
        existing.ownerId === userId &&
        existing.kind === command.kind &&
        existing.keyByOwner.equals(command.keyByOwner) &&
        existing.metadataBlob.equals(command.metadataBlob);
      if (!identical) throw new AppError('ID_CONFLICT');
      return { value: await readable(userId, command.id), created: false };
    }
    if (command.keyByOwner.length !== WRAPPED_KEY_BYTES) {
      throw invalid(SHARING_ISSUE_PATHS.keyByOwner, SHARING_TEXT.keyLength(WRAPPED_KEY_BYTES));
    }
    if (command.metadataBlob.length > settings.limits.maxCollectionMetadataBytes) throw new AppError('PAYLOAD_TOO_LARGE');
    if (command.kind === 'shared') {
      const max = settings.sharing.maxSharedCollectionsPerUser;
      if ((await collections.countOwnedShared(db, userId)) >= max) {
        throw invalid(SHARING_ISSUE_PATHS.id, SHARING_TEXT.sharedLimit(max), { limit: max });
      }
    }
    const result = await collections.insertOwned(db, {
      id: command.id,
      ownerId: userId,
      kind: command.kind,
      keyByOwner: command.keyByOwner,
      metadataBlob: command.metadataBlob,
      now: clock.now(),
    });
    if (result === 'idConflict') return createCollection(userId, command);
    return { value: await readable(userId, command.id), created: true };
  }

  async function deleteCollection(userId: string, id: string): Promise<void> {
    const found = await collections.findReadable(db, userId, id);
    if (found === undefined || found.role !== 'owner') {
      requireSharing();
      throw new AppError('NOT_FOUND');
    }
    requireFlagFor(found.kind === 'link' ? 'link' : 'shared');
    if (found.kind === 'link' && deps.deleteLinkCollection !== undefined) {
      await deps.deleteLinkCollection(userId, id);
      return;
    }
    if (found.kind !== 'shared') throw new AppError('FORBIDDEN');
    await collections.deleteCollection(db, id);
  }

  async function addEntry(
    userId: string,
    collectionId: string,
    entryId: string,
    wrap: Buffer,
  ): Promise<{ readonly created: boolean }> {
    requireSharing();
    await ownedShared(userId, collectionId);
    if (wrap.length !== WRAPPED_KEY_BYTES) {
      throw invalid(SHARING_ISSUE_PATHS.entryKeyByCollection, SHARING_TEXT.keyLength(WRAPPED_KEY_BYTES));
    }
    return db.transaction().execute(async (trx) => {
      const entry = await entriesRepository.findEntry(trx, entryId, { forUpdate: true });
      if (entry === undefined || entry.ownerId !== userId || entry.purpose !== 'journal' || entry.trashedAt !== null) {
        throw new AppError('NOT_FOUND');
      }
      const existing = await trx
        .selectFrom('entryMemberships')
        .select('entryKeyByCollection')
        .where('collectionId', '=', collectionId)
        .where('entryId', '=', entryId)
        .executeTakeFirst();
      if (existing !== undefined) {
        if (!existing.entryKeyByCollection.equals(wrap)) throw new AppError('ID_CONFLICT');
        return { created: false };
      }
      const now = clock.now();
      const seqs = await allocateSeqs(trx, [collectionId], now);
      await entriesRepository.insertMemberships(trx, [
        { collectionId, entryId, entryKeyByCollection: wrap, seq: seqs.get(collectionId) ?? 0, addedAt: now },
      ]);
      return { created: true };
    });
  }

  async function removeEntry(userId: string, collectionId: string, entryId: string): Promise<void> {
    requireSharing();
    await ownedShared(userId, collectionId);
    await db.transaction().execute(async (trx) => {
      const existing = await trx
        .selectFrom('entryMemberships')
        .select('entryId')
        .where('collectionId', '=', collectionId)
        .where('entryId', '=', entryId)
        .forUpdate()
        .executeTakeFirst();
      if (existing === undefined) return;
      const now = clock.now();
      const seqs = await allocateSeqs(trx, [collectionId], now);
      await entriesRepository.deleteMembership(trx, collectionId, entryId);
      await entriesRepository.insertTombstones(trx, [
        { collectionId, entryId, seq: seqs.get(collectionId) ?? 0, removedAt: now },
      ]);
    });
  }

  async function share(
    userId: string,
    collectionId: string,
    recipientUserId: string,
    sealedKey: Buffer,
  ): Promise<{ readonly created: boolean }> {
    requireSharing();
    await ownedShared(userId, collectionId);
    const existing = await sharesRepository.find(db, collectionId, recipientUserId);
    if (existing !== undefined) {
      if (!existing.sealedCollectionKey.equals(sealedKey)) throw new AppError('ALREADY_SHARED');
      return { created: false };
    }
    if (recipientUserId === userId) throw invalid(SHARING_ISSUE_PATHS.recipientUserId, SHARING_TEXT.notYourself);
    const recipient = await users.findById(recipientUserId);
    if (recipient === undefined || recipient.status !== 'active') {
      throw invalid(SHARING_ISSUE_PATHS.recipientUserId, SHARING_TEXT.recipientInactive);
    }
    if (sealedKey.length !== SEALED_SHARE_BYTES) {
      throw invalid(SHARING_ISSUE_PATHS.sealedCollectionKey, SHARING_TEXT.keyLength(SEALED_SHARE_BYTES));
    }
    const max = settings.sharing.maxRecipientsPerCollection;
    if ((await sharesRepository.count(db, collectionId)) >= max) {
      throw invalid(SHARING_ISSUE_PATHS.recipientUserId, SHARING_TEXT.recipientLimit(max), { limit: max });
    }
    const result = await sharesRepository.insert(db, {
      collectionId,
      recipientId: recipientUserId,
      sealedCollectionKey: sealedKey,
      createdAt: clock.now(),
    });
    if (result === 'alreadyShared') return share(userId, collectionId, recipientUserId, sealedKey);
    logger.info({ event: LOG_EVENTS.shareCreated, userId, collectionId, recipientUserId });
    return { created: true };
  }

  async function listShares(userId: string, collectionId: string): Promise<ShareListItem[]> {
    requireSharing();
    await ownedShared(userId, collectionId);
    return sharesRepository.list(db, collectionId);
  }

  async function unshare(actorId: string, collectionId: string, recipientUserId: string): Promise<void> {
    requireSharing();
    const found = await collections.findReadable(db, actorId, collectionId);
    const allowed =
      found !== undefined &&
      found.kind === 'shared' &&
      (found.role === 'owner' || actorId === recipientUserId);
    if (!allowed) throw new AppError('NOT_FOUND');
    await sharesRepository.delete(db, collectionId, recipientUserId);
  }

  async function publicKey(username: string): Promise<PublicKey> {
    requireSharing();
    const user = await users.findByUsername(username);
    if (user === undefined || user.status !== 'active') throw new AppError('NOT_FOUND');
    return { userId: user.id, username: user.username, publicKey: user.publicKey };
  }

  return { createCollection, deleteCollection, addEntry, removeEntry, share, listShares, unshare, publicKey };
}

export type SharingService = ReturnType<typeof createSharingService>;
