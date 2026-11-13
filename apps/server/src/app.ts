import { ROUTES } from '@kodakiko/contract';
import Fastify, { type FastifyInstance } from 'fastify';
import helmet from '@fastify/helmet';
import type { Kysely } from 'kysely';
import { serializerCompiler, validatorCompiler } from 'fastify-type-provider-zod';
import type { Database } from './adapters/db/schema.ts';
import {
  API_CSP_DIRECTIVES,
  FAILED_SIGN_IN_TRACKER_MAX_ENTRIES,
  MINUTE_MS,
  API_MAX_BODY_BYTES,
  CORP_POLICY,
  HSTS_MAX_AGE_SECONDS,
  REFERRER_POLICY,
  REQUEST_ID_BYTES,
} from './config/constants.ts';
import type { Settings } from './config/settings.ts';
import { registerErrorHandling, sendError } from './http/error-handler.ts';
import { registerAuthPlugin } from './http/auth-plugin.ts';
import { createAuthController } from './http/controllers/auth-controller.ts';
import { createCollectionsController } from './http/controllers/collections-controller.ts';
import { createEntriesController } from './http/controllers/entries-controller.ts';
import { createObjectsController } from './http/controllers/objects-controller.ts';
import { createUsersController } from './http/controllers/users-controller.ts';
import { loggerOptions, type LogStream } from './http/log-redaction.ts';
import {
  createOpenSignUpRateCheck,
  createRateLimitFor,
  registerRateLimit,
} from './http/rate-limits.ts';
import { registerRequestContext, setResponseContext } from './http/request-context.ts';
import { registerAuthRoutes } from './http/routes/auth.ts';
import { registerCollectionsRoutes } from './http/routes/collections.ts';
import { registerSharingRoutes } from './http/routes/sharing.ts';
import { registerAdminRoutes } from './http/routes/admin.ts';
import { createAdminService } from './services/admin-service.ts';
import { registerPublicLinksRoutes } from './http/routes/public-links.ts';
import { createPublicLinksController } from './http/controllers/public-links-controller.ts';
import { createPublicLinksService } from './services/public-links-service.ts';
import { createSharingController } from './http/controllers/sharing-controller.ts';
import { createSharingService } from './services/sharing-service.ts';
import { registerEntriesRoutes } from './http/routes/entries.ts';
import { registerMediaRoutes } from './http/routes/media.ts';
import { registerSyncRoutes } from './http/routes/sync.ts';
import { registerConfigRoutes } from './http/routes/config.ts';
import { registerHealthRoutes } from './http/routes/health.ts';
import { registerObjectsRoutes } from './http/routes/objects.ts';
import { registerUsersRoutes } from './http/routes/users.ts';
import { createFailedSignInTracker } from './adapters/memory/failed-sign-in-tracker.ts';
import type { ObjectStore } from './adapters/storage/object-store.ts';
import type { Clock } from './platform/clock.ts';
import { randomToken } from './platform/ids.ts';
import { objectsRepository } from './adapters/db/objects-repository.ts';
import { createAuthService } from './services/auth-service.ts';
import { createCollectionsService } from './services/collections-service.ts';
import { createEntriesService } from './services/entries-service.ts';
import { createSequenceService } from './services/sequence-service.ts';
import { createSyncService } from './services/sync-service.ts';
import { createObjectsService } from './services/objects-service.ts';
import type { ReadinessCheck } from './services/readiness.ts';
import { LOG_EVENTS } from './text/events.ts';
import { SECURITY_TEXT } from './text/settings.ts';

export type AppDeps = {
  readonly settings: Settings;
  readonly db: Kysely<Database>;
  readonly clock: Clock;
  readonly readinessChecks: readonly ReadinessCheck[];
  readonly objectStore: ObjectStore;
  readonly logStream?: LogStream;
};

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const { settings, db, clock, readinessChecks, objectStore } = deps;
  const app = Fastify({
    logger: loggerOptions(settings, deps.logStream),
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

  const authService = createAuthService({
    db,
    settings,
    clock,
    failedSignIns: createFailedSignInTracker({
      windowMs: settings.auth.failedSignInWindowMinutes * MINUTE_MS,
      maxEntries: FAILED_SIGN_IN_TRACKER_MAX_ENTRIES,
    }),
    logger: app.log,
    checkOpenSignUpRate: createOpenSignUpRateCheck(app, settings),
  });
  const requireSession = registerAuthPlugin(app, { authService, clock });
  registerAuthRoutes(app, {
    controller: createAuthController(authService),
    rateLimitFor,
    requireSession,
  });
  registerUsersRoutes(app, {
    controller: createUsersController(authService),
    rateLimitFor,
    requireSession,
  });
  registerAdminRoutes(app, {
    service: createAdminService({ db, clock, logger: app.log, createInvite: authService.createInvite }),
    rateLimitFor,
    requireSession,
  });
  const entriesService = createEntriesService({
    db,
    settings,
    clock,
    objectsRepository,
    sequenceService: createSequenceService(),
    logger: app.log,
  });
  const objectsService = createObjectsService({
    db,
    store: objectStore,
    settings,
    clock,
    onObjectUploaded: entriesService.onObjectUploaded,
    logger: app.log,
  });
  registerObjectsRoutes(app, {
    controller: createObjectsController(objectsService),
    rateLimitFor,
    requireSession,
  });
  const entriesController = createEntriesController(entriesService);
  registerEntriesRoutes(app, { controller: entriesController, rateLimitFor, requireSession });
  registerMediaRoutes(app, { controller: entriesController, rateLimitFor, requireSession });
  const collectionsController = createCollectionsController(
    createCollectionsService({ db, settings, clock }),
    createSyncService({ db, settings }),
  );
  registerCollectionsRoutes(app, { controller: collectionsController, rateLimitFor, requireSession });
  registerSyncRoutes(app, { controller: collectionsController, rateLimitFor, requireSession });
  const publicLinksService = createPublicLinksService({
    db,
    settings,
    clock,
    store: objectStore,
    logger: app.log,
    proxyPath: (linkId, objectId) => ROUTES.publicLinkObjectContent(linkId, objectId),
  });
  registerPublicLinksRoutes(app, {
    controller: createPublicLinksController(publicLinksService),
    rateLimitFor,
    requireSession,
  });
  registerSharingRoutes(app, {
    controller: createSharingController(
      createSharingService({
        db,
        settings,
        clock,
        logger: app.log,
        deleteLinkCollection: publicLinksService.deleteOwnedLinkCollection,
      }),
    ),
    rateLimitFor,
    requireSession,
  });
  return app;
}
