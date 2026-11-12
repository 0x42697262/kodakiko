import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { HTTP_HEADERS } from '../config/constants.ts';
import { routeAllowedWhilePendingDeletion } from '../domain/auth-rules.ts';
import { AppError } from '../domain/errors.ts';
import type { Clock } from '../platform/clock.ts';
import type { AuthContext, AuthService } from '../services/auth-service.ts';

export type RouteAuthMode = 'session' | 'none';

declare module 'fastify' {
  interface FastifyRequest {
    auth: AuthContext | null;
  }
  interface FastifyContextConfig {
    routeId?: string;
    auth?: RouteAuthMode;
  }
}

export type RequireSession = (request: FastifyRequest, reply: FastifyReply) => Promise<void>;

export function registerAuthPlugin(
  app: FastifyInstance,
  deps: { readonly authService: AuthService; readonly clock: Clock },
): RequireSession {
  app.decorateRequest('auth', null);
  return async function requireSession(request) {
    const context = await deps.authService.authenticate(
      request.headers[HTTP_HEADERS.authorization],
      deps.clock.now(),
    );
    request.auth = context;
    const routeId = request.routeOptions.config.routeId ?? '';
    if (context.status === 'pending_deletion' && !routeAllowedWhilePendingDeletion(routeId)) {
      throw new AppError('ACCOUNT_PENDING_DELETION');
    }
  };
}

export function authContextOf(request: FastifyRequest): AuthContext {
  if (request.auth === null) throw new AppError('UNAUTHENTICATED');
  return request.auth;
}
