import { decodeBytes, encodeBytes } from '@kodakiko/contract';
import {
  HMAC_LABELS,
  LINK_VIEW_TOKEN_SECONDS_BYTES,
  LINK_VIEW_TOKEN_SEPARATOR,
  MINUTE_MS,
  SECOND_MS,
} from '../config/constants.ts';
import { constantTimeEqual, hmacSha256 } from '../platform/hash.ts';

const VIEW_TOKEN_PARTS = 2;

function signature(secret: Uint8Array, linkId: string, expiresAtSeconds: number): Buffer {
  return hmacSha256(secret, HMAC_LABELS.linkView, `${linkId}${LINK_VIEW_TOKEN_SEPARATOR}${expiresAtSeconds}`);
}

export function createViewToken(secret: Uint8Array, linkId: string, now: Date, minutes: number): string {
  const expiresAtSeconds = Math.floor(now.getTime() / SECOND_MS) + (minutes * MINUTE_MS) / SECOND_MS;
  const seconds = Buffer.alloc(LINK_VIEW_TOKEN_SECONDS_BYTES);
  seconds.writeUInt32BE(expiresAtSeconds);
  return `${encodeBytes(seconds)}${LINK_VIEW_TOKEN_SEPARATOR}${encodeBytes(signature(secret, linkId, expiresAtSeconds))}`;
}

export function verifyViewToken(secret: Uint8Array, linkId: string, token: string | undefined, now: Date): boolean {
  if (token === undefined) return false;
  const parts = token.split(LINK_VIEW_TOKEN_SEPARATOR);
  if (parts.length !== VIEW_TOKEN_PARTS) return false;
  try {
    const seconds = Buffer.from(decodeBytes(parts[0] as never));
    const mac = decodeBytes(parts[1] as never);
    if (seconds.length !== LINK_VIEW_TOKEN_SECONDS_BYTES) return false;
    const expiresAtSeconds = seconds.readUInt32BE();
    if (!constantTimeEqual(mac, signature(secret, linkId, expiresAtSeconds))) return false;
    return now.getTime() < expiresAtSeconds * SECOND_MS;
  } catch {
    return false;
  }
}
