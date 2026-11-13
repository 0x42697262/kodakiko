import { readAscii, readUint32, readUint64 } from './binary.ts';
import type { ByteSource } from './byte-source.ts';
import {
  BOX_HEADER_BYTES,
  BOX_LARGE_HEADER_BYTES,
  BOX_LARGE_SIZE_MARKER,
  BOX_TO_END_MARKER,
  BOX_WALK_MAX_BOXES,
  BOX_XYZ,
  MOOV_MAX_BYTES,
} from './constants.ts';

export const STRIP_COPY_CHUNK_BYTES = 1_048_576;

const UINT32_MAX = 0xffff_ffff;
const UINT32_BYTES = 4;
const UINT64_BYTES = 8;
const TIME_FIELDS_OFFSET = 4;
const ENTRY_COUNT_OFFSET = 4;
const ENTRIES_OFFSET = 8;
const VERSION_1 = 1;
const TIME_FIELD_COUNT = 2;
const UINT32_BITS = 32;
const KEPT_TOP_LEVEL: ReadonlySet<string> = new Set(['ftyp', 'moov', 'mdat', 'free', 'skip', 'wide']);
const CONTAINERS: ReadonlySet<string> = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'dinf', 'mvex']);
const REMOVED: ReadonlySet<string> = new Set(['udta', 'meta', 'uuid']);
const TIMED: ReadonlySet<string> = new Set(['mvhd', 'tkhd', 'mdhd']);
const METADATA_TYPES: ReadonlySet<string> = new Set([...REMOVED, BOX_XYZ]);

const EBML_ID_SEGMENT = 0x18538067;
const EBML_ID_INFO = 0x1549a966;
const EBML_ID_CLUSTER = 0x1f43b675;
const EBML_ID_DATE_UTC = 0x4461;
const EBML_DATE_BYTES = 8;
const EBML_HEADER_PEEK_BYTES = 12;
const EBML_MAX_ID_BYTES = 4;
const EBML_MAX_SIZE_BYTES = 8;
const BITS_PER_BYTE = 8;
const BYTE_MASK = 0xff;

export class UnsupportedVideoError extends Error {}

export type StripSegment =
  | { readonly kind: 'bytes'; readonly bytes: Uint8Array }
  | { readonly kind: 'copy'; readonly offset: number; readonly length: number };

export type StripPlan = {
  readonly segments: readonly StripSegment[];
  readonly outputSize: number;
  readonly moov: Uint8Array;
};

type TopBox = { readonly type: string; readonly offset: number; readonly size: number };

async function topLevelBoxes(source: ByteSource): Promise<TopBox[]> {
  const boxes: TopBox[] = [];
  let offset = 0;
  while (offset < source.size) {
    if (boxes.length > BOX_WALK_MAX_BOXES) throw new UnsupportedVideoError('too many boxes');
    const peek = await source.read(offset, BOX_LARGE_HEADER_BYTES);
    if (peek.length < BOX_HEADER_BYTES) throw new UnsupportedVideoError('truncated box');
    const declared = readUint32(peek, 0);
    const type = readAscii(peek, UINT32_BYTES, UINT32_BYTES);
    let size = declared;
    if (declared === BOX_LARGE_SIZE_MARKER) size = readUint64(peek, BOX_HEADER_BYTES);
    if (declared === BOX_TO_END_MARKER) size = source.size - offset;
    if (size < BOX_HEADER_BYTES || offset + size > source.size) throw new UnsupportedVideoError('bad box size');
    boxes.push({ type, offset, size });
    offset += size;
  }
  return boxes;
}

type Child = { readonly type: string; readonly header: number; readonly bytes: Uint8Array };

function children(bytes: Uint8Array): Child[] {
  const found: Child[] = [];
  let position = 0;
  while (position < bytes.length) {
    if (bytes.length - position < BOX_HEADER_BYTES) throw new UnsupportedVideoError('truncated child');
    const declared = readUint32(bytes, position);
    const type = readAscii(bytes, position + UINT32_BYTES, UINT32_BYTES);
    let header = BOX_HEADER_BYTES;
    let size = declared;
    if (declared === BOX_LARGE_SIZE_MARKER) {
      header = BOX_LARGE_HEADER_BYTES;
      size = readUint64(bytes, position + BOX_HEADER_BYTES);
    }
    if (declared === BOX_TO_END_MARKER) size = bytes.length - position;
    if (size < header || position + size > bytes.length) throw new UnsupportedVideoError('bad child size');
    found.push({ type, header, bytes: bytes.subarray(position, position + size) });
    position += size;
  }
  return found;
}

