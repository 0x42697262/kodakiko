import type { Insertable, Kysely, Selectable } from 'kysely';
import {
  PG_UNIQUE_VIOLATION,
  USERS_ID_CONSTRAINT,
  USERS_USERNAME_CONSTRAINT,
} from '../../config/constants.ts';
import type { UserStatus } from '../../domain/enums.ts';
import type { Database, UsersTable } from './schema.ts';

export type UserRow = Selectable<UsersTable>;
export type NewUser = Insertable<UsersTable>;
export type InsertUserResult = 'inserted' | 'usernameTaken' | 'idConflict';

export type LoginMaterialUpdate = {
  readonly kdfSalt: Buffer;
  readonly kdfOpsLimit: number;
  readonly kdfMemLimitBytes: number;
  readonly loginKeyHash: Buffer;
  readonly masterKeyByKek: Buffer;
  readonly updatedAt: Date;
};

type PgError = { readonly code?: string; readonly constraint?: string };

function insertResultOf(error: unknown): InsertUserResult {
  const { code, constraint } = error as PgError;
  if (code === PG_UNIQUE_VIOLATION && constraint === USERS_USERNAME_CONSTRAINT) {
    return 'usernameTaken';
  }
  if (code === PG_UNIQUE_VIOLATION && constraint === USERS_ID_CONSTRAINT) return 'idConflict';
  throw error;
}

export function createUsersRepository(db: Kysely<Database>) {
  return {
    async insert(user: NewUser): Promise<InsertUserResult> {
      try {
        await db.insertInto('users').values(user).execute();
        return 'inserted';
      } catch (error) {
        return insertResultOf(error);
      }
    },

    findByUsername(username: string): Promise<UserRow | undefined> {
      return db.selectFrom('users').selectAll().where('username', '=', username).executeTakeFirst();
    },

    findById(id: string): Promise<UserRow | undefined> {
      return db.selectFrom('users').selectAll().where('id', '=', id).executeTakeFirst();
    },

    findByIdForUpdate(id: string): Promise<UserRow | undefined> {
      return db
        .selectFrom('users')
        .selectAll()
        .where('id', '=', id)
        .forUpdate()
        .executeTakeFirst();
    },

    findByUsernameForUpdate(username: string): Promise<UserRow | undefined> {
      return db
        .selectFrom('users')
        .selectAll()
        .where('username', '=', username)
        .forUpdate()
        .executeTakeFirst();
    },

    async updateLoginMaterial(id: string, update: LoginMaterialUpdate): Promise<void> {
      await db.updateTable('users').set(update).where('id', '=', id).execute();
    },

    async setStatus(
      id: string,
      status: UserStatus,
      deletionDueAt: Date | null,
      updatedAt: Date,
    ): Promise<void> {
      await db
        .updateTable('users')
        .set({ status, deletionDueAt, updatedAt })
        .where('id', '=', id)
        .execute();
    },

    async cancelPendingDeletion(id: string, now: Date): Promise<number> {
      const result = await db
        .updateTable('users')
        .set({ status: 'active', deletionDueAt: null, updatedAt: now })
        .where('id', '=', id)
        .where('status', '=', 'pending_deletion')
        .where('deletionDueAt', '>', now)
        .executeTakeFirst();
      return Number(result.numUpdatedRows);
    },
  };
}
