import { STREAM_HEADER_BYTES, type StreamLayout } from '@kodakiko/core';
import { RANGE_LIST_SEPARATOR, RANGE_PATTERN } from '../keys.ts';

export type ByteRange = { readonly start: number; readonly end: number };

export function parseRange(header: string | null, size: number): ByteRange | null {
  if (size <= 0) return null;
  const whole = { start: 0, end: size - 1 };
  if (header === null) return whole;
  const first = header.split(RANGE_LIST_SEPARATOR)[0]?.trim() ?? '';
  const match = RANGE_PATTERN.exec(first);
  if (match === null) return whole;
  const [, from = '', to = ''] = match;
  if (from === '' && to === '') return whole;
  if (from === '') {
    const suffix = Number(to);
    if (suffix === 0) return null;
    return { start: Math.max(0, size - suffix), end: size - 1 };
  }
  const start = Number(from);
  const end = to === '' ? size - 1 : Math.min(Number(to), size - 1);
  if (start >= size || start > end) return null;
  return { start, end };
}

export function clampRange(range: ByteRange, layout: StreamLayout, maxChunks: number): ByteRange {
  const firstChunk = Math.floor(range.start / layout.chunkSize);
  const limit = (firstChunk + maxChunks) * layout.chunkSize - 1;
  return { start: range.start, end: Math.min(range.end, limit) };
}

export function ciphertextRange(
  layout: StreamLayout,
  firstChunk: number,
  lastChunk: number,
): ByteRange {
  const step = layout.chunkCiphertextSize;
  return {
    start: STREAM_HEADER_BYTES + firstChunk * step,
    end: Math.min(STREAM_HEADER_BYTES + (lastChunk + 1) * step - 1, layout.ciphertextSize - 1),
  };
}
