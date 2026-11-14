const FENCE = '---';
const FENCE_LINE = `${FENCE}\n`;
const FENCE_BREAK = `\n${FENCE}\n`;
const MS_PER_MINUTE = 60_000;
const MINUTES_PER_HOUR = 60;
const DATE_PATTERN = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})([+-])(\d{2}):(\d{2})$/;
const INVALID = 'This file is not an entry from a Kodakiko export.';
const SIGN_NEGATIVE = -1;
const SIGN_POSITIVE = 1;

export type ParsedEntryNote = {
  readonly id: string;
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly tags: readonly string[];
  readonly favorite: boolean;
  readonly location: { readonly latitude: number; readonly longitude: number; readonly source: string } | null;
  readonly media: readonly { readonly file: string; readonly originalName: string | null; readonly kind: 'photo' | 'video' | 'audio'; readonly caption?: string }[];
  readonly note: string;
};

export class InvalidEntryNoteError extends Error {}

type Value = string | Value[] | { [key: string]: Value };

function parseFlow(text: string): Value {
  let position = 0;
  const skip = () => {
    while (text[position] === ' ') position += 1;
  };
  function scalar(): string {
    skip();
    if (text[position] === '"') {
      position += 1;
      let out = '';
      while (position < text.length && text[position] !== '"') {
        if (text[position] === '\\') position += 1;
        out += text[position] ?? '';
        position += 1;
      }
      position += 1;
      return out;
    }
    const start = position;
    while (position < text.length && !',]}'.includes(text[position] ?? '')) position += 1;
    return text.slice(start, position).trim();
  }
  function value(): Value {
    skip();
    const opener = text[position];
    if (opener === '[') {
      position += 1;
      const list: Value[] = [];
      skip();
      if (text[position] === ']') {
        position += 1;
        return list;
      }
      for (;;) {
        list.push(value());
        skip();
        const next = text[position];
        position += 1;
        if (next === ']') return list;
        if (next !== ',') throw new InvalidEntryNoteError(INVALID);
      }
    }
    if (opener === '{') {
      position += 1;
      const map: { [key: string]: Value } = {};
      for (;;) {
        skip();
        const colon = text.indexOf(':', position);
        if (colon < 0) throw new InvalidEntryNoteError(INVALID);
        const key = text.slice(position, colon).trim();
        position = colon + 1;
        map[key] = value();
        skip();
        const next = text[position];
        position += 1;
        if (next === '}') return map;
        if (next !== ',') throw new InvalidEntryNoteError(INVALID);
      }
    }
    return scalar();
  }
  return value();
}

function text(value: Value | undefined): string {
  if (typeof value !== 'string') throw new InvalidEntryNoteError(INVALID);
  return value;
}

export function parseEntryNote(source: string): ParsedEntryNote {
  if (!source.startsWith(FENCE_LINE)) throw new InvalidEntryNoteError(INVALID);
  const end = source.indexOf(FENCE_BREAK, FENCE.length);
  if (end < 0) throw new InvalidEntryNoteError(INVALID);
  const fields = new Map<string, string>();
  for (const line of source.slice(FENCE_LINE.length, end).split('\n')) {
    const colon = line.indexOf(':');
    if (colon > 0) fields.set(line.slice(0, colon).trim(), line.slice(colon + 1).trim());
  }
  const date = DATE_PATTERN.exec(fields.get('date') ?? '');
  if (date === null) throw new InvalidEntryNoteError(INVALID);
  const sign = date[2] === '-' ? SIGN_NEGATIVE : SIGN_POSITIVE;
  const utcOffsetMinutes = sign * (Number(date[3]) * MINUTES_PER_HOUR + Number(date[4]));
  const takenAtUtc = new Date(Date.parse(`${date[1] ?? ''}Z`) - utcOffsetMinutes * MS_PER_MINUTE).toISOString();
  const tags = parseFlow(fields.get('tags') ?? '[]');
  const media = parseFlow(fields.get('media') ?? '[]');
  const location = fields.has('location') ? parseFlow(fields.get('location') ?? '') : null;
  if (!Array.isArray(tags) || !Array.isArray(media)) throw new InvalidEntryNoteError(INVALID);
  const body = source.slice(end + FENCE_BREAK.length);
  const note = body.startsWith('\n') ? body.slice(1).replace(/\n$/, '') : body.replace(/\n$/, '');
  return {
    id: text(fields.get('id')),
    takenAtUtc,
    utcOffsetMinutes,
    tags: tags.map((tag) => text(tag)),
    favorite: fields.get('favorite') === 'true',
    location:
      location === null || Array.isArray(location) || typeof location === 'string'
        ? null
        : { latitude: Number(text(location['latitude'])), longitude: Number(text(location['longitude'])), source: text(location['source']) },
    media: media.map((item) => {
      if (typeof item !== 'object' || Array.isArray(item)) throw new InvalidEntryNoteError(INVALID);
      const kind = text(item['kind']);
      if (kind !== 'photo' && kind !== 'video' && kind !== 'audio') throw new InvalidEntryNoteError(INVALID);
      const caption = item['caption'] === undefined ? null : text(item['caption']);
      return {
        file: text(item['file']),
        originalName: item['original_name'] === undefined ? null : text(item['original_name']),
        kind,
        ...(caption === null ? {} : { caption }),
      };
    }),
    note,
  };
}
