import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { bytesToHex } from '../../src/crypto/index.ts';
import type { ByteSource } from '../../src/media/byte-source.ts';

export const GOLDEN_FORMAT = 1;
export const GENERATOR = 'packages/core/scripts/generate-goldens.ts';
export const JSON_INDENT = 2;
export const UNDEFINED_KEY = '$undefined';
export const NUMBER_KEY = '$number';
export const INVALID_DATE = 'Invalid Date';
export const FUNCTION_CASE_FIELDS = Object.freeze(['name', 'fn', 'args', 'result'] as const);

export type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
export type GoldenCase = { readonly [key: string]: Json };

export const FILL_MIN_RUN = 4096;
export const HASH_CHUNK_BYTES = 1_048_576;
export const SLUG_MAX_CHARS = 80;
const SLUG_SEPARATOR = '-';
const SLUG_INVALID = /[^a-z0-9]+/g;
const SLUG_EDGES = /^-+|-+$/g;

export type ContentSegment =
  | { readonly kind: 'bytes'; readonly bytes: Uint8Array }
  | { readonly kind: 'fill'; readonly value: number; readonly size: number };

export type Content = readonly ContentSegment[];

export type ReadStats = {
  readonly reads: number;
  readonly bytesRequested: number;
  readonly largestRead: number;
  readonly furthestOffset: number;
};

export type RecordingSource = ByteSource & { readonly stats: () => ReadStats };

export function slug(name: string): string {
  return name.toLowerCase().replace(SLUG_INVALID, SLUG_SEPARATOR).replace(SLUG_EDGES, '').slice(0, SLUG_MAX_CHARS).replace(SLUG_EDGES, '');
}

export class ContentBuilder {
  private readonly segments: ContentSegment[] = [];
  private pending: Uint8Array[] = [];

  private flushBytes(): void {
    if (this.pending.length === 0) return;
    const total = this.pending.reduce((sum, part) => sum + part.length, 0);
    const joined = new Uint8Array(total);
    let offset = 0;
    for (const part of this.pending) {
      joined.set(part, offset);
      offset += part.length;
    }
    this.segments.push({ kind: 'bytes', bytes: joined });
    this.pending = [];
  }

  fill(value: number, size: number): this {
    if (size === 0) return this;
    const last = this.segments[this.segments.length - 1];
    if (this.pending.length === 0 && last !== undefined && last.kind === 'fill' && last.value === value) {
      this.segments[this.segments.length - 1] = { kind: 'fill', value, size: last.size + size };
      return this;
    }
    if (size < FILL_MIN_RUN) return this.bytes(new Uint8Array(size).fill(value));
    this.flushBytes();
    this.segments.push({ kind: 'fill', value, size });
    return this;
  }

  bytes(bytes: Uint8Array): this {
    let start = 0;
    let position = 0;
    while (position < bytes.length) {
      const value = bytes[position] ?? 0;
      let end = position + 1;
      while (end < bytes.length && bytes[end] === value) end += 1;
      const last = this.segments[this.segments.length - 1];
      const extendsFill = position === 0 && this.pending.length === 0 && last !== undefined && last.kind === 'fill' && last.value === value;
      if (extendsFill || end - position >= FILL_MIN_RUN) {
        if (position > start) this.pending.push(bytes.slice(start, position));
        this.fill(value, end - position);
        if (end - position < FILL_MIN_RUN) this.flushBytes();
        start = end;
      }
      position = end;
    }
    if (start < bytes.length) this.pending.push(bytes.slice(start));
    return this;
  }

  build(): Content {
    this.flushBytes();
    return [...this.segments];
  }
}

export function contentOf(bytes: Uint8Array): Content {
  return new ContentBuilder().bytes(bytes).build();
}

export function placedContent(size: number, placed: readonly { readonly offset: number; readonly bytes: Uint8Array }[]): Content {
  const builder = new ContentBuilder();
  let position = 0;
  for (const part of [...placed].sort((left, right) => left.offset - right.offset)) {
    builder.fill(0, part.offset - position);
    builder.bytes(part.bytes);
    position = part.offset + part.bytes.length;
  }
  builder.fill(0, size - position);
  return builder.build();
}

