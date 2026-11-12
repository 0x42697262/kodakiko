import {
  encodeBytes,
  idSchema,
  type KeyBundleDto,
  type ParamsResponse,
  type SessionDto,
  type UserDto,
} from '@kodakiko/contract';
import type {
  KdfNumbers,
  KeyBundleView,
  SessionView,
  UserView,
} from '../../services/auth-service.ts';

export function toParamsResponse(numbers: KdfNumbers): ParamsResponse {
  return {
    salt: encodeBytes(numbers.salt),
    opsLimit: numbers.opsLimit,
    memLimitBytes: numbers.memLimitBytes,
  };
}

export function toUserDto(user: UserView): UserDto {
  return {
    id: idSchema.parse(user.id),
    username: user.username,
    role: user.role,
    status: user.status,
    quotaBytes: user.quotaBytes,
    usedBytes: user.usedBytes,
    createdAt: user.createdAt.toISOString(),
    deletionDueAt: user.deletionDueAt?.toISOString() ?? null,
  };
}

export function toSessionDto(session: SessionView): SessionDto {
  return {
    token: session.token,
    expiresAt: session.expiresAt.toISOString(),
    deviceLabel: session.deviceLabel,
  };
}

export function toKeyBundleDto(bundle: KeyBundleView): KeyBundleDto {
  return {
    ...toParamsResponse(bundle),
    masterKeyByKek: encodeBytes(bundle.masterKeyByKek),
    recoveryKeyByMaster: encodeBytes(bundle.recoveryKeyByMaster),
    publicKey: encodeBytes(bundle.publicKey),
    privateKeyByMaster: encodeBytes(bundle.privateKeyByMaster),
  };
}
