import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  collectionCreateRequest,
  collectionDto,
  collectionEntryAddRequest,
  collectionEntryParams,
  collectionIdParams,
  publicKeyResponse,
  shareCreateRequest,
  shareListResponse,
  shareParams,
  userPublicKeyParams,
} from '@kodakiko/contract';
import { z } from 'zod';
import { HTTP_STATUS } from '../../config/constants.ts';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { SharingController } from '../controllers/sharing-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type SharingRouteOptions = {
  readonly controller: SharingController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

const emptyResponse = z.object({});

export function registerSharingRoutes(app: FastifyInstance, options: SharingRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();
  const config = (group: 'collections' | 'users', routeId: string) => ({
    ...rateLimitFor(group),
    routeId,
    auth: 'session' as const,
  });

  typed.post(
    ROUTE_PATTERNS.collections,
    {
      config: config('collections', 'collectionCreate'),
      preHandler: requireSession,
      schema: {
        body: collectionCreateRequest,
        response: { [HTTP_STATUS.ok]: collectionDto, [HTTP_STATUS.created]: collectionDto },
      },
    },
    async (request, reply) => {
      const { collection, created } = await controller.create(authContextOf(request), request.body);
      return reply.status(created ? HTTP_STATUS.created : HTTP_STATUS.ok).send(collection);
    },
  );

  typed.delete(
    ROUTE_PATTERNS.collection,
    {
      config: config('collections', 'collectionDelete'),
      preHandler: requireSession,
      schema: { params: collectionIdParams },
    },
    async (request, reply) => {
      await controller.remove(authContextOf(request), request.params.id);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.post(
    ROUTE_PATTERNS.collectionEntries,
    {
      config: config('collections', 'collectionEntryAdd'),
      preHandler: requireSession,
      schema: {
        params: collectionIdParams,
        body: collectionEntryAddRequest,
        response: { [HTTP_STATUS.ok]: emptyResponse, [HTTP_STATUS.created]: emptyResponse },
      },
    },
    async (request, reply) => {
      const { created } = await controller.addEntry(
        authContextOf(request),
        request.params.id,
        request.body.entryId,
        request.body.entryKeyByCollection,
      );
      return reply.status(created ? HTTP_STATUS.created : HTTP_STATUS.ok).send({});
    },
  );

  typed.delete(
    ROUTE_PATTERNS.collectionEntry,
    {
      config: config('collections', 'collectionEntryRemove'),
      preHandler: requireSession,
      schema: { params: collectionEntryParams },
    },
    async (request, reply) => {
      await controller.removeEntry(authContextOf(request), request.params.id, request.params.entryId);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.post(
    ROUTE_PATTERNS.collectionShares,
    {
      config: config('collections', 'collectionShareCreate'),
      preHandler: requireSession,
      schema: {
        params: collectionIdParams,
        body: shareCreateRequest,
        response: { [HTTP_STATUS.ok]: emptyResponse, [HTTP_STATUS.created]: emptyResponse },
      },
    },
    async (request, reply) => {
      const { created } = await controller.share(
        authContextOf(request),
        request.params.id,
        request.body.recipientUserId,
        request.body.sealedCollectionKey,
      );
      return reply.status(created ? HTTP_STATUS.created : HTTP_STATUS.ok).send({});
    },
  );

  typed.get(
    ROUTE_PATTERNS.collectionShares,
    {
      config: config('collections', 'collectionShareList'),
      preHandler: requireSession,
      schema: { params: collectionIdParams, response: { [HTTP_STATUS.ok]: shareListResponse } },
    },
    async (request) => controller.listShares(authContextOf(request), request.params.id),
  );

  typed.delete(
    ROUTE_PATTERNS.collectionShare,
    {
      config: config('collections', 'collectionShareDelete'),
      preHandler: requireSession,
      schema: { params: shareParams },
    },
    async (request, reply) => {
      await controller.unshare(authContextOf(request), request.params.id, request.params.recipientUserId);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.get(
    ROUTE_PATTERNS.userPublicKey,
    {
      config: config('users', 'userPublicKey'),
      preHandler: requireSession,
      schema: { params: userPublicKeyParams, response: { [HTTP_STATUS.ok]: publicKeyResponse } },
    },
    async (request) => controller.publicKey(request.params.username),
  );
}