export function segmentSize(segment: ContentSegment): number {
  return segment.kind === 'bytes' ? segment.bytes.length : segment.size;
}

export function contentSize(content: Content): number {
  return content.reduce((sum, segment) => sum + segmentSize(segment), 0);
}

export function contentSha256(content: Content): string {
  const hash = createHash('sha256');
  for (const segment of content) {
    if (segment.kind === 'bytes') {
      hash.update(segment.bytes);
      continue;
    }
    const block = new Uint8Array(Math.min(HASH_CHUNK_BYTES, segment.size)).fill(segment.value);
    for (let done = 0; done < segment.size; done += block.length) {
      hash.update(block.subarray(0, Math.min(block.length, segment.size - done)));
    }
  }
  return hash.digest('hex');
}

export function readContent(content: Content, offset: number, length: number): Uint8Array {
  const size = contentSize(content);
  const start = Math.max(0, offset);
  const end = Math.min(size, start + Math.max(0, length));
  if (start >= end) return new Uint8Array(0);
  const result = new Uint8Array(end - start);
  let position = 0;
  for (const segment of content) {
    const segmentEnd = position + segmentSize(segment);
    const from = Math.max(start, position);
    const to = Math.min(end, segmentEnd);
    if (from < to) {
      if (segment.kind === 'bytes') result.set(segment.bytes.subarray(from - position, to - position), from - start);
      else result.fill(segment.value, from - start, to - start);
    }
    position = segmentEnd;
  }
  return result;
}

export function materialize(content: Content): Uint8Array {
  return readContent(content, 0, contentSize(content));
}

export function recordingSource(content: Content): RecordingSource {
  const size = contentSize(content);
  const reads: { readonly offset: number; readonly length: number }[] = [];
  return {
    size,
    read: async (offset, length) => {
      reads.push({ offset, length });
      return readContent(content, offset, length);
    },
    stats: () => ({
      reads: reads.length,
      bytesRequested: reads.reduce((sum, record) => sum + record.length, 0),
      largestRead: reads.reduce((max, record) => Math.max(max, record.length), 0),
      furthestOffset: reads.reduce((max, record) => Math.max(max, record.offset + record.length), 0),
    }),
  };
}

export function failingSource(size: number, message: string): ByteSource {
  return {
    size,
    read: async () => {
      throw new Error(message);
    },
  };
}

export function failingSourceJson(size: number, message: string): Json {
  return { failing: { size, message } };
}

export class GoldenWriter {
  readonly written: string[] = [];
  private readonly directory: string;
  private readonly stored = new Map<string, string>();
  private readonly usedNames = new Set<string>();

  constructor(directory: string) {
    this.directory = directory;
  }

  async bytes(relativePath: string, content: Uint8Array): Promise<void> {
    const target = join(this.directory, relativePath);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content);
    this.written.push(relativePath);
  }

  private async storeFile(directory: string, baseName: string, extension: string, bytes: Uint8Array): Promise<string> {
    const digest = sha256Hex(bytes);
    const key = `${directory}/${digest}`;
    const known = this.stored.get(key);
    if (known !== undefined) return known;
    const stem = slug(baseName);
    let path = `${directory}/${stem}${extension}`;
    for (let copy = 2; this.usedNames.has(path); copy += 1) path = `${directory}/${stem}-${copy}${extension}`;
    this.usedNames.add(path);
    this.stored.set(key, path);
    await this.bytes(path, bytes);
    return path;
  }

  async content(directory: string, baseName: string, content: Content, extension = '.bin'): Promise<Json> {
    const size = contentSize(content);
    const sha256 = contentSha256(content);
    const only = content[0];
    if (content.length === 0 || (content.length === 1 && only?.kind === 'bytes')) {
      const bytes = only?.kind === 'bytes' ? only.bytes : new Uint8Array(0);
      return { file: await this.storeFile(directory, baseName, extension, bytes), size, sha256 };
    }
    const segments: Json[] = [];
    let offset = 0;
    let part = 0;
    for (const segment of content) {
      const length = segmentSize(segment);
      if (segment.kind === 'fill') {
        segments.push({ offset, size: length, fill: segment.value });
      } else {
        part += 1;
        segments.push({ offset, size: length, file: await this.storeFile(directory, `${baseName}-part${part}`, extension, segment.bytes) });
      }
      offset += length;
    }
    return { size, sha256, segments };
  }

  async file(directory: string, baseName: string, bytes: Uint8Array, extension = '.bin'): Promise<Json> {
    return this.content(directory, baseName, contentOf(bytes), extension);
  }

  async cases(relativePath: string, cases: readonly GoldenCase[]): Promise<void> {
    const document = { format: GOLDEN_FORMAT, generator: GENERATOR, cases };
    const text = `${JSON.stringify(document, null, JSON_INDENT)}\n`;
    await this.bytes(relativePath, new TextEncoder().encode(text));
  }

  async functionCases(relativePath: string, cases: readonly GoldenCase[]): Promise<void> {
    const seen = new Set<string>();
    for (const goldenCase of cases) {
      for (const field of FUNCTION_CASE_FIELDS) {
        if (!(field in goldenCase)) throw new Error(`${relativePath}: case without ${field}`);
      }
      const name = goldenCase['name'];
      if (typeof name !== 'string' || seen.has(name)) throw new Error(`${relativePath}: duplicate case name ${String(name)}`);
      seen.add(name);
    }
    await this.cases(relativePath, cases);
  }
}

