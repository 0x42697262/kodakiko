export const MARKDOWN_MAX_DEPTH = 6;
export const MAX_HEADING_LEVEL = 3;
export const SAFE_LINK_SCHEMES: ReadonlySet<string> = new Set(['http:', 'https:', 'mailto:']);
export const SCHEME_PATTERN = /^([a-z][a-z0-9+.-]*:)/;
export const LEXER_OPTIONS = Object.freeze({ gfm: true, breaks: true });
export const TRAILING_NEWLINES = /\n+$/;
export const QUOTE_BORDER_WIDTH = 3;
export type HeadingLevel = 1 | 2 | 3;
