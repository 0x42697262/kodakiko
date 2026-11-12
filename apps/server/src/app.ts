import Fastify, { type FastifyInstance } from 'fastify';
import helmet from '@fastify/helmet';
import type { Kysely } from 'kysely';
import { serializerCompiler, validatorCompiler } from 'fastify-type-provider-zod';
import type { Database } from './adapters/db/schema.ts';
import {
  API_CSP_DIRECTIVES,
  API_MAX_BODY_BYTES,
  CORP_POLICY,
  HSTS_MAX_AGE_SECONDS,
  REFERRER_POLICY,
  REQUEST_ID_BYTES,
} from './config/constants.ts';
import type { Settings } from './config/settings.ts';
import { registerErrorHandling, sendError } from './http/error-handler.ts';
import { loggerOptions } from './http/log-redaction.ts';
import { createRateLimitFor, registerRateLimit } from './http/rate-limits.ts';
import { registerRequestContext, setResponseContext } from './http/request-context.ts';
import { registerConfigRoutes } from './http/routes/config.ts';
import { registerHealthRoutes } from './http/routes/health.ts';
import type { Clock } from './platform/clock.ts';
import { randomToken } from './platform/ids.ts';
import type { ReadinessCheck } from './services/readiness.ts';
import { LOG_EVENTS } from './text/events.ts';
import { SECURITY_TEXT } from './text/settings.ts';

export type AppDeps = {
  readonly settings: Settings;
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly readinessChecks: readonly ReadinessCheck[];
};

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const { settings, readinessChecks } = deps;
  const app = Fastify({
    logger: loggerOptions(settings),
    trustProxy: [...settings.server.trustedProxies],
    bodyLimit: API_MAX_BODY_BYTES,
    genReqId: () => randomToken(REQUEST_ID_BYTES),
    requestIdHeader: false,
    frameworkErrors: (_error, request, reply) => {
      setResponseContext(request, reply);
      return sendError(reply, request.id, 'MALFORMED_REQUEST', null);
    },
  });
  if (!settings.server.rateLimitsEnabled) {
    app.log.warn(
      { event: LOG_EVENTS.securityRateLimitsDisabled },
      SECURITY_TEXT.rateLimitsDisabled,
    );
  }
  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);

  registerRequestContext(app, settings);
  await app.register(helmet, {
    contentSecurityPolicy: { useDefaults: false, directives: API_CSP_DIRECTIVES },
    hsts: { maxAge: HSTS_MAX_AGE_SECONDS, includeSubDomains: true, preload: true },
    referrerPolicy: { policy: REFERRER_POLICY },
    crossOriginResourcePolicy: { policy: CORP_POLICY },
    crossOriginOpenerPolicy: false,
    crossOriginEmbedderPolicy: false,
  });
  await registerRateLimit(app);
  registerErrorHandling(app);

  const rateLimitFor = createRateLimitFor(settings);
  registerHealthRoutes(app, { readinessChecks });
  registerConfigRoutes(app, { settings, rateLimitFor });
  return app;
}
