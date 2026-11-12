import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { ROUTE_PATTERNS, deletionRequestResponse, userDto } from '@kodakiko/contract';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { UsersController } from '../controllers/users-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

const STATUS_ACCEPTED = 202;

export type UsersRouteOptions = {
  readonly controller: UsersController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerUsersRoutes(app: FastifyInstance, options: UsersRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();

  typed.get(
    ROUTE_PATTERNS.usersMe,
    {
      config: { ...rateLimitFor('users'), routeId: 'usersMe', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { 200: userDto } },
    },
    async (request) => controller.me(authContextOf(request)),
  );

  typed.post(
    ROUTE_PATTERNS.usersMeDelete,
    {
      config: { ...rateLimitFor('users'), routeId: 'usersMeDelete', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { [STATUS_ACCEPTED]: deletionRequestResponse } },
    },
    async (request, reply) => {
      const result = await controller.requestDeletion(authContextOf(request));
      return reply.status(STATUS_ACCEPTED).send(result);
    },
  );

  typed.post(
    ROUTE_PATTERNS.usersMeDeleteCancel,
    {
      config: { ...rateLimitFor('users'), routeId: 'usersMeDeleteCancel', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { 200: userDto } },
    },
    async (request) => controller.cancelDeletion(authContextOf(request)),
  );
}