function compareJsonKeys(left: Json, right: Json): number {
  if (typeof left === 'number' && typeof right === 'number') return left - right;
  const a = typeof left === 'string' ? left : JSON.stringify(left);
  const b = typeof right === 'string' ? right : JSON.stringify(right);
  return a < b ? -1 : a > b ? 1 : 0;
}

function numberToJson(value: number): Json {
  if (Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
  return { [NUMBER_KEY]: String(value) };
}

export function toJson(value: unknown): Json {
  if (value === undefined) return { [UNDEFINED_KEY]: true };
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return value;
  if (typeof value === 'number') return numberToJson(value);
  if (typeof value === 'bigint') return value.toString();
  if (value instanceof Uint8Array) return bytesToHex(value);
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? INVALID_DATE : value.toISOString();
  if (value instanceof Map) return [...value.entries()].map(([key, entry]) => [toJson(key), toJson(entry)]);
  if (value instanceof Set) return [...value].map(toJson).sort(compareJsonKeys);
  if (Array.isArray(value)) return value.map(toJson);
  if (typeof value === 'object') {
    const out: { [key: string]: Json } = {};
    for (const [key, entry] of Object.entries(value)) {
      if (entry !== undefined) out[key] = toJson(entry);
    }
    return out;
  }
  throw new Error(`golden: cannot encode ${typeof value}`);
}

export function errorJson(error: unknown): Json {
  if (!(error instanceof Error)) throw new Error(`golden: non-Error thrown: ${String(error)}`);
  return { error: error.constructor.name, message: error.message };
}

export function outcome(run: () => unknown, encode: (value: unknown) => Json = toJson): Json {
  let value: unknown;
  try {
    value = run();
  } catch (error) {
    return errorJson(error);
  }
  return encode(value);
}

export async function outcomeAsync(run: () => Promise<unknown>, encode: (value: unknown) => Json = toJson): Promise<Json> {
  let value: unknown;
  try {
    value = await run();
  } catch (error) {
    return errorJson(error);
  }
  return encode(value);
}

export function functionCase(
  name: string,
  fn: string,
  args: Json,
  run: () => unknown,
  extra: { readonly [key: string]: Json } = {},
): GoldenCase {
  return { name, fn, args, result: outcome(run), ...extra };
}

export async function asyncFunctionCase(
  name: string,
  fn: string,
  args: Json,
  run: () => Promise<unknown>,
  extra: { readonly [key: string]: Json } = {},
): Promise<GoldenCase> {
  return { name, fn, args, result: await outcomeAsync(run), ...extra };
}

export function sha256Hex(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex');
}

export async function withTimeZone<T>(timeZone: string, run: () => Promise<T>): Promise<T> {
  const previous = process.env['TZ'];
  process.env['TZ'] = timeZone;
  try {
    return await run();
  } finally {
    if (previous === undefined) delete process.env['TZ'];
    else process.env['TZ'] = previous;
  }
}
