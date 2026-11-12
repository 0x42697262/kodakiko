import { TAG_MAX_CHARS } from '@kodakiko/contract';
import { TAG_UNICODE_FORM, TAG_WORD_SEPARATOR, WHITESPACE_RUN_PATTERN } from './constants.ts';

export function normalizeTag(input: string): string | null {
  const normalized = input
    .normalize(TAG_UNICODE_FORM)
    .trim()
    .replace(WHITESPACE_RUN_PATTERN, TAG_WORD_SEPARATOR)
    .toLowerCase();
  return normalized === '' || normalized.length > TAG_MAX_CHARS ? null : normalized;
}
