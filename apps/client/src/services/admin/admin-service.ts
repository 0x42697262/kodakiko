import {
  ROUTES,
  adminInviteCreateResponse,
  adminStatsResponse,
  adminUserDto,
  adminUserListResponse,
  inviteListResponse,
  type AdminInviteCreateRequest,
  type AdminUserPatchRequest,
} from '@kodakiko/contract';
import { z } from 'zod';
import type { ApiCall, HttpClient } from '../../adapters/http/http';

function call<Response>(method: ApiCall<Response>['method'], path: string, schema: z.ZodType<Response>, body?: unknown): ApiCall<Response> {
  return { method, path, responseSchema: schema, auth: 'session', idempotent: method !== 'POST', ...(body === undefined ? {} : { body }) };
}

export function createAdminClient(deps: { readonly http: HttpClient }) {
  const { http } = deps;
  return {
    listUsers: (cursor: string | null) =>
      http.request(call('GET', `${ROUTES.adminUsers()}${cursor === null ? '' : `?cursor=${encodeURIComponent(cursor)}`}`, adminUserListResponse)),
    patchUser: (id: string, patch: AdminUserPatchRequest) => http.request(call('PATCH', ROUTES.adminUser(id), adminUserDto, patch)),
    createInvite: (input: AdminInviteCreateRequest) => http.request(call('POST', ROUTES.adminInvites(), adminInviteCreateResponse, input)),
    listInvites: () => http.request(call('GET', ROUTES.adminInvites(), inviteListResponse)),
    revokeInvite: (id: string) => http.request(call('DELETE', ROUTES.adminInvite(id), z.unknown())),
    stats: () => http.request(call('GET', ROUTES.adminStats(), adminStatsResponse)),
  };
}

export type AdminClient = ReturnType<typeof createAdminClient>;
