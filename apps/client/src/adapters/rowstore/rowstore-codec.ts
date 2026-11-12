import { utf8Decode, utf8Encode } from '@kodakiko/core';
import type { z } from 'zod';
import { OUTBOX_STATES } from './schema';
import type { OutboxState } from './rowstore';

export function encodeJson(value: unknown): Uint8Array {
  return utf8Encode(JSON.stringify(value));
}

export function decodeJson<T>(bytes: Uint8Array, schema: z.ZodType<T>): T {
  return schema.parse(JSON.parse(utf8Decode(bytes)));
}

export function emptyOutboxCounts(): Record<OutboxState, number> {
  return Object.fromEntries(OUTBOX_STATES.map((state) => [state, 0])) as Record<
    OutboxState,
    number
  >;
}
