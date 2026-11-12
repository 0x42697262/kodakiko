import { z } from 'zod';
import { USER_ROLES, USER_STATUSES } from './enums.ts';
import { bytesSchema, idSchema } from './ids.ts';
import {
  DEVICE_LABEL_MAX_CHARS,
  INVITE_TOKEN_REGEX,
  KDF_OPS_LIMIT_MAX,
  SESSION_TOKEN_REGEX,
  USERNAME_INPUT_MAX,
  USERNAME_PATTERN,
} from './limits.ts';

const signUpUsername = z.string().regex(new RegExp(USERNAME_PATTERN));
const usernameInput = z.string().min(1).max(USERNAME_INPUT_MAX);
const deviceLabel = z.string().min(1).max(DEVICE_LABEL_MAX_CHARS);
const opsLimit = z.number().int().min(1).max(KDF_OPS_LIMIT_MAX);
const memLimitBytes = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER);
const isoTime = z.iso.datetime();

export const paramsRequest = z.object({ username: usernameInput });

export const paramsResponse = z.object({ salt: bytesSchema, opsLimit, memLimitBytes });

export const signUpRequest = z.object({
  userId: idSchema,
  username: signUpUsername,
  inviteToken: z.string().regex(INVITE_TOKEN_REGEX).nullable(),
  deviceLabel,
  salt: bytesSchema,
  opsLimit,
  memLimitBytes,
  loginKey: bytesSchema,
  recoveryLoginKey: bytesSchema,
  publicKey: bytesSchema,
  masterKeyByKek: bytesSchema,
  masterKeyByRecovery: bytesSchema,
  recoveryKeyByMaster: bytesSchema,
  privateKeyByMaster: bytesSchema,
  homeCollection: z.object({
    id: idSchema,
    keyByOwner: bytesSchema,
    metadataBlob: bytesSchema,
  }),
});

export const signInRequest = z.object({
  username: usernameInput,
  loginKey: bytesSchema,
  deviceLabel,
});

export const recoveryStartRequest = z.object({ username: usernameInput });

export const recoveryStartResponse = z.object({
  userId: idSchema,
  masterKeyByRecovery: bytesSchema,
});

export const recoveryCompleteRequest = z.object({
  username: usernameInput,
  recoveryLoginKey: bytesSchema,
  deviceLabel,
  salt: bytesSchema,
  opsLimit,
  memLimitBytes,
  loginKey: bytesSchema,
  masterKeyByKek: bytesSchema,
});

export const passwordChangeRequest = z.object({
  currentLoginKey: bytesSchema,
  salt: bytesSchema,
  opsLimit,
  memLimitBytes,
  loginKey: bytesSchema,
  masterKeyByKek: bytesSchema,
});

export const sessionDto = z.object({
  token: z.string().regex(SESSION_TOKEN_REGEX),
  expiresAt: isoTime,
  deviceLabel,
});

export const userDto = z.object({
  id: idSchema,
  username: z.string(),
  role: z.enum(USER_ROLES),
  status: z.enum(USER_STATUSES),
  quotaBytes: z.number().int().nonnegative().nullable(),
  usedBytes: z.number().int().nonnegative(),
  createdAt: isoTime,
  deletionDueAt: isoTime.nullable(),
});

export const keyBundleDto = z.object({
  salt: bytesSchema,
  opsLimit,
  memLimitBytes,
  masterKeyByKek: bytesSchema,
  recoveryKeyByMaster: bytesSchema,
  publicKey: bytesSchema,
  privateKeyByMaster: bytesSchema,
});

export const signUpResponse = z.object({ session: sessionDto, user: userDto });

export const signInResponse = z.object({
  session: sessionDto,
  user: userDto,
  keyBundle: keyBundleDto,
});

export const sessionResponse = z.object({ user: userDto });

export const passwordChangeResponse = z.object({ session: sessionDto });

export type ParamsRequest = z.infer<typeof paramsRequest>;
export type ParamsResponse = z.infer<typeof paramsResponse>;
export type SignUpRequest = z.infer<typeof signUpRequest>;
export type SignInRequest = z.infer<typeof signInRequest>;
export type RecoveryStartRequest = z.infer<typeof recoveryStartRequest>;
export type RecoveryStartResponse = z.infer<typeof recoveryStartResponse>;
export type RecoveryCompleteRequest = z.infer<typeof recoveryCompleteRequest>;
export type PasswordChangeRequest = z.infer<typeof passwordChangeRequest>;
export type SessionDto = z.infer<typeof sessionDto>;
export type UserDto = z.infer<typeof userDto>;
export type KeyBundleDto = z.infer<typeof keyBundleDto>;
export type SignUpResponse = z.infer<typeof signUpResponse>;
export type SignInResponse = z.infer<typeof signInResponse>;
