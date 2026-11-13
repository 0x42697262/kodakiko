import { describe, expect, it } from 'vitest';
import { byteSourceFromBytes } from '../../src/media/byte-source.ts';
import { readVideoMetadata } from '../../src/media/iso-bmff.ts';
import { hasExifSegment } from '../../src/media/exif.ts';
import {
  STRIP_COPY_CHUNK_BYTES,
  UnsupportedVideoError,
  moovHasMetadata,
  planIsoBmffStrip,
  planWebmDateStrip,
  streamStripped,
} from '../../src/media/strip.ts';
import {
  appleMeta,
  asciiBytes,
  box,
  buildJpeg,
  buildTiff,
  concat,
  exifApp1,
  ftyp,
  insertExifIntoJpeg,
  moov as moovBox,
  mp4Seconds,
  mvhd,
  rotationMatrix,
  seededBytes,
  tkhd,
  u32be,
  u64be,
  udta,
  virtualSource,
  xyzBox,
} from './builders.ts';

const MP4_EPOCH = 2_082_844_800;
const CREATED = '2026-05-01T10:00:00.000Z';
const MDAT_PAYLOAD_BYTES = 4096;
const SAMPLE_POSITIONS = [0, 1000, 2500, 4000];
const GIB = 1_073_741_824;

function fullBox(type: string, version: number, payload: Uint8Array): Uint8Array {
  return box(type, concat([version, 0, 0, 0], payload));
}

function timedBox(type: string, version: 0 | 1, seconds: number, tailBytes: number): Uint8Array {
  const times = version === 0 ? concat(u32be(seconds), u32be(seconds)) : concat(u64be(BigInt(seconds)), u64be(BigInt(seconds)));
  return fullBox(type, version, concat(times, new Uint8Array(tailBytes)));
}

function stco(offsets: readonly number[]): Uint8Array {
  return fullBox('stco', 0, concat(u32be(offsets.length), ...offsets.map((offset) => u32be(offset))));
}

function co64(offsets: readonly number[]): Uint8Array {
  return fullBox('co64', 0, concat(u32be(offsets.length), ...offsets.map((offset) => u64be(BigInt(offset)))));
}

type Layout = 'faststart' | 'moovLast';

type BuildOptions = {
  readonly layout: Layout;
  readonly version: 0 | 1;
  readonly table: 'stco' | 'co64';
  readonly topLevelJunk?: boolean;
};

function buildVideo(options: BuildOptions) {
  const seconds = mp4Seconds(CREATED, MP4_EPOCH);
  const payload = seededBytes(MDAT_PAYLOAD_BYTES, 9);
  const head = ftyp('isom', ['isom', 'mp42']);
  const junk = options.topLevelJunk === true ? concat(box('uuid', seededBytes(40, 3)), udta(xyzBox('+48.8584+002.2945/'))) : new Uint8Array(0);
  const buildMoov = (mdatPayloadStart: number) => {
    const offsets = SAMPLE_POSITIONS.map((position) => mdatPayloadStart + position);
    const table = options.table === 'stco' ? stco(offsets) : co64(offsets);
    const track = box(
      'trak',
      concat(
        tkhd({ version: options.version, matrix: rotationMatrix(0) }),
        udta(box('name', asciiBytes('track name'))),
        box(
          'mdia',
          concat(
            timedBox('mdhd', options.version, seconds, 8),
            box('minf', box('stbl', table)),
          ),
        ),
      ),
    );
    return moovBox(
      mvhd({ version: options.version, creationSeconds1904: seconds }),
      track,
      udta(xyzBox('+48.8584+002.2945/')),
      appleMeta([{ key: 'com.apple.quicktime.location.ISO6709', value: '+48.8584+002.2945/' }], false),
      box('uuid', seededBytes(24, 5)),
    );
  };
  const mdatHeader = 8;
  if (options.layout === 'faststart') {
    const provisional = buildMoov(0);
    const mdatPayloadStart = head.length + junk.length + provisional.length + mdatHeader;
    const finalMoov = buildMoov(mdatPayloadStart);
    return { bytes: concat(head, junk, finalMoov, box('mdat', payload)), payload, mdatPayloadStart };
  }
  const mdatPayloadStart = head.length + junk.length + mdatHeader;
  return { bytes: concat(head, junk, box('mdat', payload), buildMoov(mdatPayloadStart)), payload, mdatPayloadStart };
}

