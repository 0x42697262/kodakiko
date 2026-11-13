import { beforeEach, describe, expect, it } from 'vitest';
import { ROUTES } from '@kodakiko/contract';
import { z } from 'zod';
import { NetworkError } from '../src/adapters/http/http';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import { createAccountService, type AccountService } from '../src/services/account/account-service';
import { createAdminClient } from '../src/services/admin/admin-service';
import { createFakeHttp, type FakeHttp } from './support/fake-http';
import { createMemoryRowStore } from './support/memory-rowstore';

const USER = {
  id: 'U'.repeat(22),
  username: 'ada',
  role: 'admin',
  status: 'active',
  quotaBytes: null,
  usedBytes: 10,
  createdAt: '2026-06-01T10:00:00.000Z',
  deletionDueAt: null,
};

let fake: FakeHttp;
let rowStore: ReturnType<typeof createMemoryRowStore>;
let service: AccountService;
let online: boolean;

beforeEach(async () => {
  fake = createFakeHttp();
  rowStore = createMemoryRowStore();
  await rowStore.open('account');
  online = true;
  fake.on('GET', ROUTES.usersMe(), () => {
    if (!online) throw new NetworkError();
    return USER;
  });
  service = createAccountService({ http: fake.http, rowStore });
});

describe('account service', () => {
  it('refreshMe stores the role and deletion date and keeps the user in memory', async () => {
    const user = await service.refreshMe();
    expect(user?.username).toBe('ada');
    expect(await rowStore.kvGetJson(STORAGE_KEYS.accountRole, z.string())).toBe('admin');
    expect(service.user.get()?.usedBytes).toBe(10);
    expect(await service.isAdmin()).toBe(true);
  });

  it('keeps the last values when offline', async () => {
    await service.refreshMe();
    online = false;
    expect(await service.refreshMe()).toBeNull();
    expect(service.user.get()?.username).toBe('ada');
    expect(await service.isAdmin()).toBe(true);
  });
});

describe('admin client', () => {
  it('wraps the admin routes', async () => {
    fake.on('GET', ROUTES.adminStats(), () => ({ users: 2, activeUsers30d: 1, pendingDeletion: 0, collections: 3, entries: 4, objects: 5, totalBytes: 6, quotaBytesAllocated: null }));
    fake.on('PATCH', ROUTES.adminUser(USER.id), (request) => ({ ...USER, ...(request.body as object), sessionCount: 1, lastActiveAt: null }));
    const admin = createAdminClient({ http: fake.http });
    expect((await admin.stats()).entries).toBe(4);
    expect((await admin.patchUser(USER.id, { quotaBytes: 1024 })).quotaBytes).toBe(1024);
  });
});
