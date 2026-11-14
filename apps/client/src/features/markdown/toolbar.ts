export type MarkdownAction = 'bold' | 'italic' | 'strike' | 'heading' | 'bullet' | 'checklist' | 'quote' | 'link' | 'time';
export type Selection = { readonly start: number; readonly end: number };
export type Edited = { readonly text: string; readonly selection: Selection };

const WRAPS: Readonly<Partial<Record<MarkdownAction, string>>> = Object.freeze({ bold: '**', italic: '*', strike: '~~' });
const PREFIXES: Readonly<Partial<Record<MarkdownAction, string>>> = Object.freeze({ bullet: '- ', checklist: '- [ ] ', quote: '> ' });
const HEADINGS = ['# ', '## '] as const;
const LINK_URL = 'url';
const LINE_BREAK = '\n';
const NOT_FOUND = -1;

function wrap(text: string, selection: Selection, marker: string): Edited {
  const before = text.slice(0, selection.start);
  const inner = text.slice(selection.start, selection.end);
  const after = text.slice(selection.end);
  const start = selection.start + marker.length;
  return { text: `${before}${marker}${inner}${marker}${after}`, selection: { start, end: start + inner.length } };
}

function lineStart(text: string, position: number): number {
  const index = text.lastIndexOf(LINE_BREAK, position - 1);
  return index === NOT_FOUND ? 0 : index + 1;
}

function replaceLinePrefix(text: string, selection: Selection, remove: string, add: string): Edited {
  const start = lineStart(text, selection.start);
  const shift = add.length - remove.length;
  return {
    text: text.slice(0, start) + add + text.slice(start + remove.length),
    selection: { start: Math.max(start, selection.start + shift), end: Math.max(start, selection.end + shift) },
  };
}

function cycleHeading(text: string, selection: Selection): Edited {
  const line = text.slice(lineStart(text, selection.start));
  const current = [...HEADINGS].reverse().find((heading) => line.startsWith(heading)) ?? '';
  const index = HEADINGS.indexOf(current as (typeof HEADINGS)[number]);
  const next = index === NOT_FOUND ? HEADINGS[0] : (HEADINGS[index + 1] ?? '');
  return replaceLinePrefix(text, selection, current, next);
}

export function applyMarkdownAction(action: MarkdownAction, text: string, selection: Selection, time = ''): Edited {
  const marker = WRAPS[action];
  if (marker !== undefined) return wrap(text, selection, marker);
  const prefix = PREFIXES[action];
  if (prefix !== undefined) return replaceLinePrefix(text, selection, '', prefix);
  if (action === 'heading') return cycleHeading(text, selection);
  if (action === 'link') {
    const label = text.slice(selection.start, selection.end);
    const head = `${text.slice(0, selection.start)}[${label}](`;
    return { text: `${head}${LINK_URL})${text.slice(selection.end)}`, selection: { start: head.length, end: head.length + LINK_URL.length } };
  }
  const head = text.slice(0, selection.start) + time;
  return { text: head + text.slice(selection.end), selection: { start: head.length, end: head.length } };
}
