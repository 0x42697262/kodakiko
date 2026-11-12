import { FIRST_WORD_POSITION } from '../constants/auth-constants';
import { ApiError, NetworkError } from '../adapters/http/http';
import {
  HostileServerParamsError,
  InvalidRecoveryWordsError,
  KdfUnavailableError,
  WrongPasswordError,
  WrongRecoveryWordsError,
  type PasswordRule,
} from '@kodakiko/core';

export type AuthFailure =
  | 'wrongPassword'
  | 'invalidCredentials'
  | 'unreachable'
  | 'notHttps'
  | 'notKodakiko'
  | 'inviteInvalid'
  | 'usernameTaken'
  | 'usernameInvalid'
  | 'unsafeServer'
  | 'kdfUnavailable'
  | 'recoveryWordsInvalid'
  | 'rateLimited'
  | 'passwordPolicy'
  | 'generic';

export type AuthErrorDetails = {
  readonly position?: number | null;
  readonly rules?: readonly PasswordRule[];
};

export class AuthError extends Error {
  readonly reason: AuthFailure;
  readonly position: number | null;
  readonly rules: readonly PasswordRule[];

  constructor(reason: AuthFailure, details: AuthErrorDetails = {}) {
    super(reason);
    this.reason = reason;
    this.position = details.position ?? null;
    this.rules = details.rules ?? [];
  }
}

const REASON_BY_API_CODE: Readonly<Record<string, AuthFailure>> = Object.freeze({
  INVALID_CREDENTIALS: 'invalidCredentials',
  INVITE_INVALID: 'inviteInvalid',
  USERNAME_TAKEN: 'usernameTaken',
  RATE_LIMITED: 'rateLimited',
});

export function toAuthError(error: unknown): AuthError {
  if (error instanceof AuthError) return error;
  if (error instanceof NetworkError) return new AuthError('unreachable');
  if (error instanceof ApiError) return new AuthError(REASON_BY_API_CODE[error.code] ?? 'generic');
  if (error instanceof HostileServerParamsError) return new AuthError('unsafeServer');
  if (error instanceof KdfUnavailableError) return new AuthError('kdfUnavailable');
  if (error instanceof WrongPasswordError) return new AuthError('wrongPassword');
  if (error instanceof InvalidRecoveryWordsError) {
    const position = error.wordPosition === null ? null : error.wordPosition + FIRST_WORD_POSITION;
    return new AuthError('recoveryWordsInvalid', { position });
  }
  if (error instanceof WrongRecoveryWordsError) return new AuthError('recoveryWordsInvalid');
  throw error;
}
