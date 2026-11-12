import { INVITE_TOKEN_REGEX } from '@kodakiko/contract';
import { INVITE_QUERY_HASH_PREFIX } from '../../keys/auth-keys';

export function extractInviteToken(input: string): string | null {
  const trimmed = input.trim();
  if (INVITE_TOKEN_REGEX.test(trimmed)) return trimmed;
  try {
    const { hash } = new URL(trimmed);
    const token = hash.startsWith(INVITE_QUERY_HASH_PREFIX)
      ? hash.slice(INVITE_QUERY_HASH_PREFIX.length)
      : '';
    return INVITE_TOKEN_REGEX.test(token) ? token : null;
  } catch {
    return null;
  }
}
