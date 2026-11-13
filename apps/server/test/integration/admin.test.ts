import { randomBytes } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  ROUTES,
  adminInviteCreateResponse,
  adminStatsResponse,
  adminUserDto,
  adminUserListResponse,
  inviteListResponse,
} from '@kodakiko/contract';
import { runAdminCommand } from '../../src/cli-admin.ts';
import { createTestAccount, errorCodeOf, registerAccount } from './auth-helpers.ts';
import { createEntriesContext, createEntryFor, send, type EntriesContext } from './entries-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const STATUS_OK = 200;
const STATUS_CREATED = 201;
const STATUS_NO_CONTENT = 204;
const STATUS_NOT_FOUND = 404;
const STATUS_UNPROCESSABLE = 422;
const EXTRA_USERS = 120;
const GIB = 1_073_741_824;

let testDatabase: TestDatabase;
let context: EntriesContext;
let adminToken: string;
let adminId: string;
let memberToken: string;

function name(prefix: string): string {
  return `${prefix}${randomBytes(4).toString('hex')}`;
}

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createEntriesContext(testDatabase);
  const admin = await registerAccount(context, name('admin'), 'admin');
  adminToken = admin.token;
  adminId = admin.material.request.userId;
  memberToken = (await registerAccount(context, name('member'))).token;
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

describe('authorization', () => {
  it('answers 404 with the generic body to members on every admin route', async () => {
    const results = [
      await send(context.app, 'GET', ROUTES.adminUsers(), { token: memberToken }),
      await send(context.app, 'PATCH', ROUTES.adminUser(adminId), { token: memberToken, body: { quotaBytes: 1 } }),
      await send(context.app, 'POST', ROUTES.adminInvites(), { token: memberToken, body: { role: 'member', expiresInDays: 7 } }),
      await send(context.app, 'GET', ROUTES.adminInvites(), { token: memberToken }),
      await send(context.app, 'DELETE', ROUTES.adminInvite(adminId), { token: memberToken }),
      await send(context.app, 'GET', ROUTES.adminStats(), { token: memberToken }),
    ];
    for (const result of results) {
      expect(result.status).toBe(STATUS_NOT_FOUND);
      expect(errorCodeOf(result)).toBe('NOT_FOUND');
    }
  });
});

describe('users', () => {
  it('pages through every user by username', async () => {
    for (let index = 0; index < EXTRA_USERS; index += 1) await registerAccount(context, name(`p${String(index).padStart(3, '0')}`));
    const seen: string[] = [];
    let cursor: string | null = null;
    do {
      const query: string = cursor === null ? '?limit=50' : `?limit=50&cursor=${encodeURIComponent(cursor)}`;
      const page = adminUserListResponse.parse((await send(context.app, 'GET', `${ROUTES.adminUsers()}${query}`, { token: adminToken })).json);
      seen.push(...page.items.map((item) => item.username));
      cursor = page.nextCursor;
    } while (cursor !== null);
    const total = await context.db.selectFrom('users').select((builder) => builder.fn.countAll<string>().as('count')).executeTakeFirstOrThrow();
    expect(seen).toHaveLength(Number(total.count));
    expect([...seen].sort()).toEqual(seen);
    expect(new Set(seen).size).toBe(seen.length);
  });

  it('patches quota and role and refuses self-demotion', async () => {
    const target = await registerAccount(context, name('target'));
    const targetId = target.material.request.userId;
    const quota = await send(context.app, 'PATCH', ROUTES.adminUser(targetId), { token: adminToken, body: { quotaBytes: GIB } });
    expect(quota.status).toBe(STATUS_OK);
    expect(adminUserDto.parse(quota.json)).toMatchObject({ quotaBytes: GIB, sessionCount: 1 });
    expect(adminUserDto.parse(quota.json).lastActiveAt).not.toBeNull();
    const reset = await send(context.app, 'PATCH', ROUTES.adminUser(targetId), { token: adminToken, body: { quotaBytes: null, role: 'admin' } });
    expect(adminUserDto.parse(reset.json)).toMatchObject({ quotaBytes: null, role: 'admin' });
    const self = await send(context.app, 'PATCH', ROUTES.adminUser(adminId), { token: adminToken, body: { role: 'member' } });
    expect(self.status).toBe(STATUS_UNPROCESSABLE);
    expect((self.json as { error: { details: { reason: string } } }).error.details.reason).toBe('selfDemotion');
    const missing = await send(context.app, 'PATCH', ROUTES.adminUser('Z'.repeat(22)), { token: adminToken, body: { quotaBytes: 1 } });
    expect(missing.status).toBe(STATUS_NOT_FOUND);
    expect(context.logs.text()).toContain('admin.user_updated');
  });
});