function boxBytes(type: string, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(BOX_HEADER_BYTES + payload.length);
  new DataView(out.buffer).setUint32(0, out.length);
  for (let index = 0; index < UINT32_BYTES; index += 1) out[UINT32_BYTES + index] = type.charCodeAt(index) & BYTE_MASK;
  out.set(payload, BOX_HEADER_BYTES);
  return out;
}

function zeroTimes(payload: Uint8Array): Uint8Array {
  const copy = payload.slice();
  const width = copy[0] === VERSION_1 ? UINT64_BYTES : UINT32_BYTES;
  copy.fill(0, TIME_FIELDS_OFFSET, TIME_FIELDS_OFFSET + TIME_FIELD_COUNT * width);
  return copy;
}

function shiftOffsets(payload: Uint8Array, wide: boolean, mapOffset: (offset: number) => number): Uint8Array {
  const copy = payload.slice();
  const view = new DataView(copy.buffer);
  const count = view.getUint32(ENTRY_COUNT_OFFSET);
  const width = wide ? UINT64_BYTES : UINT32_BYTES;
  if (ENTRIES_OFFSET + count * width > copy.length) throw new UnsupportedVideoError('bad chunk table');
  for (let index = 0; index < count; index += 1) {
    const at = ENTRIES_OFFSET + index * width;
    const original = wide ? Number(view.getBigUint64(at)) : view.getUint32(at);
    const mapped = mapOffset(original);
    if (wide) view.setBigUint64(at, BigInt(mapped));
    else {
      if (mapped > UINT32_MAX || mapped < 0) throw new UnsupportedVideoError('chunk offset overflow');
      view.setUint32(at, mapped);
    }
  }
  return copy;
}

function rewrite(box: Child, mapOffset: (offset: number) => number): Uint8Array | null {
  if (REMOVED.has(box.type)) return null;
  const payload = box.bytes.subarray(box.header);
  if (CONTAINERS.has(box.type)) {
    const kept = children(payload)
      .map((child) => rewrite(child, mapOffset))
      .filter((child): child is Uint8Array => child !== null);
    const joined = new Uint8Array(kept.reduce((sum, part) => sum + part.length, 0));
    let position = 0;
    for (const part of kept) {
      joined.set(part, position);
      position += part.length;
    }
    return boxBytes(box.type, joined);
  }
  if (TIMED.has(box.type)) return boxBytes(box.type, zeroTimes(payload));
  if (box.type === 'stco') return boxBytes(box.type, shiftOffsets(payload, false, mapOffset));
  if (box.type === 'co64') return boxBytes(box.type, shiftOffsets(payload, true, mapOffset));
  return boxBytes(box.type, payload);
}

export function moovHasMetadata(moov: Uint8Array): boolean {
  const visit = (bytes: Uint8Array): boolean =>
    children(bytes).some(
      (child) => METADATA_TYPES.has(child.type) || (CONTAINERS.has(child.type) && visit(child.bytes.subarray(child.header))),
    );
  return visit(moov.subarray(BOX_HEADER_BYTES));
}

export async function planIsoBmffStrip(source: ByteSource): Promise<StripPlan> {
  const boxes = await topLevelBoxes(source);
  if (boxes.some((box) => box.type === 'moof')) throw new UnsupportedVideoError('fragmented MP4');
  const kept = boxes.filter((box) => KEPT_TOP_LEVEL.has(box.type));
  const moovBoxes = kept.filter((box) => box.type === 'moov');
  const moovBox = moovBoxes[0];
  if (moovBox === undefined || moovBoxes.length !== 1) throw new UnsupportedVideoError('missing moov');
  if (moovBox.size > MOOV_MAX_BYTES) throw new UnsupportedVideoError('moov too large');
  const originalMoov = await source.read(moovBox.offset, moovBox.size);
  const [moovChild] = children(originalMoov);
  if (moovChild === undefined) throw new UnsupportedVideoError('missing moov');
  const sized = rewrite(moovChild, (offset) => offset);
  if (sized === null) throw new UnsupportedVideoError('missing moov');
  const newStarts = new Map<TopBox, number>();
  let position = 0;
  for (const box of kept) {
    newStarts.set(box, position);
    position += box === moovBox ? sized.length : box.size;
  }
  const mdats = kept.filter((box) => box.type === 'mdat');
  const mapOffset = (offset: number): number => {
    const home = mdats.find((box) => offset >= box.offset && offset < box.offset + box.size);
    if (home === undefined) throw new UnsupportedVideoError('chunk offset outside mdat');
    return offset + (newStarts.get(home) ?? 0) - home.offset;
  };
  const moov = rewrite(moovChild, mapOffset);
  if (moov === null) throw new UnsupportedVideoError('missing moov');
  const segments: StripSegment[] = [];
  for (const box of kept) {
    if (box === moovBox) segments.push({ kind: 'bytes', bytes: moov });
    else if (box.type === 'mdat') segments.push({ kind: 'copy', offset: box.offset, length: box.size });
    else segments.push({ kind: 'bytes', bytes: await source.read(box.offset, box.size) });
  }
  return { segments, outputSize: position, moov };
}

