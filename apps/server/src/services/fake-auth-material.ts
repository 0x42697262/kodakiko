import { encodeBytes } from '@kodakiko/contract';
import {
  FAKE_PARAMS_SALT_BYTES,
  FAKE_RECOVERY_ID_BYTES,
  FAKE_RECOVERY_WRAP_BYTES,
  FAKE_RECOVERY_WRAP_VERSION,
  HMAC_LABELS,
} from '../config/constants.ts';
import { hmacSha256 } from '../platform/hash.ts';

export function fakeKdfSalt(secret: Uint8Array, username: string): Buffer {
  return hmacSha256(secret, HMAC_LABELS.paramsSalt, username).subarray(0, FAKE_PARAMS_SALT_BYTES);
}

export function fakeRecoveryWrap(secret: Uint8Array, username: string): Buffer {
  const body = Buffer.concat([
    hmacSha256(secret, HMAC_LABELS.recoveryFake1, username),
    hmacSha256(secret, HMAC_LABELS.recoveryFake2, username),
    hmacSha256(secret, HMAC_LABELS.recoveryFake3, username),
  ]);
  return Buffer.concat([Buffer.of(FAKE_RECOVERY_WRAP_VERSION), body]).subarray(
    0,
    FAKE_RECOVERY_WRAP_BYTES,
  );
}

export function fakeUserId(secret: Uint8Array, username: string): string {
  return encodeBytes(
    hmacSha256(secret, HMAC_LABELS.recoveryFakeId, username).subarray(0, FAKE_RECOVERY_ID_BYTES),
  );
}
