import type { Insertable, Kysely, Selectable } from 'kysely';
import type { UserRole, UserStatus } from '../../domain/enums.ts';
import type { Database, SessionsTable } from './schema.ts';

export type SessionRow = Selectable<SessionsTable>;
export type NewSession = Insertable<SessionsTable>;
export type SessionWithUser = SessionRow & {
  readonly userStatus: UserStatus;
  readonly userRole: UserRole;
};

export function createSessionsRepository(db: Kysely<Database>) {
  return {
    async insert(session: NewSession): Promise<void> {
      await db.insertInto('sessions').values(session).execute();
    },

    findByTokenHash(tokenHash: Buffer): Promise<SessionWithUser | undefined> {
      return db
        .selectFrom('sessions')
        .innerJoin('users', 'users.id', 'sessions.userId')
        .select([
          'sessions.id',
          'sessions.userId',
          'sessions.tokenHash',
          'sessions.deviceLabel',
          'sessions.createdAt',
          'sessions.lastUsedAt',
          'sessions.expiresAt',
          'users.status as userStatus',
          'users.role as userRole',
        ])
        .where('sessions.tokenHash', '=', tokenHash)
        .executeTakeFirst();
    },

    async touch(id: string, lastUsedAt: Date, expiresAt: Date): Promise<void> {
      await db
        .updateTable('sessions')
        .set({ lastUsedAt, expiresAt })
        .where('id', '=', id)
        .execute();
    },

    async deleteById(id: string): Promise<number> {
      const result = await db.deleteFrom('sessions').where('id', '=', id).executeTakeFirst();
      return Number(result.numDeletedRows);
    },

    async deleteAllForUser(userId: string, exceptSessionId?: string): Promise<void> {
      let statement = db.deleteFrom('sessions').where('userId', '=', userId);
      if (exceptSessionId !== undefined) statement = statement.where('id', '<>', exceptSessionId);
      await statement.execute();
    },

    async deleteExpired(now: Date, limit: number): Promise<number> {
      const result = await db
        .deleteFrom('sessions')
        .where(
          'id',
          'in',
          db.selectFrom('sessions').select('id').where('expiresAt', '<=', now).limit(limit),
        )
        .executeTakeFirst();
      return Number(result.numDeletedRows);
    },
  };
}
