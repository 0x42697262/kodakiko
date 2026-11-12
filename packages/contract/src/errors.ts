import { z } from 'zod';

export const ERROR_CODES = Object.freeze([
  'MALFORMED_REQUEST',
  'HTTPS_REQUIRED',
  'UNAUTHENTICATED',
  'SESSION_EXPIRED',
  'INVALID_CREDENTIALS',
  'FORBIDDEN',
  'FEATURE_DISABLED',
  'QUOTA_EXCEEDED',
  'INVITE_INVALID',
  'LINK_TOKEN_INVALID',
  'LINK_EXPIRED',
  'LINK_VIEW_EXPIRED',
  'ACCOUNT_PENDING_DELETION',
  'NOT_FOUND',
  'REVISION_CONFLICT',
  'ID_CONFLICT',
  'USERNAME_TAKEN',
  'ALREADY_SHARED',
  'PAYLOAD_TOO_LARGE',
  'VALIDATION_FAILED',
  'KDF_PARAMS_TOO_WEAK',
  'UPLOAD_SIZE_MISMATCH',
  'UPLOAD_INCOMPLETE',
  'RATE_LIMITED',
  'INTERNAL',
  'STORAGE_UNAVAILABLE',
] as const);

export type ErrorCode = (typeof ERROR_CODES)[number];

export const errorResponseSchema = z.object({
  error: z.object({
    code: z.enum(ERROR_CODES),
    message: z.string(),
    requestId: z.string(),
    details: z.unknown().nullable(),
  }),
});
