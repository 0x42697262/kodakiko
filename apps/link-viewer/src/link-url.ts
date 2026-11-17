import { ID_REGEX, bytesSchema, decodeBytes } from '@kodakiko/contract';
import { LINK_SECRET_BYTES } from './constants.ts';
import { FRAGMENT_MARK, LINK_PATH_PREFIX, TRAILING_SLASH } from './keys.ts';

export { LINK_PATH_PREFIX, LINK_SECRET_BYTES };

export type LinkLocation =
  | { readonly ok: true; readonly linkId: string; readonly secret: Uint8Array }
  | { readonly ok: false; readonly failure: 'incomplete' | 'invalid' };

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

export function parseLinkPath(pathname: string): string | null {
  if (!pathname.startsWith(LINK_PATH_PREFIX)) return null;
  const linkId = pathname.slice(LINK_PATH_PREFIX.length).replace(TRAILING_SLASH, '');
  return ID_REGEX.test(linkId) ? linkId : null;
}

export function parseLinkLocation(pathname: string, hash: string): LinkLocation {
  const linkId = parseLinkPath(pathname);
  if (linkId === null) return { ok: false, failure: 'invalid' };
  const secret = parseLinkFragment(hash);
  if (secret === null) return { ok: false, failure: 'incomplete' };
  return { ok: true, linkId, secret };
}
