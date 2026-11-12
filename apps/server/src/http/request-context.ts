import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import {
  API_PATH_PREFIX,
  API_ROOT_PATH,
  CACHE_CONTROL_NO_STORE,
  HTTP_HEADERS,
  HTTPS_SCHEME,
  QUERY_SEPARATOR,
} from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { AppError } from '../domain/errors.ts';

const HEALTH_PATTERNS: readonly string[] = [ROUTE_PATTERNS.healthLive, ROUTE_PATTERNS.healthReady];

export function setResponseContext(request: FastifyRequest, reply: FastifyReply): void {
  reply.header(HTTP_HEADERS.requestId, request.id);
  const path = request.url.split(QUERY_SEPARATOR)[0] ?? '';
  if (path === API_ROOT_PATH || path.startsWith(API_PATH_PREFIX)) {
    reply.header(HTTP_HEADERS.cacheControl, CACHE_CONTROL_NO_STORE);
  }
}

export function registerRequestContext(app: FastifyInstance, settings: Settings): void {
  app.addHook('onRequest', async (request) => {
    const exempt = HEALTH_PATTERNS.includes(request.routeOptions.url ?? '');
    if (settings.server.requireHttps && request.protocol !== HTTPS_SCHEME && !exempt) {
      throw new AppError('HTTPS_REQUIRED');
    }
  });
  app.addHook('onSend', async (request, reply) => setResponseContext(request, reply));
}
