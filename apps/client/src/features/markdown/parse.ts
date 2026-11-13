import { marked, type Token, type Tokens } from 'marked';
import { EN } from '../../text/en';
import {
  type HeadingLevel,
  LEXER_OPTIONS,
  MARKDOWN_MAX_DEPTH,
  MAX_HEADING_LEVEL,
  SAFE_LINK_SCHEMES,
  SCHEME_PATTERN,
  TRAILING_NEWLINES,
} from './constants';

export type Inline =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'strong' | 'em' | 'del'; readonly children: readonly Inline[] }
  | { readonly type: 'code'; readonly text: string }
  | { readonly type: 'link'; readonly href: string | null; readonly children: readonly Inline[] }
  | { readonly type: 'break' };

export type Block =
  | { readonly type: 'heading'; readonly level: HeadingLevel; readonly children: readonly Inline[] }
  | { readonly type: 'paragraph'; readonly children: readonly Inline[] }
  | { readonly type: 'code'; readonly text: string }
  | { readonly type: 'quote'; readonly blocks: readonly Block[] }
  | { readonly type: 'list'; readonly ordered: boolean; readonly start: number; readonly items: readonly ListItem[] }
  | { readonly type: 'rule' };

export type ListItem = { readonly checked: boolean | null; readonly blocks: readonly Block[] };

function rawText(token: Token): string {
  return token.raw.replace(TRAILING_NEWLINES, '');
}

function safeHref(href: string): string | null {
  const scheme = SCHEME_PATTERN.exec(href.trim().toLowerCase())?.[1];
  return scheme !== undefined && SAFE_LINK_SCHEMES.has(scheme) ? href : null;
}

function headingLevel(depth: number): HeadingLevel {
  return Math.min(Math.max(depth, 1), MAX_HEADING_LEVEL) as HeadingLevel;
}

function inlines(tokens: readonly Token[] | undefined): Inline[] {
  return (tokens ?? []).flatMap(inline);
}

function inline(token: Token): Inline[] {
  switch (token.type) {
    case 'text':
    case 'escape': {
      const text = token as Tokens.Text;
      return text.tokens !== undefined && text.tokens.length > 0
        ? inlines(text.tokens)
        : [{ type: 'text', text: text.text }];
    }
    case 'strong':
    case 'em':
    case 'del':
      return [{ type: token.type, children: inlines((token as Tokens.Strong).tokens) }];
    case 'codespan':
      return [{ type: 'code', text: (token as Tokens.Codespan).text }];
    case 'br':
      return [{ type: 'break' }];
    case 'link': {
      const link = token as Tokens.Link;
      return [{ type: 'link', href: safeHref(link.href), children: inlines(link.tokens) }];
    }
    case 'image': {
      const alt = (token as Tokens.Image).text;
      return [{ type: 'text', text: alt === '' ? EN['markdown.image'] : alt }];
    }
    default:
      return [{ type: 'text', text: token.raw }];
  }
}

function plain(token: Token): Block {
  return { type: 'paragraph', children: [{ type: 'text', text: rawText(token) }] };
}

function blocks(tokens: readonly Token[], depth: number): Block[] {
  return tokens.flatMap((token) => block(token, depth));
}

function block(token: Token, depth: number): Block[] {
  switch (token.type) {
    case 'space':
      return [];
    case 'heading': {
      const heading = token as Tokens.Heading;
      return [{ type: 'heading', level: headingLevel(heading.depth), children: inlines(heading.tokens) }];
    }
    case 'paragraph':
      return [{ type: 'paragraph', children: inlines((token as Tokens.Paragraph).tokens) }];
    case 'text': {
      const text = token as Tokens.Text;
      return [{ type: 'paragraph', children: text.tokens === undefined ? [{ type: 'text', text: text.text }] : inlines(text.tokens) }];
    }
    case 'code':
      return [{ type: 'code', text: (token as Tokens.Code).text }];
    case 'hr':
      return [{ type: 'rule' }];
    case 'blockquote':
      if (depth >= MARKDOWN_MAX_DEPTH) return [plain(token)];
      return [{ type: 'quote', blocks: blocks((token as Tokens.Blockquote).tokens, depth + 1) }];
    case 'list': {
      if (depth >= MARKDOWN_MAX_DEPTH) return [plain(token)];
      const list = token as Tokens.List;
      return [
        {
          type: 'list',
          ordered: list.ordered,
          start: list.ordered ? Number(list.start) : 1,
          items: list.items.map((item) => ({
            checked: item.task ? item.checked === true : null,
            blocks: blocks(
              item.tokens.filter((child) => child.type !== 'checkbox'),
              depth + 1,
            ),
          })),
        },
      ];
    }
    default:
      return [plain(token)];
  }
}

export function parseMarkdown(source: string): readonly Block[] {
  return blocks(marked.lexer(source, { ...LEXER_OPTIONS }), 0);
}
