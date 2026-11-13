import { bytesSchema, decodeBytes, encodeBytes } from '@kodakiko/contract';

export const LINK_SECRET_BYTES = 32;
export const LINK_PATH_PREFIX = '/s/';
const FRAGMENT_MARK = '#';
const TRAILING_SLASHES = /\/+$/;

export function buildLinkUrl(baseUrl: string, linkId: string, secret: Uint8Array): string {
  return `${baseUrl.replace(TRAILING_SLASHES, '')}${LINK_PATH_PREFIX}${linkId}${FRAGMENT_MARK}${encodeBytes(secret)}`;
}

export function parseLinkFragment(hash: string): Uint8Array | null {
  const raw = hash.startsWith(FRAGMENT_MARK) ? hash.slice(FRAGMENT_MARK.length) : hash;
  const parsed = bytesSchema.safeParse(raw);
  if (!parsed.success) return null;
  try {
    const bytes = decodeBytes(parsed.data);
    return bytes.length === LINK_SECRET_BYTES ? bytes : null;
  } catch {
    return null;
  }
}