export async function* streamStripped(
  source: ByteSource,
  plan: Pick<StripPlan, 'segments'>,
  chunkBytes: number,
): AsyncGenerator<Uint8Array> {
  for (const segment of plan.segments) {
    if (segment.kind === 'bytes') {
      if (segment.bytes.length > 0) yield segment.bytes;
      continue;
    }
    for (let done = 0; done < segment.length; done += chunkBytes) {
      yield await source.read(segment.offset + done, Math.min(chunkBytes, segment.length - done));
    }
  }
}

type EbmlHeader = { readonly id: number; readonly headerBytes: number; readonly size: number | null };

function readEbmlHeader(bytes: Uint8Array): EbmlHeader | null {
  const first = bytes[0];
  if (first === undefined || first === 0) return null;
  const idLength = Math.clz32(first) - (UINT32_BITS - BITS_PER_BYTE) + 1;
  if (idLength > EBML_MAX_ID_BYTES || bytes.length < idLength + 1) return null;
  let id = 0;
  for (let index = 0; index < idLength; index += 1) id = id * (BYTE_MASK + 1) + (bytes[index] ?? 0);
  const sizeFirst = bytes[idLength] ?? 0;
  if (sizeFirst === 0) return null;
  const sizeLength = Math.clz32(sizeFirst) - (UINT32_BITS - BITS_PER_BYTE) + 1;
  if (sizeLength > EBML_MAX_SIZE_BYTES || bytes.length < idLength + sizeLength) return null;
  let size = sizeFirst & (BYTE_MASK >> sizeLength);
  let unknown = size === BYTE_MASK >> sizeLength;
  for (let index = 1; index < sizeLength; index += 1) {
    const value = bytes[idLength + index] ?? 0;
    unknown = unknown && value === BYTE_MASK;
    size = size * (BYTE_MASK + 1) + value;
  }
  return { id, headerBytes: idLength + sizeLength, size: unknown ? null : size };
}

async function findDateUtc(source: ByteSource): Promise<number | null> {
  let offset = 0;
  let end = source.size;
  let insideSegment = false;
  let insideInfo = false;
  while (offset < end) {
    const header = readEbmlHeader(await source.read(offset, EBML_HEADER_PEEK_BYTES));
    if (header === null) return null;
    const dataStart = offset + header.headerBytes;
    if (!insideSegment && header.id === EBML_ID_SEGMENT) {
      insideSegment = true;
      offset = dataStart;
      if (header.size !== null) end = Math.min(end, dataStart + header.size);
      continue;
    }
    if (insideSegment && !insideInfo && header.id === EBML_ID_INFO && header.size !== null) {
      insideInfo = true;
      offset = dataStart;
      end = dataStart + header.size;
      continue;
    }
    if (insideInfo && header.id === EBML_ID_DATE_UTC) return header.size === EBML_DATE_BYTES ? dataStart : null;
    if (header.id === EBML_ID_CLUSTER || header.size === null) return null;
    offset = dataStart + header.size;
  }
  return null;
}

export async function planWebmDateStrip(source: ByteSource): Promise<StripPlan> {
  const at = await findDateUtc(source);
  if (at === null) {
    return { segments: [{ kind: 'copy', offset: 0, length: source.size }], outputSize: source.size, moov: new Uint8Array(0) };
  }
  return {
    segments: [
      { kind: 'copy', offset: 0, length: at },
      { kind: 'bytes', bytes: new Uint8Array(EBML_DATE_BYTES) },
      { kind: 'copy', offset: at + EBML_DATE_BYTES, length: source.size - at - EBML_DATE_BYTES },
    ],
    outputSize: source.size,
    moov: new Uint8Array(0),
  };
}
