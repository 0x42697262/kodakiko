import { utf8Encode } from './bytes.ts';

export function passwordToBytes(password: string): Uint8Array {
  return utf8Encode(password.normalize('NFKC'));
}
