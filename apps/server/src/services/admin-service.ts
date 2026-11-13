import type { AuthLogger } from '../platform/silent-logger.ts';
import { sql, type Kysely } from 'kysely';
import type { Database } from '../adapters/db/schema.ts';
import { COLLATE_KEYWORD, COLLATION_C, DAY_MS } from '../config/constants.ts';
import { roleChangeRefusal } from '../domain/admin.ts';
import { clampLimit, decodeTextCursor, encodeCursor } from '../domain/collections.ts';
import type { UserRole, UserStatus } from '../domain/enums.ts';
import { AppError } from '../domain/errors.ts';
import type { Clock } from '../platform/clock.ts';
import { LOG_EVENTS } from '../text/events.ts';

export const ADMIN_PAGE_DEFAULT = 50;
export const ADMIN_PAGE_MAX = 200;
export const ADMIN_INVITES_LIST_MAX = 200;
export const ADMIN_ACTIVE_WINDOW_DAYS = 30;

export type AdminUserView = {
  readonly id: string;
  readonly username: string;
  readonly role: UserRole;
  readonly status: UserStatus;
  readonly quotaBytes: number | null;
  readonly usedBytes: number;
  readonly createdAt: Date;
  readonly deletionDueAt: Date | null;
  readonly sessionCount: number;
  readonly lastActiveAt: Date | null;
};

export type InviteView = {
  readonly id: string;
  readonly role: UserRole;
  readonly createdAt: Date;
  readonly expiresAt: Date;
  readonly redeemedAt: Date | null;
  readonly revokedAt: Date | null;
  readonly redeemedByUsername: string | null;
};

export type AdminStats = {
  readonly users: number;
  readonly activeUsers30d: number;
  readonly pendingDeletion: number;
  readonly collections: number;
  readonly entries: number;
  readonly objects: number;
  readonly totalBytes: number;
  readonly quotaBytesAllocated: number | null;
};

export type AdminActor = string | 'cli';

export type AdminServiceDeps = {
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly logger: AuthLogger;
  readonly createInvite: (role: UserRole, days: number, createdBy: string | null) => Promise<{ inviteUrl: string; expiresAt: Date }>;
};

export class AdminRefusalError extends AppError {
  constructor(reason: string) {
    super('VALIDATION_FAILED', { reason });
  }
}

