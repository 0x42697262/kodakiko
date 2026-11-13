import { ROUTES, userDto, type UserDto } from '@kodakiko/contract';
import { z } from 'zod';
import { NetworkError, type ApiCall, type HttpClient } from '../../adapters/http/http';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import { createObservable, type Observable } from '../../composition/account-state';
import { STORAGE_KEYS } from '../../keys/storage-keys';
import { saveDeletionDueAt, saveRole } from '../account-storage';

const ADMIN_ROLE = 'admin';

const ME_CALL = (): ApiCall<UserDto> => ({
  method: 'GET',
  path: ROUTES.usersMe(),
  responseSchema: userDto,
  auth: 'session',
  idempotent: true,
});

export type AccountService = {
  readonly refreshMe: () => Promise<UserDto | null>;
  readonly user: Observable<UserDto | null>;
  readonly isAdmin: () => Promise<boolean>;
  readonly clear: () => void;
};

export function createAccountService(deps: { readonly http: HttpClient; readonly rowStore: RowStore }): AccountService {
  const user = createObservable<UserDto | null>(null);

  async function refreshMe(): Promise<UserDto | null> {
    try {
      const me = await deps.http.request(ME_CALL());
      await saveRole(deps.rowStore, me.role);
      await saveDeletionDueAt(deps.rowStore, me.deletionDueAt);
      user.set(me);
      return me;
    } catch (error) {
      if (error instanceof NetworkError) return null;
      throw error;
    }
  }

  async function isAdmin(): Promise<boolean> {
    const role = await deps.rowStore.kvGetJson(STORAGE_KEYS.accountRole, z.string()).catch(() => null);
    return role === ADMIN_ROLE;
  }

  return { refreshMe, user, isAdmin, clear: () => user.set(null) };
}
