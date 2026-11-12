import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  collectionDto,
  collectionIdParams,
  collectionListQuery,
  collectionListResponse,
  collectionPatchRequest,
} from '@kodakiko/contract';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { CollectionsController } from '../controllers/collections-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type CollectionsRouteOptions = {
  readonly controller: CollectionsController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerCollectionsRoutes(
  app: FastifyInstance,
  options: CollectionsRouteOptions,
): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();
  const sessionConfig = (routeId: string) => ({
    ...rateLimitFor('collections'),
    routeId,
    auth: 'session' as const,
  });

  typed.get(
    ROUTE_PATTERNS.collections,
    {
      config: sessionConfig('collectionsList'),
      preHandler: requireSession,
      schema: { querystring: collectionListQuery, response: { 200: collectionListResponse } },
    },
    async (request) => controller.list(authContextOf(request), request.query),
  );

  typed.get(
    ROUTE_PATTERNS.collection,
    {
      config: sessionConfig('collectionGet'),
      preHandler: requireSession,
      schema: { params: collectionIdParams, response: { 200: collectionDto } },
    },
    async (request) => controller.get(authContextOf(request), request.params.id),
  );

  typed.patch(
    ROUTE_PATTERNS.collection,
    {
      config: sessionConfig('collectionRename'),
      preHandler: requireSession,
      schema: {
        params: collectionIdParams,
        body: collectionPatchRequest,
        response: { 200: collectionDto },
      },
    },
    async (request) => controller.rename(authContextOf(request), request.params.id, request.body),
  );
}
