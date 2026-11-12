import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

export function sha256(bytes: Uint8Array): Buffer {
  return createHash('sha256').update(bytes).digest();
}

export function sha256OfText(text: string): Buffer {
  return createHash('sha256').update(text, 'utf8').digest();
}

export function hmacSha256(key: Uint8Array, label: string, message: string): Buffer {
  return createHmac('sha256', key).update(label, 'utf8').update(message, 'utf8').digest();
}

export function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && timingSafeEqual(left, right);
}
