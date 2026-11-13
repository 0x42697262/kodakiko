import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  adminIdParams,
  adminInviteCreateRequest,
  adminInviteCreateResponse,
  adminStatsResponse,
  adminUserDto,
  adminUserListQuery,
  adminUserListResponse,
  adminUserPatchRequest,
  inviteListResponse,
  type AdminStatsResponse,
  type AdminUserDto,
  type InviteDto,
} from '@kodakiko/contract';
import { HTTP_STATUS } from '../../config/constants.ts';
import { AppError } from '../../domain/errors.ts';
import type { AdminService, AdminStats, AdminUserView, InviteView } from '../../services/admin-service.ts';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type AdminRouteOptions = {
  readonly service: AdminService;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function toAdminUserDto(view: AdminUserView): AdminUserDto {
  return {
    id: view.id,
    username: view.username,
    role: view.role,
    status: view.status,
    quotaBytes: view.quotaBytes,
    usedBytes: view.usedBytes,
    createdAt: view.createdAt.toISOString(),
    deletionDueAt: view.deletionDueAt?.toISOString() ?? null,
    sessionCount: view.sessionCount,
    lastActiveAt: view.lastActiveAt?.toISOString() ?? null,
  } as AdminUserDto;
}

export function toInviteDto(view: InviteView): InviteDto {
  return {
    id: view.id,
    role: view.role,
    createdAt: view.createdAt.toISOString(),
    expiresAt: view.expiresAt.toISOString(),
    redeemedAt: view.redeemedAt?.toISOString() ?? null,
    revokedAt: view.revokedAt?.toISOString() ?? null,
    redeemedByUsername: view.redeemedByUsername,
  } as InviteDto;
}

export function toStatsDto(stats: AdminStats): AdminStatsResponse {
  return stats;
}

export function registerAdminRoutes(app: FastifyInstance, options: AdminRouteOptions): void {
  const { service, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();
  const requireAdmin = async (request: FastifyRequest, reply: FastifyReply) => {
    await requireSession(request, reply);
    if (authContextOf(request).role !== 'admin') throw new AppError('NOT_FOUND');
  };
  const config = (routeId: string) => ({ ...rateLimitFor('admin'), routeId, auth: 'session' as const });

  typed.get(
    ROUTE_PATTERNS.adminUsers,
    { config: config('adminUsers'), preHandler: requireAdmin, schema: { querystring: adminUserListQuery, response: { [HTTP_STATUS.ok]: adminUserListResponse } } },
    async (request) => {
      const page = await service.listUsers(request.query.cursor, request.query.limit);
      return { items: page.items.map(toAdminUserDto), nextCursor: page.nextCursor };
    },
  );

  typed.patch(
    ROUTE_PATTERNS.adminUser,
    { config: config('adminUserPatch'), preHandler: requireAdmin, schema: { params: adminIdParams, body: adminUserPatchRequest, response: { [HTTP_STATUS.ok]: adminUserDto } } },
    async (request) => toAdminUserDto(await service.patchUser(authContextOf(request).userId, request.params.id, request.body)),
  );

  typed.post(
    ROUTE_PATTERNS.adminInvites,
    { config: config('adminInviteCreate'), preHandler: requireAdmin, schema: { body: adminInviteCreateRequest, response: { [HTTP_STATUS.created]: adminInviteCreateResponse } } },
    async (request, reply) => {
      const created = await service.createInvite(authContextOf(request).userId, request.body.role, request.body.expiresInDays);
      return reply.status(HTTP_STATUS.created).send({ inviteUrl: created.inviteUrl, expiresAt: created.expiresAt.toISOString() });
    },
  );

  typed.get(
    ROUTE_PATTERNS.adminInvites,
    { config: config('adminInvites'), preHandler: requireAdmin, schema: { response: { [HTTP_STATUS.ok]: inviteListResponse } } },
    async () => ({ items: (await service.listInvites()).map(toInviteDto) }),
  );

  typed.delete(
    ROUTE_PATTERNS.adminInvite,
    { config: config('adminInviteRevoke'), preHandler: requireAdmin, schema: { params: adminIdParams } },
    async (request, reply) => {
      await service.revokeInvite(authContextOf(request).userId, request.params.id);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.get(
    ROUTE_PATTERNS.adminStats,
    { config: config('adminStats'), preHandler: requireAdmin, schema: { response: { [HTTP_STATUS.ok]: adminStatsResponse } } },
    async () => toStatsDto(await service.stats()),
  );
}