async function collect(chunks: AsyncIterable<Uint8Array>): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  for await (const chunk of chunks) parts.push(chunk);
  return concat(...parts);
}

type Found = { readonly offset: number; readonly payload: Uint8Array };

function findBoxes(bytes: Uint8Array, type: string, base = 0, results: Found[] = []): Found[] {
  const containers = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'udta', 'meta']);
  let position = 0;
  while (position + 8 <= bytes.length) {
    const size = new DataView(bytes.buffer, bytes.byteOffset + position).getUint32(0);
    const name = String.fromCharCode(...bytes.subarray(position + 4, position + 8));
    if (size < 8 || position + size > bytes.length) break;
    const payload = bytes.subarray(position + 8, position + size);
    if (name === type) results.push({ offset: base + position + 8, payload });
    if (containers.has(name)) findBoxes(payload, type, base + position + 8, results);
    position += size;
  }
  return results;
}

function tableOffsets(file: Uint8Array): number[] {
  const [table] = [...findBoxes(file, 'stco'), ...findBoxes(file, 'co64')];
  if (table === undefined) throw new Error('no chunk table');
  const view = new DataView(table.payload.buffer, table.payload.byteOffset);
  const count = view.getUint32(4);
  const wide = findBoxes(file, 'co64').length > 0;
  return Array.from({ length: count }, (_, index) =>
    wide ? Number(view.getBigUint64(8 + index * 8)) : view.getUint32(8 + index * 4),
  );
}

describe('planIsoBmffStrip', () => {
  for (const layout of ['faststart', 'moovLast'] as const) {
    for (const version of [0, 1] as const) {
      for (const table of ['stco', 'co64'] as const) {
        it(`${layout}, version ${version}, ${table}: removes metadata, zeroes times and keeps chunk offsets pointing at the same bytes`, async () => {
          const input = buildVideo({ layout, version, table, topLevelJunk: true });
          const source = byteSourceFromBytes(input.bytes);
          const plan = await planIsoBmffStrip(source);
          const output = await collect(streamStripped(source, plan, STRIP_COPY_CHUNK_BYTES));
          expect(output.length).toBe(plan.outputSize);
          expect(moovHasMetadata(plan.moov)).toBe(false);
          for (const type of ['udta', 'meta', 'uuid']) expect(findBoxes(output, type)).toEqual([]);
          const metadata = await readVideoMetadata(byteSourceFromBytes(output));
          expect(metadata.creationUtc).toBeNull();
          expect(metadata.location).toBeNull();
          for (const type of ['mvhd', 'tkhd', 'mdhd']) {
            const [found] = findBoxes(output, type);
            const timeBytes = version === 0 ? 8 : 16;
            expect([...(found?.payload.subarray(4, 4 + timeBytes) ?? [])].every((value) => value === 0)).toBe(true);
          }
          const before = tableOffsets(input.bytes);
          const after = tableOffsets(output);
          for (const [index, offset] of before.entries()) {
            expect(output.subarray(after[index] ?? 0, (after[index] ?? 0) + 16)).toEqual(input.bytes.subarray(offset, offset + 16));
          }
        });
      }
    }
  }

  it('rejects fragmented MP4 (moof)', async () => {
    const bytes = concat(ftyp('isom'), moovBox(mvhd({ version: 0, creationSeconds1904: 0 })), box('moof', new Uint8Array(16)), box('mdat', new Uint8Array(16)));
    await expect(planIsoBmffStrip(byteSourceFromBytes(bytes))).rejects.toBeInstanceOf(UnsupportedVideoError);
  });

  it('rejects a chunk offset that points outside every mdat', async () => {
    const moov = moovBox(
      mvhd({ version: 0, creationSeconds1904: 0 }),
      box('trak', box('mdia', box('minf', box('stbl', stco([0xffff_fff0]))))),
    );
    const bytes = concat(ftyp('isom'), moov, box('mdat', new Uint8Array(64)));
    await expect(planIsoBmffStrip(byteSourceFromBytes(bytes))).rejects.toBeInstanceOf(UnsupportedVideoError);
  });

  it('streams a 2 GiB mdat in copy-chunk reads without reading it all at once', async () => {
    const moov = moovBox(mvhd({ version: 0, creationSeconds1904: 1 }), udta(xyzBox('+10.0000+010.0000/')));
    const prefix = concat(ftyp('isom'), moov);
    const mdatSize = 2 * GIB;
    const mdatHeader = concat(u32be(1), asciiBytes('mdat'), u64be(BigInt(mdatSize)));
    const source = virtualSource(prefix.length + mdatSize, [{ offset: 0, bytes: concat(prefix, mdatHeader) }]);
    const plan = await planIsoBmffStrip(source);
    let total = 0;
    let largest = 0;
    for await (const chunk of streamStripped(source, plan, STRIP_COPY_CHUNK_BYTES)) {
      total += chunk.length;
      largest = Math.max(largest, chunk.length);
    }
    expect(total).toBe(plan.outputSize);
    expect(largest).toBeLessThanOrEqual(STRIP_COPY_CHUNK_BYTES);
    expect(Math.max(...source.reads.map((read) => read.length))).toBeLessThanOrEqual(STRIP_COPY_CHUNK_BYTES);
  });
});

