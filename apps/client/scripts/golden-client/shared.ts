import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

export const GOLDEN_FORMAT = 1;
export const GENERATOR = 'apps/client/scripts/generate-golden-client.ts';
export const JSON_INDENT = 2;
export const UNDEFINED_KEY = '$undefined';
export const NUMBER_KEY = '$number';
export const INVALID_DATE = 'Invalid Date';
export const FUNCTION_CASE_FIELDS = Object.freeze(['name', 'fn', 'args', 'result'] as const);
export const SLUG_MAX_CHARS = 80;
const SLUG_SEPARATOR = '-';
const SLUG_INVALID = /[^a-z0-9]+/g;
const SLUG_EDGES = /^-+|-+$/g;
const HEX_RADIX = 16;
const HEX_PAIR = 2;

export type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
export type GoldenCase = { readonly [key: string]: Json };
export type FileRef = { readonly file: string; readonly size: number; readonly sha256: string };

export function slug(name: string): string {
  return name.toLowerCase().replace(SLUG_INVALID, SLUG_SEPARATOR).replace(SLUG_EDGES, '').slice(0, SLUG_MAX_CHARS).replace(SLUG_EDGES, '');
}

export function sha256Hex(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex');
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(HEX_RADIX).padStart(HEX_PAIR, '0')).join('');
}

export class GoldenWriter {
  readonly written: string[] = [];
  private readonly directory: string;
  private readonly stored = new Map<string, FileRef>();
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

  async file(directory: string, baseName: string, bytes: Uint8Array, extension: string): Promise<FileRef> {
    const sha256 = sha256Hex(bytes);
    const key = `${directory}/${extension}/${sha256}`;
    const known = this.stored.get(key);
    if (known !== undefined) return known;
    const stem = slug(baseName);
    let path = `${directory}/${stem}${extension}`;
    for (let copy = 2; this.usedNames.has(path); copy += 1) path = `${directory}/${stem}-${copy}${extension}`;
    this.usedNames.add(path);
    const ref = { file: path, size: bytes.length, sha256 };
    this.stored.set(key, ref);
    await this.bytes(path, bytes);
    return ref;
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
    const document = { format: GOLDEN_FORMAT, generator: GENERATOR, cases };
    const text = `${JSON.stringify(document, null, JSON_INDENT)}\n`;
    await this.bytes(relativePath, new TextEncoder().encode(text));
  }
}

export function compareCodeUnits(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function compareJsonKeys(left: Json, right: Json): number {
  if (typeof left === 'number' && typeof right === 'number') return left - right;
  return compareCodeUnits(typeof left === 'string' ? left : JSON.stringify(left), typeof right === 'string' ? right : JSON.stringify(right));
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

export function functionCase(name: string, fn: string, args: Json, run: () => unknown): GoldenCase {
  let result: Json;
  try {
    result = toJson(run());
  } catch (error) {
    result = errorJson(error);
  }
  return { name, fn, args, result };
}

export function setJson(values: ReadonlySet<string>): Json {
  return [...values].sort(compareCodeUnits);
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
