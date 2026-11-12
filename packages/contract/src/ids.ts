import { z } from 'zod';
import { isDecodableBase64Url } from './bytes.ts';

export const ID_PATTERN = '^[A-Za-z0-9_-]{22}$';
export const ID_REGEX = new RegExp(ID_PATTERN);
export const idSchema = z.string().regex(ID_REGEX).brand<'Id'>();
export type Id = z.infer<typeof idSchema>;

export const BASE64URL_REGEX = /^[A-Za-z0-9_-]*$/;
export const bytesSchema = z
  .string()
  .min(1)
  .regex(BASE64URL_REGEX)
  .refine(isDecodableBase64Url)
  .brand<'Bytes'>();
export type Bytes = z.infer<typeof bytesSchema>;
