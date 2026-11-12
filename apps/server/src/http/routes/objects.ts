import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  downloadResponse,
  objectDto,
  objectIdParams,
  partsResponse,
  presignRequest,
  presignResponse,
} from '@kodakiko/contract';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { ObjectsController } from '../controllers/objects-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type ObjectsRouteOptions = {
  readonly controller: ObjectsController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerObjectsRoutes(app: FastifyInstance, options: ObjectsRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();

  typed.post(
    ROUTE_PATTERNS.objectsPresign,
    {
      config: { ...rateLimitFor('media'), routeId: 'objectsPresign', auth: 'session' },
      preHandler: requireSession,
      schema: { body: presignRequest, response: { 200: presignResponse } },
    },
    async (request) => controller.presign(authContextOf(request), request.body),
  );

  typed.get(
    ROUTE_PATTERNS.objectParts,
    {
      config: { ...rateLimitFor('media'), routeId: 'objectParts', auth: 'session' },
      preHandler: requireSession,
      schema: { params: objectIdParams, response: { 200: partsResponse } },
    },
    async (request) => controller.parts(authContextOf(request), request.params.id),
  );

  typed.post(
    ROUTE_PATTERNS.objectConfirm,
    {
      config: { ...rateLimitFor('media'), routeId: 'objectConfirm', auth: 'session' },
      preHandler: requireSession,
      schema: { params: objectIdParams, response: { 200: objectDto } },
    },
    async (request) => controller.confirm(authContextOf(request), request.params.id),
  );

  typed.get(
    ROUTE_PATTERNS.objectDownload,
    {
      config: { ...rateLimitFor('media'), routeId: 'objectDownload', auth: 'session' },
      preHandler: requireSession,
      schema: { params: objectIdParams, response: { 200: downloadResponse } },
    },
    async (request) => controller.download(authContextOf(request), request.params.id),
  );
}
