import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  entryCreateRequest,
  entryDto,
  entryIdParams,
  entryPatchRequest,
  mediaCreateRequest,
  mediaDto,
} from '@kodakiko/contract';
import { HTTP_STATUS } from '../../config/constants.ts';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { EntriesController } from '../controllers/entries-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type EntriesRouteOptions = {
  readonly controller: EntriesController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerEntriesRoutes(app: FastifyInstance, options: EntriesRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();
  const sessionConfig = (routeId: string) => ({
    ...rateLimitFor('entries'),
    routeId,
    auth: 'session' as const,
  });

  typed.post(
    ROUTE_PATTERNS.entries,
    {
      config: sessionConfig('entriesCreate'),
      preHandler: requireSession,
      schema: {
        body: entryCreateRequest,
        response: { [HTTP_STATUS.ok]: entryDto, [HTTP_STATUS.created]: entryDto },
      },
    },
    async (request, reply) => {
      const { entry, created } = await controller.create(authContextOf(request), request.body);
      return reply.status(created ? HTTP_STATUS.created : HTTP_STATUS.ok).send(entry);
    },
  );

  typed.get(
    ROUTE_PATTERNS.entry,
    {
      config: sessionConfig('entryGet'),
      preHandler: requireSession,
      schema: { params: entryIdParams, response: { [HTTP_STATUS.ok]: entryDto } },
    },
    async (request) => controller.get(authContextOf(request), request.params.id),
  );

  typed.patch(
    ROUTE_PATTERNS.entry,
    {
      config: sessionConfig('entryPatch'),
      preHandler: requireSession,
      schema: {
        params: entryIdParams,
        body: entryPatchRequest,
        response: { [HTTP_STATUS.ok]: entryDto },
      },
    },
    async (request) => controller.patch(authContextOf(request), request.params.id, request.body),
  );

  typed.delete(
    ROUTE_PATTERNS.entry,
    {
      config: sessionConfig('entryDelete'),
      preHandler: requireSession,
      schema: { params: entryIdParams },
    },
    async (request, reply) => {
      await controller.remove(authContextOf(request), request.params.id);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.post(
    ROUTE_PATTERNS.entryTrash,
    {
      config: sessionConfig('entryTrash'),
      preHandler: requireSession,
      schema: { params: entryIdParams, response: { [HTTP_STATUS.ok]: entryDto } },
    },
    async (request) => controller.trash(authContextOf(request), request.params.id),
  );

  typed.post(
    ROUTE_PATTERNS.entryRestore,
    {
      config: sessionConfig('entryRestore'),
      preHandler: requireSession,
      schema: { params: entryIdParams, response: { [HTTP_STATUS.ok]: entryDto } },
    },
    async (request) => controller.restore(authContextOf(request), request.params.id),
  );

  typed.post(
    ROUTE_PATTERNS.entryMedia,
    {
      config: sessionConfig('entryMediaAdd'),
      preHandler: requireSession,
      schema: {
        params: entryIdParams,
        body: mediaCreateRequest,
        response: { [HTTP_STATUS.ok]: mediaDto, [HTTP_STATUS.created]: mediaDto },
      },
    },
    async (request, reply) => {
      const { media, created } = await controller.addMedia(
        authContextOf(request),
        request.params.id,
        request.body,
      );
      return reply.status(created ? HTTP_STATUS.created : HTTP_STATUS.ok).send(media);
    },
  );
}
