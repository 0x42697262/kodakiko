import { ID_REGEX, bytesSchema, decodeBytes, encodeBytes } from '@kodakiko/contract';

export function encodeCursor(lastId: string): string {
  return encodeBytes(new TextEncoder().encode(lastId));
}

export function decodeCursor(cursor: string): string | null {
  const parsed = bytesSchema.safeParse(cursor);
  if (!parsed.success) return null;
  const id = new TextDecoder().decode(decodeBytes(parsed.data));
  return ID_REGEX.test(id) ? id : null;
}

export function clampLimit(requested: number | undefined, fallback: number, max: number): number {
  return Math.min(requested ?? fallback, max);
}

export function decodeTextCursor(cursor: string): string | null {
  const parsed = bytesSchema.safeParse(cursor);
  if (!parsed.success) return null;
  const text = new TextDecoder().decode(decodeBytes(parsed.data));
  return text.length > 0 ? text : null;
}
