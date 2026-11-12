import type { FastifyError, FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { hasZodFastifySchemaValidationErrors } from 'fastify-type-provider-zod';
import { errorResponseSchema, type ErrorCode } from '@kodakiko/contract';
import { AppError } from '../domain/errors.ts';
import { ERROR_TEXT } from '../text/errors.ts';
import { LOG_EVENTS } from '../text/events.ts';

export const HTTP_STATUS_BY_CODE: Readonly<Record<ErrorCode, number>> = Object.freeze({
  MALFORMED_REQUEST: 400,
  HTTPS_REQUIRED: 400,
  UNAUTHENTICATED: 401,
  SESSION_EXPIRED: 401,
  INVALID_CREDENTIALS: 401,
  FORBIDDEN: 403,
  FEATURE_DISABLED: 403,
  QUOTA_EXCEEDED: 403,
  INVITE_INVALID: 403,
  LINK_TOKEN_INVALID: 403,
  LINK_EXPIRED: 403,
  LINK_VIEW_EXPIRED: 403,
  ACCOUNT_PENDING_DELETION: 403,
  NOT_FOUND: 404,
  REVISION_CONFLICT: 409,
  ID_CONFLICT: 409,
  USERNAME_TAKEN: 409,
  ALREADY_SHARED: 409,
  PAYLOAD_TOO_LARGE: 413,
  VALIDATION_FAILED: 422,
  KDF_PARAMS_TOO_WEAK: 422,
  UPLOAD_SIZE_MISMATCH: 422,
  UPLOAD_INCOMPLETE: 422,
  RATE_LIMITED: 429,
  INTERNAL: 500,
  STORAGE_UNAVAILABLE: 503,
});

const CODE_BY_FASTIFY_ERROR: Readonly<Record<string, ErrorCode>> = Object.freeze({
  FST_ERR_CTP_BODY_TOO_LARGE: 'PAYLOAD_TOO_LARGE',
  FST_ERR_CTP_INVALID_JSON_BODY: 'MALFORMED_REQUEST',
  FST_ERR_CTP_EMPTY_JSON_BODY: 'MALFORMED_REQUEST',
  FST_ERR_CTP_INVALID_MEDIA_TYPE: 'MALFORMED_REQUEST',
  FST_ERR_CTP_INVALID_CONTENT_LENGTH: 'MALFORMED_REQUEST',
});

const PATH_SEPARATOR = '/';

type ErrorOutcome = { readonly code: ErrorCode; readonly details: unknown };

function issuesOf(error: FastifyError): { path: string[]; message: string }[] {
  return (error.validation ?? []).map((issue) => ({
    path: issue.instancePath.split(PATH_SEPARATOR).filter((segment) => segment !== ''),
    message: issue.message ?? '',
  }));
}

function hasValidation(error: unknown): error is FastifyError {
  return (
    typeof error === 'object' && error !== null && (error as FastifyError).validation !== undefined
  );
}

function outcomeOf(error: unknown): ErrorOutcome {
  if (error instanceof AppError) return { code: error.code, details: error.details };
  if (hasZodFastifySchemaValidationErrors(error) || hasValidation(error)) {
    return { code: 'VALIDATION_FAILED', details: { issues: issuesOf(error as FastifyError) } };
  }
  const fastifyCode = (error as FastifyError).code;
  const mapped = fastifyCode === undefined ? undefined : CODE_BY_FASTIFY_ERROR[fastifyCode];
  if (mapped !== undefined) return { code: mapped, details: null };
  return { code: 'INTERNAL', details: null };
}

export function sendError(
  reply: FastifyReply,
  requestId: string,
  code: ErrorCode,
  details: unknown,
): FastifyReply {
  const body = errorResponseSchema.parse({
    error: { code, message: ERROR_TEXT[code], requestId, details },
  });
  return reply.status(HTTP_STATUS_BY_CODE[code]).send(body);
}

export function registerErrorHandling(app: FastifyInstance): void {
  app.setErrorHandler((error: unknown, request: FastifyRequest, reply: FastifyReply) => {
    const outcome = outcomeOf(error);
    if (outcome.code === 'INTERNAL') {
      request.log.error({ event: LOG_EVENTS.httpUnhandledError, err: error });
    }
    return sendError(reply, request.id, outcome.code, outcome.details);
  });
  app.setNotFoundHandler((request, reply) => sendError(reply, request.id, 'NOT_FOUND', null));
}
