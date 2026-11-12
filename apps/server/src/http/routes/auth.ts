import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import {
  ROUTE_PATTERNS,
  keyBundleDto,
  paramsRequest,
  paramsResponse,
  passwordChangeRequest,
  passwordChangeResponse,
  recoveryCompleteRequest,
  recoveryStartRequest,
  recoveryStartResponse,
  sessionResponse,
  signInRequest,
  signInResponse,
  signUpRequest,
  signUpResponse,
} from '@kodakiko/contract';
import { authContextOf, type RequireSession } from '../auth-plugin.ts';
import type { AuthController } from '../controllers/auth-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

const STATUS_CREATED = 201;
const STATUS_NO_CONTENT = 204;

export type AuthRouteOptions = {
  readonly controller: AuthController;
  readonly rateLimitFor: RateLimitFor;
  readonly requireSession: RequireSession;
};

export function registerAuthRoutes(app: FastifyInstance, options: AuthRouteOptions): void {
  const { controller, rateLimitFor, requireSession } = options;
  const typed = app.withTypeProvider<ZodTypeProvider>();

  typed.post(
    ROUTE_PATTERNS.authParams,
    {
      config: { ...rateLimitFor('authPublic'), routeId: 'authParams', auth: 'none' },
      schema: { body: paramsRequest, response: { 200: paramsResponse } },
    },
    async (request) => controller.params(request.body),
  );

  typed.post(
    ROUTE_PATTERNS.authSignUp,
    {
      config: { ...rateLimitFor('authSignUp'), routeId: 'authSignUp', auth: 'none' },
      schema: { body: signUpRequest, response: { [STATUS_CREATED]: signUpResponse } },
    },
    async (request, reply) => {
      const result = await controller.signUp(request.body, request.ip);
      return reply.status(STATUS_CREATED).send(result);
    },
  );

  typed.post(
    ROUTE_PATTERNS.authSignIn,
    {
      config: { ...rateLimitFor('authSignIn'), routeId: 'authSignIn', auth: 'none' },
      schema: { body: signInRequest, response: { 200: signInResponse } },
    },
    async (request) => controller.signIn(request.body),
  );

  typed.get(
    ROUTE_PATTERNS.authKeyBundle,
    {
      config: { ...rateLimitFor('auth'), routeId: 'authKeyBundle', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { 200: keyBundleDto } },
    },
    async (request) => controller.keyBundle(authContextOf(request)),
  );

  typed.post(
    ROUTE_PATTERNS.authRecoveryStart,
    {
      config: { ...rateLimitFor('authPublic'), routeId: 'authRecoveryStart', auth: 'none' },
      schema: { body: recoveryStartRequest, response: { 200: recoveryStartResponse } },
    },
    async (request) => controller.recoveryStart(request.body),
  );

  typed.post(
    ROUTE_PATTERNS.authRecoveryComplete,
    {
      config: { ...rateLimitFor('authSignIn'), routeId: 'authRecoveryComplete', auth: 'none' },
      schema: { body: recoveryCompleteRequest, response: { 200: signInResponse } },
    },
    async (request) => controller.recoveryComplete(request.body),
  );

  typed.post(
    ROUTE_PATTERNS.authPassword,
    {
      config: { ...rateLimitFor('auth'), routeId: 'authPassword', auth: 'session' },
      preHandler: requireSession,
      schema: { body: passwordChangeRequest, response: { 200: passwordChangeResponse } },
    },
    async (request) => controller.changePassword(authContextOf(request), request.body),
  );

  typed.post(
    ROUTE_PATTERNS.authSignOut,
    {
      config: { ...rateLimitFor('auth'), routeId: 'authSignOut', auth: 'session' },
      preHandler: requireSession,
    },
    async (request, reply) => {
      await controller.signOut(authContextOf(request));
      return reply.status(STATUS_NO_CONTENT).send();
    },
  );

  typed.get(
    ROUTE_PATTERNS.authSession,
    {
      config: { ...rateLimitFor('auth'), routeId: 'authSession', auth: 'session' },
      preHandler: requireSession,
      schema: { response: { 200: sessionResponse } },
    },
    async (request) => controller.session(authContextOf(request)),
  );
}
