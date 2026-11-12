import type { PushFailure } from '@kodakiko/core';
import { ApiError, NetworkError } from '../../adapters/http/http';

export function toPushFailure(error: unknown): PushFailure | null {
  if (error instanceof NetworkError) return { kind: 'network' };
  if (error instanceof ApiError) {
    return {
      kind: 'http',
      status: error.status,
      code: error.code,
      retryAfterSeconds: error.retryAfterSeconds,
    };
  }
  return null;
}
