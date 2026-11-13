import {
  MAX_INDEX_TOKENS_PER_ENTRY,
  TOKEN_MIN_CHARS,
  TOKEN_SEPARATOR_PATTERN,
  TOKEN_UNICODE_FORM,
} from './constants.ts';

export function tokenize(text: string): readonly string[] {
  const tokens = new Set<string>();
  for (const part of text.normalize(TOKEN_UNICODE_FORM).toLowerCase().split(TOKEN_SEPARATOR_PATTERN)) {
    if (part.length < TOKEN_MIN_CHARS) continue;
    tokens.add(part);
    if (tokens.size >= MAX_INDEX_TOKENS_PER_ENTRY) break;
  }
  return [...tokens];
}
