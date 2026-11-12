import type { ErrorCode } from '@kodakiko/contract';

export class AppError extends Error {
  readonly code: ErrorCode;
  readonly details: unknown;

  constructor(code: ErrorCode, details: unknown = null) {
    super(code);
    this.code = code;
    this.details = details;
  }
}

export class SettingsError extends Error {}
