import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  LINK_TOKEN_HEADER,
  LINK_VIEW_TOKEN_HEADER,
  ROUTE_PATTERNS,
  decodeBytes,
  linkEntriesQuery,
  linkEntriesResponse,
  linkOpenResponse,
  publicLinkCreateRequest,
  publicLinkDto,
  publicLinkListResponse,
  publicLinkObjectParams,
  publicLinkParams,
} from '@kodakiko/contract';
import type { ByteRange } from '../../adapters/storage/object-store.ts';
import { HTTP_STATUS, ROBOTS_HEADER, ROBOTS_NOINDEX } from '../../config/constants.ts';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { PublicLinksController } from '../controllers/public-links-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

const HTTP_PARTIAL = 206;
const HTTP_RANGE_NOT_SATISFIABLE = 416;
const SINGLE_RANGE = /^bytes=(\d+)-(\d*)$/;

export type PublicLinksRouteOptions = {
  readonly controller: PublicLinksController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

function header(request: FastifyRequest, name: string): string | undefined {
  const value = request.headers[name];
  return typeof value === 'string' ? value : undefined;
}

function parseRange(value: string | undefined): ByteRange | null | 'invalid' {
  if (value === undefined) return null;
  const match = SINGLE_RANGE.exec(value);
  if (match === null) return 'invalid';
  const start = Number(match[1]);
  const end = match[2] === '' || match[2] === undefined ? Number.MAX_SAFE_INTEGER : Number(match[2]);
  return start <= end ? { start, end } : 'invalid';
}

function noindex(reply: FastifyReply): void {
  reply.header(ROBOTS_HEADER, ROBOTS_NOINDEX);
}

export function registerPublicLinksRoutes(app: FastifyInstance, options: PublicLinksRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();

  typed.post(
    ROUTE_PATTERNS.publicLinks,
    {
      config: { ...rateLimitFor('publicLinks'), routeId: 'publicLinkCreate', auth: 'session' },
      preHandler: requireSession,
      schema: { body: publicLinkCreateRequest, response: { [HTTP_STATUS.created]: publicLinkDto } },
    },
    async (request, reply) => {
      const created = await controller.create(authContextOf(request), {
        collectionId: request.body.collectionId,
        accessToken: decodeBytes(request.body.accessToken),
        expiry: request.body.expiry,
      });
      return reply.status(HTTP_STATUS.created).send(created);
    },
  );

  typed.get(
    ROUTE_PATTERNS.publicLinks,
    {
      config: { ...rateLimitFor('publicLinks'), routeId: 'publicLinkList', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { [HTTP_STATUS.ok]: publicLinkListResponse } },
    },
    async (request) => controller.list(authContextOf(request)),
  );

  typed.delete(
    ROUTE_PATTERNS.publicLink,
    {
      config: { ...rateLimitFor('publicLinks'), routeId: 'publicLinkRevoke', auth: 'session' },
      preHandler: requireSession,
      schema: { params: publicLinkParams },
    },
    async (request, reply) => {
      await controller.revoke(authContextOf(request), request.params.linkId);
      return reply.status(HTTP_STATUS.noContent).send();
    },
  );

  typed.post(
    ROUTE_PATTERNS.publicLinkOpen,
    {
      config: { ...rateLimitFor('publicLinkOpen'), routeId: 'publicLinkOpen', auth: 'none' },
      schema: { params: publicLinkParams, response: { [HTTP_STATUS.ok]: linkOpenResponse } },
    },
    async (request, reply) => {
      noindex(reply);
      return controller.open(request.params.linkId, header(request, LINK_TOKEN_HEADER));
    },
  );

  typed.get(
    ROUTE_PATTERNS.publicLinkEntries,
    {
      config: { ...rateLimitFor('publicLinkEntries'), routeId: 'publicLinkEntries', auth: 'none' },
      schema: { params: publicLinkParams, querystring: linkEntriesQuery, response: { [HTTP_STATUS.ok]: linkEntriesResponse } },
    },
    async (request, reply) => {
      noindex(reply);
      return controller.entries(
        request.params.linkId,
        header(request, LINK_TOKEN_HEADER),
        header(request, LINK_VIEW_TOKEN_HEADER),
        request.query,
      );
    },
  );

  typed.get(
    ROUTE_PATTERNS.publicLinkObjectContent,
    {
      config: { ...rateLimitFor('publicLinkContent'), routeId: 'publicLinkContent', auth: 'none' },
      schema: { params: publicLinkObjectParams },
    },
    async (request, reply) => {
      noindex(reply);
      const range = parseRange(header(request, 'range'));
      if (range === 'invalid') return reply.status(HTTP_RANGE_NOT_SATISFIABLE).send();
      const stream = await controller.content(
        request.params.linkId,
        header(request, LINK_TOKEN_HEADER),
        header(request, LINK_VIEW_TOKEN_HEADER),
        request.params.objectId,
        range,
      );
      reply
        .header('content-type', 'application/octet-stream')
        .header('content-length', String(stream.sizeBytes))
        .header('accept-ranges', 'bytes')
        .header('cache-control', 'no-store');
      if (range !== null) {
        const end = Math.min(range.end, stream.totalSizeBytes - 1);
        reply.status(HTTP_PARTIAL).header('content-range', `bytes ${range.start}-${end}/${stream.totalSizeBytes}`);
      } else {
        reply.status(HTTP_STATUS.ok);
      }
      return reply.send(stream.body);
    },
  );
}
