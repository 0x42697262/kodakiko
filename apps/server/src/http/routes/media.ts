import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { ROUTE_PATTERNS, mediaIdParams } from '@kodakiko/contract';
import { HTTP_STATUS } from '../../config/constants.ts';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { EntriesController } from '../controllers/entries-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type MediaRouteOptions = {
  readonly controller: EntriesController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerMediaRoutes(app: FastifyInstance, options: MediaRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  app.withTypeProvider<ZodTypeProvider>().delete(
    ROUTE_PATTERNS.media,
    {
      config: { ...rateLimitFor('media'), routeId: 'mediaDelete', auth: 'session' },
      preHandler: requireSession,
      schema: { params: mediaIdParams },
    },
    async (request, reply) => {
      await controller.removeMedia(authContextOf(request), request.params.id);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );
}
