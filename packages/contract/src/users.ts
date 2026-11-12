import { z } from 'zod';

export { userDto, type UserDto } from './auth.ts';

export const deletionRequestResponse = z.object({ deletionDueAt: z.iso.datetime() });

export type DeletionRequestResponse = z.infer<typeof deletionRequestResponse>;
