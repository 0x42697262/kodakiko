import { createHash } from 'node:crypto';
import type { FastifyInstance, FastifyRequest, RouteOptions } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { SESSION_TOKEN_REGEX } from '@kodakiko/contract';
import {
  BEARER_PREFIX,
  HTTP_HEADERS,
  RATE_LIMIT_HEADERS_OVER_LIMIT,
  RATE_LIMIT_HEADERS_UNDER_LIMIT,
  RATE_LIMIT_IP_KEY_PREFIX,
  RATE_LIMIT_IP_KEYED_GROUPS,
  RATE_LIMIT_SESSION_HASH_HEX_CHARS,
  RATE_LIMIT_SESSION_KEY_PREFIX,
  RATE_LIMITS,
  type RateLimitGroup,
} from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import { AppError } from '../domain/errors.ts';

export type RateLimitFor = (group: RateLimitGroup) => NonNullable<RouteOptions['config']>;

export function ipRateLimitKey(request: FastifyRequest): string {
  return `${RATE_LIMIT_IP_KEY_PREFIX}${request.ip}`;
}

export function rateLimitKey(request: FastifyRequest): string {
  const authorization = request.headers[HTTP_HEADERS.authorization];
  if (typeof authorization === 'string' && authorization.startsWith(BEARER_PREFIX)) {
    const token = authorization.slice(BEARER_PREFIX.length);
    if (SESSION_TOKEN_REGEX.test(token)) {
      const digest = createHash('sha256').update(token).digest('hex');
      return `${RATE_LIMIT_SESSION_KEY_PREFIX}${digest.slice(0, RATE_LIMIT_SESSION_HASH_HEX_CHARS)}`;
    }
  }
  return ipRateLimitKey(request);
}

export function createRateLimitFor(settings: Settings): RateLimitFor {
  return (group) => {
    if (!settings.server.rateLimitsEnabled) return { rateLimit: false };
    const { max, timeWindowMs } = RATE_LIMITS[group];
    const timeWindow = timeWindowMs;
    if (RATE_LIMIT_IP_KEYED_GROUPS.includes(group)) {
      return { rateLimit: { max, timeWindow, keyGenerator: ipRateLimitKey } };
    }
    return { rateLimit: { max, timeWindow } };
  };
}

export async function registerRateLimit(app: FastifyInstance): Promise<void> {
  await app.register(rateLimit, {
    global: false,
    keyGenerator: rateLimitKey,
    errorResponseBuilder: () => {
      throw new AppError('RATE_LIMITED');
    },
    addHeadersOnExceeding: RATE_LIMIT_HEADERS_UNDER_LIMIT,
    addHeaders: RATE_LIMIT_HEADERS_OVER_LIMIT,
  });
}