export function createAdminService(deps: AdminServiceDeps) {
  const { db, clock, logger } = deps;

  function userQuery() {
    return db
      .selectFrom('users')
      .leftJoin('sessions', 'sessions.userId', 'users.id')
      .select([
        'users.id',
        'users.username',
        'users.role',
        'users.status',
        'users.quotaBytes',
        'users.usedBytes',
        'users.createdAt',
        'users.deletionDueAt',
        (builder) => builder.fn.count<string>('sessions.id').as('sessionCount'),
        (builder) => builder.fn.max('sessions.lastUsedAt').as('lastActiveAt'),
      ])
      .groupBy('users.id');
  }

  function toView(row: Awaited<ReturnType<ReturnType<typeof userQuery>['execute']>>[number]): AdminUserView {
    return {
      ...row,
      usedBytes: Number(row.usedBytes),
      quotaBytes: row.quotaBytes === null ? null : Number(row.quotaBytes),
      sessionCount: Number(row.sessionCount),
      lastActiveAt: row.lastActiveAt === null ? null : new Date(row.lastActiveAt),
    };
  }

  async function listUsers(cursor: string | undefined, limit: number | undefined) {
    const after = cursor === undefined ? null : decodeTextCursor(cursor);
    if (cursor !== undefined && after === null) throw new AppError('VALIDATION_FAILED');
    const pageLimit = clampLimit(limit, ADMIN_PAGE_DEFAULT, ADMIN_PAGE_MAX);
    const collation = sql.raw(`${COLLATE_KEYWORD} "${COLLATION_C}"`);
    let query = userQuery()
      .orderBy(sql`users.username ${collation}`)
      .limit(pageLimit + 1);
    if (after !== null) query = query.where(sql<boolean>`users.username ${collation} > ${after}`);
    const rows = await query.execute();
    const items = rows.slice(0, pageLimit).map(toView);
    const last = items[items.length - 1];
    return { items, nextCursor: rows.length > pageLimit && last !== undefined ? encodeCursor(last.username) : null };
  }

  async function getUser(userId: string): Promise<AdminUserView> {
    const row = await userQuery().where('users.id', '=', userId).executeTakeFirst();
    if (row === undefined) throw new AppError('NOT_FOUND');
    return toView(row);
  }

  async function findUserIdByUsername(username: string): Promise<string> {
    const row = await db.selectFrom('users').select('id').where('username', '=', username).executeTakeFirst();
    if (row === undefined) throw new AppError('NOT_FOUND');
    return row.id;
  }

  async function patchUser(
    actor: AdminActor,
    userId: string,
    patch: { readonly quotaBytes?: number | null | undefined; readonly role?: UserRole | undefined },
  ): Promise<AdminUserView> {
    const updated = await db.transaction().execute(async (trx) => {
      const target = await trx.selectFrom('users').selectAll().where('id', '=', userId).forUpdate().executeTakeFirst();
      if (target === undefined) throw new AppError('NOT_FOUND');
      if (patch.role !== undefined) {
        const admins = await trx
          .selectFrom('users')
          .select((builder) => builder.fn.countAll<string>().as('count'))
          .where('role', '=', 'admin')
          .executeTakeFirstOrThrow();
        const refusal = roleChangeRefusal({
          actorId: actor === 'cli' ? null : actor,
          targetId: userId,
          currentRole: target.role,
          newRole: patch.role,
          adminCount: Number(admins.count),
        });
        if (refusal !== null) throw new AdminRefusalError(refusal);
      }
      const changes = {
        ...(patch.role === undefined ? {} : { role: patch.role }),
        ...(patch.quotaBytes === undefined ? {} : { quotaBytes: patch.quotaBytes }),
      };
      await trx.updateTable('users').set({ ...changes, updatedAt: clock.now() }).where('id', '=', userId).execute();
      return { before: { role: target.role, quotaBytes: target.quotaBytes }, changes };
    });
    logger.info({ event: LOG_EVENTS.adminUserUpdated, actor, userId, before: updated.before, after: updated.changes });
    return getUser(userId);
  }

  async function createInvite(actor: AdminActor, role: UserRole, expiresInDays: number) {
    const created = await deps.createInvite(role, expiresInDays, actor === 'cli' ? null : actor);
    logger.info({ event: LOG_EVENTS.adminInviteCreated, actor, role, expiresInDays });
    return created;
  }

  async function listInvites(): Promise<InviteView[]> {
    return db
      .selectFrom('invites')
      .leftJoin('users', 'users.id', 'invites.redeemedBy')
      .select([
        'invites.id',
        'invites.role',
        'invites.createdAt',
        'invites.expiresAt',
        'invites.redeemedAt',
        'invites.revokedAt',
        'users.username as redeemedByUsername',
      ])
      .orderBy('invites.createdAt', 'desc')
      .limit(ADMIN_INVITES_LIST_MAX)
      .execute();
  }

  async function revokeInvite(actor: AdminActor, id: string): Promise<'revoked' | 'alreadyClosed'> {
    const invite = await db.selectFrom('invites').selectAll().where('id', '=', id).executeTakeFirst();
    if (invite === undefined) throw new AppError('NOT_FOUND');
    if (invite.redeemedAt !== null || invite.revokedAt !== null) return 'alreadyClosed';
    await db.updateTable('invites').set({ revokedAt: clock.now() }).where('id', '=', id).where('revokedAt', 'is', null).execute();
    logger.info({ event: LOG_EVENTS.adminInviteRevoked, actor, inviteId: id });
    return 'revoked';
  }

  async function stats(): Promise<AdminStats> {
    const activeSince = new Date(clock.now().getTime() - ADMIN_ACTIVE_WINDOW_DAYS * DAY_MS);
    const result = await sql<Record<keyof AdminStats, string | null>>`
      select
        (select count(*) from users) as users,
        (select count(distinct user_id) from sessions where last_used_at > ${activeSince}) as "activeUsers30d",
        (select count(*) from users where status = 'pending_deletion') as "pendingDeletion",
        (select count(*) from collections) as collections,
        (select count(*) from entries where purpose = 'journal') as entries,
        (select count(*) from objects where state = 'uploaded') as objects,
        (select coalesce(sum(used_bytes), 0) from users) as "totalBytes",
        (select sum(quota_bytes) from users where quota_bytes is not null) as "quotaBytesAllocated"`.execute(db);
    const row = result.rows[0];
    if (row === undefined) throw new AppError('INTERNAL');
    return {
      users: Number(row.users),
      activeUsers30d: Number(row.activeUsers30d),
      pendingDeletion: Number(row.pendingDeletion),
      collections: Number(row.collections),
      entries: Number(row.entries),
      objects: Number(row.objects),
      totalBytes: Number(row.totalBytes),
      quotaBytesAllocated: row.quotaBytesAllocated === null ? null : Number(row.quotaBytesAllocated),
    };
  }

  return { listUsers, getUser, findUserIdByUsername, patchUser, createInvite, listInvites, revokeInvite, stats };
}

export type AdminService = ReturnType<typeof createAdminService>;
