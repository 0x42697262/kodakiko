import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  collectionIdParams,
  diffPage,
  diffQuery,
  syncStateResponse,
} from '@kodakiko/contract';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { CollectionsController } from '../controllers/collections-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type SyncRouteOptions = {
  readonly controller: CollectionsController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerSyncRoutes(app: FastifyInstance, options: SyncRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();

  typed.get(
    ROUTE_PATTERNS.collectionDiff,
    {
      config: { ...rateLimitFor('collections'), routeId: 'collectionDiff', auth: 'session' },
      preHandler: requireSession,
      schema: { params: collectionIdParams, querystring: diffQuery, response: { 200: diffPage } },
    },
    async (request) =>
      controller.diff(authContextOf(request), request.params.id, request.query),
  );

  typed.get(
    ROUTE_PATTERNS.syncState,
    {
      config: { ...rateLimitFor('collections'), routeId: 'syncState', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { 200: syncStateResponse } },
    },
    async (request) => controller.syncState(authContextOf(request)),
  );
}
