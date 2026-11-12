import { USERNAME_PATTERN } from '@kodakiko/contract';

const USERNAME_REGEX = new RegExp(USERNAME_PATTERN);

export function normalizeUsername(input: string): string {
  return input.normalize('NFKC').trim().toLowerCase();
}

export function isValidUsername(normalized: string): boolean {
  return USERNAME_REGEX.test(normalized);
}
