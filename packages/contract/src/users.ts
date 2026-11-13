import { z } from 'zod';
import { bytesSchema, idSchema } from './ids.ts';

export { userDto, type UserDto } from './auth.ts';

export const deletionRequestResponse = z.object({ deletionDueAt: z.iso.datetime() });

export type DeletionRequestResponse = z.infer<typeof deletionRequestResponse>;

export const userPublicKeyParams = z.object({ username: z.string().min(1) });

export const publicKeyResponse = z.object({ userId: idSchema, username: z.string(), publicKey: bytesSchema });

export type PublicKeyResponse = z.infer<typeof publicKeyResponse>;
