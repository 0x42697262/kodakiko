export const ARGON2_WORKER_ERROR = 'argon2_failed';

export type Argon2Request = {
  readonly id: number;
  readonly password: Uint8Array;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly outputLength: number;
};

export type Argon2Response =
  | { readonly id: number; readonly output: Uint8Array }
  | { readonly id: number; readonly error: typeof ARGON2_WORKER_ERROR };
