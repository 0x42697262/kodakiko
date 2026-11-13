import { FILE_NAME_MAX_CHARS } from '@kodakiko/contract';

const EXTENSION_SEPARATOR = '.';

export function shortenFileName(name: string, maxChars: number = FILE_NAME_MAX_CHARS): string {
  if (name.length <= maxChars) return name;
  const dot = name.lastIndexOf(EXTENSION_SEPARATOR);
  const extension = dot > 0 ? name.slice(dot) : '';
  if (extension.length >= maxChars) return name.slice(0, maxChars);
  return `${name.slice(0, maxChars - extension.length)}${extension}`;
}
