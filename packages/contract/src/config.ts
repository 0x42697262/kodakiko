import { z } from 'zod';

export const SIGNUP_MODES = ['invite', 'open'] as const;

export const publicConfigSchema = z.object({
  apiVersion: z.literal(1),
  signupMode: z.enum(SIGNUP_MODES),
  sharingEnabled: z.boolean(),
  publicLinksEnabled: z.boolean(),
  maxMediaPerEntry: z.number().int().positive(),
  maxUploadBytes: z.number().int().positive(),
  defaultQuotaBytes: z.number().int().nonnegative(),
  trashDays: z.number().int().positive(),
  accountDeletionGraceDays: z.number().int().positive(),
  serverVersion: z.string(),
});

export type PublicConfigDto = z.infer<typeof publicConfigSchema>;