describe('invites', () => {
  it('creates, lists, revokes, and a revoked invite cannot be used', async () => {
    const created = await send(context.app, 'POST', ROUTES.adminInvites(), { token: adminToken, body: { role: 'member', expiresInDays: 7 } });
    expect(created.status).toBe(STATUS_CREATED);
    const { inviteUrl } = adminInviteCreateResponse.parse(created.json);
    const token = inviteUrl.split('#invite=')[1] ?? inviteUrl.split('#')[1] ?? '';
    const listed = inviteListResponse.parse((await send(context.app, 'GET', ROUTES.adminInvites(), { token: adminToken })).json);
    const open = listed.items.find((item) => item.redeemedAt === null && item.revokedAt === null);
    expect(open).toMatchObject({ role: 'member', revokedAt: null, redeemedAt: null });
    expect(JSON.stringify(listed)).not.toContain(token);
    expect((await send(context.app, 'DELETE', ROUTES.adminInvite(open?.id ?? ''), { token: adminToken })).status).toBe(STATUS_NO_CONTENT);
    const after = inviteListResponse.parse((await send(context.app, 'GET', ROUTES.adminInvites(), { token: adminToken })).json);
    expect(after.items.find((item) => item.id === open?.id)?.revokedAt).not.toBeNull();
    const signUp = await createTestAccount(context, { inviteToken: token, username: name('late') });
    expect(errorCodeOf(signUp.response)).toBe('INVITE_INVALID');
    expect((await send(context.app, 'DELETE', ROUTES.adminInvite(open?.id ?? ''), { token: adminToken })).status).toBe(STATUS_NO_CONTENT);
    expect((await send(context.app, 'DELETE', ROUTES.adminInvite('Q'.repeat(22)), { token: adminToken })).status).toBe(STATUS_NOT_FOUND);
    expect(context.logs.text()).not.toContain(token);
  });

  it('lists the username of whoever redeemed an invite', async () => {
    const created = adminInviteCreateResponse.parse(
      (await send(context.app, 'POST', ROUTES.adminInvites(), { token: adminToken, body: { role: 'member', expiresInDays: 3 } })).json,
    );
    const token = created.inviteUrl.split('#')[1]?.replace(/^invite=/, '') ?? '';
    const username = name('joined');
    const account = await createTestAccount(context, { inviteToken: token, username });
    expect(account.token).not.toBeNull();
    const listed = inviteListResponse.parse((await send(context.app, 'GET', ROUTES.adminInvites(), { token: adminToken })).json);
    expect(listed.items.find((item) => item.redeemedByUsername === username)).toBeDefined();
  });
});

describe('stats', () => {
  it('counts users, entries, collections and bytes without exposing content', async () => {
    const owner = await registerAccount(context, name('stats'));
    const user = { token: owner.token, userId: owner.material.request.userId, homeId: owner.material.request.homeCollection.id, username: owner.username };
    await createEntryFor(context, user);
    await context.db.updateTable('users').set({ quotaBytes: 5 * GIB }).where('id', '=', user.userId).execute();
    const stats = adminStatsResponse.parse((await send(context.app, 'GET', ROUTES.adminStats(), { token: adminToken })).json);
    const users = await context.db.selectFrom('users').select((builder) => builder.fn.countAll<string>().as('count')).executeTakeFirstOrThrow();
    const entries = await context.db.selectFrom('entries').select((builder) => builder.fn.countAll<string>().as('count')).where('purpose', '=', 'journal').executeTakeFirstOrThrow();
    const collections = await context.db.selectFrom('collections').select((builder) => builder.fn.countAll<string>().as('count')).executeTakeFirstOrThrow();
    const quotas = await context.db.selectFrom('users').select((builder) => builder.fn.sum<string>('quotaBytes').as('sum')).executeTakeFirstOrThrow();
    expect(stats.users).toBe(Number(users.count));
    expect(stats.entries).toBe(Number(entries.count));
    expect(stats.collections).toBe(Number(collections.count));
    expect(stats.activeUsers30d).toBe(stats.users);
    expect(stats.pendingDeletion).toBe(0);
    expect(stats.quotaBytesAllocated).toBe(Number(quotas.sum));
  });
});

describe('CLI', () => {
  it('runs list-users, set-quota, set-role (last admin refused), list-invites, revoke-invite and stats', async () => {
    const fresh = await createTestDatabase();
    try {
      const freshContext = await createEntriesContext(fresh);
      const onlyAdmin = await registerAccount(freshContext, 'solo-admin', 'admin');
      const member = await registerAccount(freshContext, 'plain-member');
      await freshContext.close();
      const lines: string[] = [];
      const deps = { db: fresh.db, settings: context.settings, print: (line: string) => lines.push(line), logger: context.app.log };
      expect(await runAdminCommand('list-users', [], deps)).toBe(0);
      expect(lines.join('\n')).toMatch(/plain-member\s+member\s+active/);
      lines.length = 0;
      expect(await runAdminCommand('set-quota', ['--username', 'plain-member', '--bytes', String(GIB)], deps)).toBe(0);
      expect(lines.join('\n')).toContain('1.0 GiB');
      expect(await runAdminCommand('set-quota', ['--username', 'plain-member', '--bytes', 'default'], deps)).toBe(0);
      lines.length = 0;
      expect(await runAdminCommand('set-role', ['--username', 'solo-admin', '--role', 'member'], deps)).not.toBe(0);
      expect(lines.join('\n')).toContain('lastAdmin');
      lines.length = 0;
      expect(await runAdminCommand('list-users', ['--json'], deps)).toBe(0);
      const parsed = JSON.parse(lines.join('\n')) as { username: string; role: string }[];
      expect(parsed.map((user) => user.username).sort()).toEqual(['plain-member', 'solo-admin']);
      expect(parsed.find((user) => user.username === 'solo-admin')?.role).toBe('admin');
      lines.length = 0;
      const invite = await fresh.db.selectFrom('invites').select('id').where('redeemedBy', '=', member.material.request.userId).executeTakeFirstOrThrow();
      expect(await runAdminCommand('list-invites', [], deps)).toBe(0);
      expect(lines.join('\n')).toContain('redeemed by plain-member');
      lines.length = 0;
      expect(await runAdminCommand('revoke-invite', ['--id', invite.id], deps)).toBe(0);
      expect(lines.join('\n')).toContain('already closed');
      lines.length = 0;
      expect(await runAdminCommand('stats', ['--json'], deps)).toBe(0);
      expect(adminStatsResponse.parse(JSON.parse(lines.join('\n'))).users).toBe(2);
      void onlyAdmin;
    } finally {
      await fresh.drop();
    }
  });
});

void STATUS_CREATED;
