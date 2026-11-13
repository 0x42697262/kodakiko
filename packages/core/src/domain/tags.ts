import { TAGS_PER_ENTRY_MAX, TAG_MAX_CHARS } from '@kodakiko/contract';
import { TAG_UNICODE_FORM, TAG_WORD_SEPARATOR, WHITESPACE_RUN_PATTERN } from './constants.ts';

export type TagRejection = 'empty' | 'tooLong' | 'tooMany' | 'duplicate';

export type AddTagResult = {
  readonly tags: readonly string[];
  readonly rejected: TagRejection | null;
};

function cleanTag(input: string): string {
  return input
    .normalize(TAG_UNICODE_FORM)
    .trim()
    .replace(WHITESPACE_RUN_PATTERN, TAG_WORD_SEPARATOR)
    .toLowerCase();
}

export function normalizeTag(input: string): string | null {
  const normalized = cleanTag(input);
  return normalized === '' || normalized.length > TAG_MAX_CHARS ? null : normalized;
}

export function sortTags(tags: readonly string[]): readonly string[] {
  return [...tags].sort();
}

export function addTag(tags: readonly string[], raw: string): AddTagResult {
  const normalized = cleanTag(raw);
  if (normalized === '') return { tags, rejected: 'empty' };
  if (normalized.length > TAG_MAX_CHARS) return { tags, rejected: 'tooLong' };
  if (tags.includes(normalized)) return { tags, rejected: 'duplicate' };
  if (tags.length >= TAGS_PER_ENTRY_MAX) return { tags, rejected: 'tooMany' };
  return { tags: sortTags([...tags, normalized]), rejected: null };
}

export function removeTag(tags: readonly string[], tag: string): readonly string[] {
  const normalized = normalizeTag(tag);
  if (normalized === null) return tags;
  return tags.filter((existing) => existing !== normalized);
}