function ebmlElement(id: readonly number[], payload: Uint8Array): Uint8Array {
  const size = payload.length;
  return concat(id, [0x01], u64be(BigInt(size)).slice(1), payload);
}

function buildWebm(withDate: boolean) {
  const header = ebmlElement([0x1a, 0x45, 0xdf, 0xa3], ebmlElement([0x42, 0x82], asciiBytes('webm')));
  const timecode = ebmlElement([0x2a, 0xd7, 0xb1], Uint8Array.of(0x0f, 0x42, 0x40));
  const date = withDate ? ebmlElement([0x44, 0x61], Uint8Array.of(1, 2, 3, 4, 5, 6, 7, 8)) : new Uint8Array(0);
  const info = ebmlElement([0x15, 0x49, 0xa9, 0x66], concat(timecode, date));
  const cluster = ebmlElement([0x1f, 0x43, 0xb6, 0x75], seededBytes(64, 4));
  const segment = ebmlElement([0x18, 0x53, 0x80, 0x67], concat(info, cluster));
  return concat(header, segment);
}

describe('planWebmDateStrip', () => {
  it('overwrites DateUTC with zeros and changes nothing else', async () => {
    const input = buildWebm(true);
    const source = byteSourceFromBytes(input);
    const output = await collect(streamStripped(source, await planWebmDateStrip(source), STRIP_COPY_CHUNK_BYTES));
    expect(output.length).toBe(input.length);
    const at = [...input.keys()].find((index) => [1, 2, 3, 4, 5, 6, 7, 8].every((value, step) => input[index + step] === value)) ?? -1;
    expect(at).toBeGreaterThan(0);
    expect([...output.subarray(at, at + 8)]).toEqual([0, 0, 0, 0, 0, 0, 0, 0]);
    expect(output.subarray(0, at)).toEqual(input.subarray(0, at));
    expect(output.subarray(at + 8)).toEqual(input.subarray(at + 8));
  });

  it('copies a WebM without DateUTC unchanged', async () => {
    const input = buildWebm(false);
    const source = byteSourceFromBytes(input);
    const output = await collect(streamStripped(source, await planWebmDateStrip(source), STRIP_COPY_CHUNK_BYTES));
    expect(output).toEqual(input);
  });
});

describe('hasExifSegment', () => {
  it('finds an APP1 Exif segment and ignores JPEGs without one', () => {
    const plain = buildJpeg();
    expect(hasExifSegment(plain)).toBe(false);
    expect(hasExifSegment(insertExifIntoJpeg(plain, buildTiff({ dateTimeOriginal: '2026:01:01 00:00:00' })))).toBe(true);
    expect(exifApp1(buildTiff()).length).toBeGreaterThan(0);
  });
});
