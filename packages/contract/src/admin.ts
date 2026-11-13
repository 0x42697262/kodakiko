import { z } from 'zod';
import { userDto } from './auth.ts';
import { USER_ROLES } from './enums.ts';
import { idSchema } from './ids.ts';
import { INVITE_MAX_EXPIRY_DAYS } from './limits.ts';

const isoTime = z.iso.datetime();
const count = z.number().int().nonnegative();

export const ADMIN_REFUSAL_REASONS = ['selfDemotion', 'lastAdmin'] as const;

export const adminUserDto = userDto.extend({ sessionCount: count, lastActiveAt: isoTime.nullable() });

export const adminUserListResponse = z.object({ items: z.array(adminUserDto), nextCursor: z.string().nullable() });

export const adminUserListQuery = z.object({
  cursor: z.string().min(1).optional(),
  limit: z.coerce.number().int().min(1).optional(),
});

export const adminUserPatchRequest = z
  .object({ quotaBytes: count.nullable().optional(), role: z.enum(USER_ROLES).optional() })
  .refine((value) => value.quotaBytes !== undefined || value.role !== undefined);

export const adminInviteCreateRequest = z.object({
  role: z.enum(USER_ROLES),
  expiresInDays: z.number().int().min(1).max(INVITE_MAX_EXPIRY_DAYS),
});

export const adminInviteCreateResponse = z.object({ inviteUrl: z.string(), expiresAt: isoTime });

export const inviteDto = z.object({
  id: idSchema,
  role: z.enum(USER_ROLES),
  createdAt: isoTime,
  expiresAt: isoTime,
  redeemedAt: isoTime.nullable(),
  revokedAt: isoTime.nullable(),
  redeemedByUsername: z.string().nullable(),
});

export const inviteListResponse = z.object({ items: z.array(inviteDto) });

export const adminIdParams = z.object({ id: idSchema });

export const adminStatsResponse = z.object({
  users: count,
  activeUsers30d: count,
  pendingDeletion: count,
  collections: count,
  entries: count,
  objects: count,
  totalBytes: count,
  quotaBytesAllocated: count.nullable(),
});

export type AdminUserDto = z.infer<typeof adminUserDto>;
export type AdminUserListResponse = z.infer<typeof adminUserListResponse>;
export type AdminUserPatchRequest = z.infer<typeof adminUserPatchRequest>;
export type AdminInviteCreateRequest = z.infer<typeof adminInviteCreateRequest>;
export type AdminInviteCreateResponse = z.infer<typeof adminInviteCreateResponse>;
export type InviteDto = z.infer<typeof inviteDto>;
export type InviteListResponse = z.infer<typeof inviteListResponse>;
export type AdminStatsResponse = z.infer<typeof adminStatsResponse>;
