import type { Insertable, Kysely, Selectable } from 'kysely';
import type { Database, InvitesTable } from './schema.ts';

export type InviteRow = Selectable<InvitesTable>;
export type NewInvite = Insertable<InvitesTable>;

export function createInvitesRepository(db: Kysely<Database>) {
  return {
    async insert(invite: NewInvite): Promise<void> {
      await db.insertInto('invites').values(invite).execute();
    },

    findByTokenHashForUpdate(tokenHash: Buffer): Promise<InviteRow | undefined> {
      return db
        .selectFrom('invites')
        .selectAll()
        .where('tokenHash', '=', tokenHash)
        .forUpdate()
        .executeTakeFirst();
    },

    async markRedeemed(id: string, userId: string, now: Date): Promise<void> {
      await db
        .updateTable('invites')
        .set({ redeemedBy: userId, redeemedAt: now })
        .where('id', '=', id)
        .execute();
    },
  };
}
