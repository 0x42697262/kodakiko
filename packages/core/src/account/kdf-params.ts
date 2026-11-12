import {
  ARGON2ID_MIN_MEM_BYTES,
  ARGON2ID_MIN_WORK,
  ARGON2ID_START_MEM_BYTES,
  ARGON2ID_START_OPS,
} from '../crypto/constants.ts';

const ADAPTIVE_FACTOR = 2;

export type KdfParams = {
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
};

export function startKdfParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: ARGON2ID_START_OPS, memLimitBytes: ARGON2ID_START_MEM_BYTES };
}

export function nextKdfParamsAfterFailure(params: KdfParams): KdfParams | null {
  const memLimitBytes = params.memLimitBytes / ADAPTIVE_FACTOR;
  if (memLimitBytes < ARGON2ID_MIN_MEM_BYTES) return null;
  return { salt: params.salt, opsLimit: params.opsLimit * ADAPTIVE_FACTOR, memLimitBytes };
}

export function meetsKdfFloor(params: Pick<KdfParams, 'opsLimit' | 'memLimitBytes'>): boolean {
  return (
    params.memLimitBytes >= ARGON2ID_MIN_MEM_BYTES &&
    params.memLimitBytes * params.opsLimit >= ARGON2ID_MIN_WORK
  );
}
