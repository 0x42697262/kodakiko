import {
  ISO6709_MAX_BYTES,
  KEYS_NAME_MAX_BYTES,
  MOOV_MAX_BYTES,
  MP4_EPOCH_OFFSET_SECONDS,
  byteSourceFromBytes,
  hasExifSegment,
  moovHasMetadata,
  parseIso6709,
  planIsoBmffStrip,
  planWebmDateStrip,
  readVideoMetadata,
  streamStripped,
  STRIP_COPY_CHUNK_BYTES,
  type ByteSource,
  type StripPlan,
} from '../../src/media/index.ts';
import {
  appleMeta,
  asciiBytes,
  box,
  buildJpeg,
  buildTiff,
  concat,
  ftyp,
  insertExifIntoJpeg,
  jfifApp0,
  moov,
  mp4Seconds,
  mvhd,
  rotationMatrix,
  seededBytes,
  tkhd,
  trak,
  u32be,
  u64be,
  udta,
  xmpApp1,
  xyzBox,
  type Rotation,
} from '../../test/media/builders.ts';
import { MEDIA_OUTPUTS, hex, inputContentRef, inputRef } from './media-image.ts';
import {
  ContentBuilder,
  asyncFunctionCase,
  contentOf,
  failingSource,
  failingSourceJson,
  functionCase,
  outcomeAsync,
  placedContent,
  recordingSource,
  toJson,
  type Content,
  type GoldenCase,
  type GoldenWriter,
  type Json,
} from './shared.ts';

const PATHS = Object.freeze({
  isobmff: 'media/isobmff.json',
  strip: 'media/strip.json',
});

const CREATED = '2026-05-01T08:00:00.000Z';
const CREATED_SECONDS = mp4Seconds(CREATED, MP4_EPOCH_OFFSET_SECONDS);
const STRIP_CREATED = '2026-05-01T10:00:00.000Z';
const MDAT_PAYLOAD_BYTES = 52_428_800;
const LOCATION_KEY = 'com.apple.quicktime.location.ISO6709';
const MAKE_KEY = 'com.apple.quicktime.make';
const WALK_LIMIT_FREE_BOXES = 70;
const FEW_FREE_BOXES = 10;
const FREE_PAYLOAD_BYTES = 4;
const OVERSIZE_MARGIN_BYTES = 1024;
const LONG_KEY_CHARS = 2_000_000;
const LONG_LOCATION_CHARS = 100;
const DIAGONAL_CELL = 46_341;
const MATRIX_W = 0x4000_0000;
const BOX_HEADER_BYTES = 8;
const LARGE_HEADER_BYTES = 16;
const SMALL_MDAT_BYTES = 1000;
const TRUNCATE_MOOV_BYTES = 20;
const RANDOM_VIDEO_BYTES = 2048;
const RANDOM_VIDEO_SEED = 9;
const YEAR_10000 = 10_000;
const MS_PER_SECOND = 1000;
const FAILING_SIZE = 1000;
const FAILING_MESSAGE = 'disk gone';
const STRIP_MDAT_BYTES = 4096;
const STRIP_MDAT_SEED = 9;
const STRIP_UUID_TOP_BYTES = 40;
const STRIP_UUID_TOP_SEED = 3;
const STRIP_UUID_MOOV_BYTES = 24;
const STRIP_UUID_MOOV_SEED = 5;
const STRIP_MDHD_TAIL_BYTES = 8;
const SAMPLE_POSITIONS = [0, 1000, 2500, 4000];
const HUGE_MDAT_BYTES = 2_147_483_648;
const MOOF_BYTES = 16;
const OUTSIDE_OFFSET = 0xffff_fff0;
const OUTSIDE_MDAT_BYTES = 64;
const WEBM_CLUSTER_BYTES = 64;
const WEBM_CLUSTER_SEED = 4;
const SMALL_CHUNK_BYTES = 1000;
const TOO_MANY_BOXES = 66;
const ISO_TOO_LONG_CHARS = 65;
const SECONDS_ONE = 1;
const HALF_TURN: Rotation = 180;
const THREE_QUARTER_TURN: Rotation = 270;
const TWO_ENTRIES = 2;
const TINY_BOX_SIZE = 3;

const BYTE_SOURCE_SAMPLE = Uint8Array.from([10, 20, 30, 40, 50]);
const PARIS_ISO = '+48.8584+002.2945/';

function moovAfter(seconds: number = CREATED_SECONDS): Uint8Array {
  return moov(mvhd({ version: 0, creationSeconds1904: seconds }));
}

async function videoCase(writer: GoldenWriter, name: string, bytes: Uint8Array): Promise<GoldenCase> {
  const source = await inputRef(writer, `video-${name}`, bytes);
  return asyncFunctionCase(`readVideoMetadata: ${name}`, 'readVideoMetadata', { source }, () =>
    readVideoMetadata(byteSourceFromBytes(bytes)),
  );
}

async function recordedVideoCase(writer: GoldenWriter, name: string, content: Content): Promise<GoldenCase> {
  const source = await inputContentRef(writer, `video-${name}`, content);
  const recording = recordingSource(content);
  const result = await asyncFunctionCase(`readVideoMetadata: ${name}`, 'readVideoMetadata', { source }, () =>
    readVideoMetadata(recording),
  );
  return { ...result, readStats: recording.stats() };
}

function isoCase(name: string, text: string): GoldenCase {
  return functionCase(`parseIso6709: ${name}`, 'parseIso6709', { text }, () => parseIso6709(text));
}

async function isoBmffCases(writer: GoldenWriter): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  const files: [string, Uint8Array][] = [];
  for (const version of [0, 1] as const) {
    files.push([
      `reads the creation time of an mvhd version ${version}`,
      concat(ftyp('isom', ['isom']), moov(mvhd({ version, creationSeconds1904: CREATED_SECONDS }))),
    ]);
  }
  const free = (count: number) => Array.from({ length: count }, () => box('free', new Uint8Array(FREE_PAYLOAD_BYTES)));
  files.push(
    ['treats a creation time of 0 as unknown', concat(ftyp('isom'), moovAfter(0))],
    ['finds moov after an mdat that comes first', concat(ftyp('isom'), box('mdat', new Uint8Array(SMALL_MDAT_BYTES)), moovAfter())],
    [
      'returns empty fields when a box of size 0 extends to the end and no moov was seen',
      concat(ftyp('isom'), Uint8Array.from([0, 0, 0, 0, 0x6d, 0x64, 0x61, 0x74, 1, 2, 3])),
    ],
    ['finds moov within the walk limit', concat(ftyp('isom'), ...free(FEW_FREE_BOXES), moovAfter())],
    ['gives up after the box walk limit', concat(ftyp('isom'), ...free(WALK_LIMIT_FREE_BOXES), moovAfter())],
    ['reads ©xyz without altitude', concat(ftyp('isom'), moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }), udta(xyzBox('+37.3861-122.0839/'))))],
    [
      'reads ©xyz with altitude',
      concat(ftyp('isom'), moov(udta(xyzBox('+37.3861-122.0839+012.0/')), mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }))),
    ],
  );
  for (const fullBox of [true, false]) {
    files.push([
      `reads the Apple location key in keys/ilst (full box: ${fullBox})`,
      concat(
        ftyp('qt  ', ['qt  ']),
        moov(
          mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }),
          appleMeta(
            [
              { key: MAKE_KEY, value: 'Apple' },
              { key: LOCATION_KEY, value: '+48.8584+002.2945+035.000/' },
            ],
            fullBox,
          ),
        ),
      ),
    ]);
  }
  const complete = concat(ftyp('isom'), moovAfter());
  files.push(
    ['ignores Apple metadata without the location key', concat(ftyp('qt  '), moov(appleMeta([{ key: MAKE_KEY, value: 'Apple' }], false)))],
    ['returns no location for ©xyz text that is not ISO 6709', concat(ftyp('isom'), moov(udta(xyzBox('not a location'))))],
    ['flags a moov box that claims more bytes than the file holds', complete.slice(0, complete.length - TRUNCATE_MOOV_BYTES)],
    ['flags a box whose size is smaller than its header', concat(ftyp('isom'), Uint8Array.from([0, 0, 0, 4, 0x66, 0x72, 0x65, 0x65]))],
    ['does not throw on random bytes', seededBytes(RANDOM_VIDEO_BYTES, RANDOM_VIDEO_SEED)],
    ['does not throw on an empty file', new Uint8Array(0)],
    [
      'skips a 2 MB Apple key name without throwing and still finds a later location key',
      concat(
        ftyp('qt  '),
        moov(
          appleMeta(
            [
              { key: 'x'.repeat(LONG_KEY_CHARS), value: 'ignored' },
              { key: LOCATION_KEY, value: PARIS_ISO },
            ],
            false,
          ),
        ),
      ),
    ],
    ['ignores ©xyz text longer than the ISO 6709 limit', concat(ftyp('isom'), moov(udta(xyzBox(`+37.3861-122.0839+${'0'.repeat(LONG_LOCATION_CHARS)}/`))))],
    [
      'ignores Apple location text longer than the ISO 6709 limit',
      concat(ftyp('qt  '), moov(appleMeta([{ key: LOCATION_KEY, value: `+48.8584+002.2945+${'0'.repeat(LONG_LOCATION_CHARS)}/` }], false))),
    ],
    [
      'returns no creation time when the year is above 9999',
      concat(ftyp('isom'), moov(mvhd({ version: 1, creationSeconds1904: Date.UTC(YEAR_10000, 0, 1) / MS_PER_SECOND + MP4_EPOCH_OFFSET_SECONDS }))),
    ],
  );
  for (const rotation of [0, 90, 180, 270] as const) {
    files.push([
      `reads a ${rotation} degree rotation from the video tkhd`,
      concat(ftyp('isom'), moov(trak('vide', tkhd({ version: 0, matrix: rotationMatrix(rotation) })))),
    ]);
  }
  const slanted = [DIAGONAL_CELL, DIAGONAL_CELL, 0, DIAGONAL_CELL, DIAGONAL_CELL, 0, 0, 0, MATRIX_W];
  const ninety: Rotation = 90;
  files.push(
    ['reads the rotation from a version 1 tkhd', concat(ftyp('isom'), moov(trak('vide', tkhd({ version: 1, matrix: rotationMatrix(ninety) }))))],
    [
      'uses the first video track and ignores an audio track before it',
      concat(
        ftyp('isom'),
        moov(
          trak('soun', tkhd({ version: 0, matrix: rotationMatrix(HALF_TURN) })),
          trak('vide', tkhd({ version: 0, matrix: rotationMatrix(ninety) })),
          trak('vide', tkhd({ version: 0, matrix: rotationMatrix(THREE_QUARTER_TURN) })),
        ),
      ),
    ],
    ['treats a matrix that is not a quarter turn as no rotation', concat(ftyp('isom'), moov(trak('vide', tkhd({ version: 0, matrix: slanted }))))],
    ['reports no rotation when there is no video track', concat(ftyp('isom'), moov(trak('soun', tkhd({ version: 0, matrix: rotationMatrix(ninety) }))))],
    ['edge: a creation time of 1 second is 1904-01-01T00:00:01Z', concat(ftyp('isom'), moovAfter(SECONDS_ONE))],
    [
      'edge: the first location wins (©xyz before Apple metadata)',
      concat(ftyp('isom'), moov(udta(xyzBox('+37.3861-122.0839/')), appleMeta([{ key: LOCATION_KEY, value: PARIS_ISO }], true))),
    ],
    ['edge: a moov with size 0 extends to the end of the file', concat(ftyp('isom'), u32be(0), asciiBytes('moov'), mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }))],
    ['edge: a malformed child inside moov flags the file', concat(ftyp('isom'), moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }), Uint8Array.from(u32be(TINY_BOX_SIZE)), asciiBytes('free')))],
    ['edge: a moov without ftyp is still read', moovAfter()],
    ['edge: a video track without tkhd has rotation 0', concat(ftyp('isom'), moov(box('trak', box('mdia', box('hdlr', concat(new Uint8Array(BOX_HEADER_BYTES), asciiBytes('vide')))))))],
    ['edge: a video tkhd cut before the matrix flags the file', concat(ftyp('isom'), moov(trak('vide', box('tkhd', new Uint8Array(BOX_HEADER_BYTES)))))],
    ['edge: ©xyz with an altitude and no trailing slash', concat(ftyp('isom'), moov(udta(xyzBox('-33.8688+151.2093+010'))))],
  );
  for (const [name, bytes] of files) cases.push(await videoCase(writer, name, bytes));

  const head = ftyp('isom', ['isom']);
  const mdatHeader = concat(u32be(MDAT_PAYLOAD_BYTES + BOX_HEADER_BYTES), asciiBytes('mdat'));
  const moovBytes = moovAfter();
  const moovOffset = head.length + BOX_HEADER_BYTES + MDAT_PAYLOAD_BYTES;
  cases.push(
    await recordedVideoCase(
      writer,
      'finds moov after a 50 MiB mdat without reading the mdat',
      placedContent(moovOffset + moovBytes.length, [
        { offset: 0, bytes: head },
        { offset: head.length, bytes: mdatHeader },
        { offset: moovOffset, bytes: moovBytes },
      ]),
    ),
  );
  const shortHead = ftyp('isom');
  const v1Moov = moov(mvhd({ version: 1, creationSeconds1904: CREATED_SECONDS }));
  const largeSize = LARGE_HEADER_BYTES + MDAT_PAYLOAD_BYTES;
  const largeHeader = concat(u32be(1), asciiBytes('mdat'), u64be(BigInt(largeSize)));
  cases.push(
    await recordedVideoCase(
      writer,
      'follows a 64-bit box size',
      placedContent(shortHead.length + largeSize + v1Moov.length, [
        { offset: 0, bytes: shortHead },
        { offset: shortHead.length, bytes: largeHeader },
        { offset: shortHead.length + largeSize, bytes: v1Moov },
      ]),
    ),
  );
  const declaredSize = MOOV_MAX_BYTES + OVERSIZE_MARGIN_BYTES;
  cases.push(
    await recordedVideoCase(
      writer,
      'refuses a moov box larger than the limit without reading it',
      placedContent(shortHead.length + declaredSize, [
        { offset: 0, bytes: shortHead },
        { offset: shortHead.length, bytes: concat(u32be(declaredSize), asciiBytes('moov')) },
      ]),
    ),
  );
  cases.push(
    await asyncFunctionCase(
      'readVideoMetadata: returns malformed instead of throwing when the source fails',
      'readVideoMetadata',
      { source: failingSourceJson(FAILING_SIZE, FAILING_MESSAGE) },
      () => readVideoMetadata(failingSource(FAILING_SIZE, FAILING_MESSAGE)),
    ),
    functionCase('constant: exports the review limits (KEYS_NAME_MAX_BYTES)', 'KEYS_NAME_MAX_BYTES', {}, () => KEYS_NAME_MAX_BYTES),
    functionCase('constant: exports the review limits (ISO6709_MAX_BYTES)', 'ISO6709_MAX_BYTES', {}, () => ISO6709_MAX_BYTES),
  );
  for (const text of ['+37.3861-122.0839/', '+37.3861-122.0839+012.0/', PARIS_ISO, '-33.8688+151.2093/', '+48.8584+002.2945+035.000/']) {
    cases.push(isoCase(`parses ${text}`, text));
  }
  for (const text of ['', 'abc', '+95.0000+002.0000/', '+45.0000+190.0000/', '+45.0000/', '/']) {
    cases.push(isoCase(`refuses "${text}"`, text));
  }
  cases.push(
    isoCase('edge: trailing NULs are removed', '+37.3861-122.0839/\u0000\u0000'),
    isoCase('edge: surrounding whitespace is trimmed', ' \t+37.3861-122.0839/\n'),
    isoCase('edge: no trailing slash', '+37.3861-122.0839'),
    isoCase('edge: integer degrees', '+37-122/'),
    isoCase('edge: the limits 90 and 180 are accepted', '-90.0000+180.0000/'),
    isoCase('edge: a missing sign is refused', '37.3861-122.0839/'),
    isoCase('edge: a three-digit latitude is refused', '+037.3861-122.0839/'),
    isoCase('edge: a trailing CRS suffix is refused', '+37.3861-122.0839CRSWGS_84/'),
    isoCase('edge: 64 characters are allowed', `+37.3861-122.0839+${'0'.repeat(ISO6709_MAX_BYTES - '+37.3861-122.0839+/'.length)}/`),
    isoCase('edge: 65 characters are refused before trimming', `${' '.repeat(ISO_TOO_LONG_CHARS - PARIS_ISO.length)}${PARIS_ISO}`),
    isoCase('edge: a dot without fraction digits is refused', '+37.-122.0839/'),
    isoCase('edge: negative zero latitude', '-00.0000+010.0000/'),
  );
  return cases;
}

type Layout = 'faststart' | 'moovLast';
type VideoOptions = { readonly layout: Layout; readonly version: 0 | 1; readonly table: 'stco' | 'co64' };

function fullBox(type: string, version: number, payload: Uint8Array): Uint8Array {
  return box(type, concat([version, 0, 0, 0], payload));
}

function timedBox(type: string, version: 0 | 1, seconds: number, tailBytes: number): Uint8Array {
  const times =
    version === 0 ? concat(u32be(seconds), u32be(seconds)) : concat(u64be(BigInt(seconds)), u64be(BigInt(seconds)));
  return fullBox(type, version, concat(times, new Uint8Array(tailBytes)));
}

function stco(offsets: readonly number[]): Uint8Array {
  return fullBox('stco', 0, concat(u32be(offsets.length), ...offsets.map((offset) => u32be(offset))));
}

function co64(offsets: readonly number[]): Uint8Array {
  return fullBox('co64', 0, concat(u32be(offsets.length), ...offsets.map((offset) => u64be(BigInt(offset)))));
}

function buildVideo(options: VideoOptions): Uint8Array {
  const seconds = mp4Seconds(STRIP_CREATED, MP4_EPOCH_OFFSET_SECONDS);
  const payload = seededBytes(STRIP_MDAT_BYTES, STRIP_MDAT_SEED);
  const head = ftyp('isom', ['isom', 'mp42']);
  const junk = concat(box('uuid', seededBytes(STRIP_UUID_TOP_BYTES, STRIP_UUID_TOP_SEED)), udta(xyzBox(PARIS_ISO)));
  const buildMoov = (mdatPayloadStart: number) => {
    const offsets = SAMPLE_POSITIONS.map((position) => mdatPayloadStart + position);
    const table = options.table === 'stco' ? stco(offsets) : co64(offsets);
    const track = box(
      'trak',
      concat(
        tkhd({ version: options.version, matrix: rotationMatrix(0) }),
        udta(box('name', asciiBytes('track name'))),
        box('mdia', concat(timedBox('mdhd', options.version, seconds, STRIP_MDHD_TAIL_BYTES), box('minf', box('stbl', table)))),
      ),
    );
    return moov(
      mvhd({ version: options.version, creationSeconds1904: seconds }),
      track,
      udta(xyzBox(PARIS_ISO)),
      appleMeta([{ key: LOCATION_KEY, value: PARIS_ISO }], false),
      box('uuid', seededBytes(STRIP_UUID_MOOV_BYTES, STRIP_UUID_MOOV_SEED)),
    );
  };
  if (options.layout === 'faststart') {
    const provisional = buildMoov(0);
    const start = head.length + junk.length + provisional.length + BOX_HEADER_BYTES;
    return concat(head, junk, buildMoov(start), box('mdat', payload));
  }
  const start = head.length + junk.length + BOX_HEADER_BYTES;
  return concat(head, junk, box('mdat', payload), buildMoov(start));
}

function ebmlElement(id: readonly number[], payload: Uint8Array): Uint8Array {
  return concat(id, [0x01], u64be(BigInt(payload.length)).slice(1), payload);
}

type WebmOptions = { readonly date: Uint8Array | null; readonly unknownSegmentSize?: boolean; readonly clusterFirst?: boolean };

function buildWebm(options: WebmOptions): Uint8Array {
  const header = ebmlElement([0x1a, 0x45, 0xdf, 0xa3], ebmlElement([0x42, 0x82], asciiBytes('webm')));
  const timecode = ebmlElement([0x2a, 0xd7, 0xb1], Uint8Array.from([0x0f, 0x42, 0x40]));
  const date = options.date === null ? new Uint8Array(0) : ebmlElement([0x44, 0x61], options.date);
  const info = ebmlElement([0x15, 0x49, 0xa9, 0x66], concat(timecode, date));
  const cluster = ebmlElement([0x1f, 0x43, 0xb6, 0x75], seededBytes(WEBM_CLUSTER_BYTES, WEBM_CLUSTER_SEED));
  const body = options.clusterFirst === true ? concat(cluster, info) : concat(info, cluster);
  const segment =
    options.unknownSegmentSize === true
      ? concat([0x18, 0x53, 0x80, 0x67, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff], body)
      : ebmlElement([0x18, 0x53, 0x80, 0x67], body);
  return concat(header, segment);
}

function planJson(plan: Pick<StripPlan, 'segments'>): Json {
  return toJson({ segments: plan.segments });
}

async function streamCase(
  writer: GoldenWriter,
  name: string,
  sourceArg: Json,
  content: Content,
  plan: Pick<StripPlan, 'segments'>,
  chunkBytes: number,
  recordReads: boolean,
): Promise<{ readonly goldenCase: GoldenCase; readonly output: Content | null }> {
  const recording = recordingSource(content);
  let output: Content | null = null;
  const result = await outcomeAsync(async () => {
    const builder = new ContentBuilder();
    let chunkCount = 0;
    let largestChunk = 0;
    for await (const chunk of streamStripped(recording, plan, chunkBytes)) {
      builder.bytes(chunk);
      chunkCount += 1;
      largestChunk = Math.max(largestChunk, chunk.length);
    }
    output = builder.build();
    return { output: await writer.content(MEDIA_OUTPUTS, name, output), chunkCount, largestChunk };
  }, (value) => value as Json);
  const goldenCase: GoldenCase = {
    name: `streamStripped: ${name}`,
    fn: 'streamStripped',
    args: { source: sourceArg, plan: planJson(plan), chunkBytes },
    result,
    ...(recordReads ? { readStats: recording.stats() } : {}),
  };
  return { goldenCase, output };
}

async function planCase(
  name: string,
  fn: 'planIsoBmffStrip' | 'planWebmDateStrip',
  sourceArg: Json,
  source: ByteSource,
): Promise<{ readonly goldenCase: GoldenCase; readonly plan: StripPlan | null }> {
  let plan: StripPlan | null = null;
  const run = fn === 'planIsoBmffStrip' ? planIsoBmffStrip : planWebmDateStrip;
  const goldenCase = await asyncFunctionCase(`${fn}: ${name}`, fn, { source: sourceArg }, async () => {
    plan = await run(source);
    return plan;
  });
  return { goldenCase, plan };
}

async function stripPipeline(
  writer: GoldenWriter,
  cases: GoldenCase[],
  fn: 'planIsoBmffStrip' | 'planWebmDateStrip',
  name: string,
  content: Content,
  options: { readonly chunkBytes?: number; readonly recordReads?: boolean; readonly checkOutput?: boolean } = {},
): Promise<void> {
  const sourceArg = await inputContentRef(writer, `strip-${name}`, content);
  const planSource = recordingSource(content);
  const planned = await planCase(name, fn, sourceArg, planSource);
  cases.push(options.recordReads === true ? { ...planned.goldenCase, readStats: planSource.stats() } : planned.goldenCase);
  const plan = planned.plan;
  if (plan === null) return;
  const streamed = await streamCase(
    writer,
    name,
    sourceArg,
    content,
    plan,
    options.chunkBytes ?? STRIP_COPY_CHUNK_BYTES,
    options.recordReads === true,
  );
  cases.push(streamed.goldenCase);
  if (fn !== 'planIsoBmffStrip') return;
  const strippedMoov = plan.moov;
  cases.push(
    functionCase(`moovHasMetadata: ${name}: stripped moov`, 'moovHasMetadata', { moov: hex(strippedMoov) }, () =>
      moovHasMetadata(strippedMoov),
    ),
  );
  if (options.checkOutput === true && streamed.output !== null) {
    const outputContent = streamed.output;
    const outputRef = await writer.content(MEDIA_OUTPUTS, name, outputContent);
    cases.push(
      await asyncFunctionCase(`readVideoMetadata: ${name}: stripped output`, 'readVideoMetadata', { source: outputRef }, () =>
        readVideoMetadata(recordingSource(outputContent)),
      ),
    );
  }
}

function moovOf(bytes: Uint8Array): Uint8Array {
  let position = 0;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  while (position + BOX_HEADER_BYTES <= bytes.length) {
    const size = view.getUint32(position);
    const type = String.fromCharCode(...bytes.subarray(position + FREE_PAYLOAD_BYTES, position + BOX_HEADER_BYTES));
    if (type === 'moov') return bytes.subarray(position, position + size);
    position += size;
  }
  throw new Error('golden: no moov');
}

async function stripCases(writer: GoldenWriter): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  for (const layout of ['faststart', 'moovLast'] as const) {
    for (const version of [0, 1] as const) {
      for (const table of ['stco', 'co64'] as const) {
        const name = `${layout}, version ${version}, ${table}: removes metadata, zeroes times and keeps chunk offsets pointing at the same bytes`;
        const bytes = buildVideo({ layout, version, table });
        await stripPipeline(writer, cases, 'planIsoBmffStrip', name, contentOf(bytes), { checkOutput: true });
        const original = moovOf(bytes);
        cases.push(
          functionCase(`moovHasMetadata: ${name}: original moov`, 'moovHasMetadata', { moov: hex(original) }, () =>
            moovHasMetadata(original),
          ),
        );
      }
    }
  }
  await stripPipeline(
    writer,
    cases,
    'planIsoBmffStrip',
    'rejects fragmented MP4 (moof)',
    contentOf(concat(ftyp('isom'), moovAfter(0), box('moof', new Uint8Array(MOOF_BYTES)), box('mdat', new Uint8Array(MOOF_BYTES)))),
  );
  await stripPipeline(
    writer,
    cases,
    'planIsoBmffStrip',
    'rejects a chunk offset that points outside every mdat',
    contentOf(
      concat(
        ftyp('isom'),
        moov(mvhd({ version: 0, creationSeconds1904: 0 }), box('trak', box('mdia', box('minf', box('stbl', stco([OUTSIDE_OFFSET])))))),
        box('mdat', new Uint8Array(OUTSIDE_MDAT_BYTES)),
      ),
    ),
  );
  const hugeMoov = moov(mvhd({ version: 0, creationSeconds1904: SECONDS_ONE }), udta(xyzBox('+10.0000+010.0000/')));
  const prefix = concat(ftyp('isom'), hugeMoov);
  const hugeHeader = concat(u32be(1), asciiBytes('mdat'), u64be(BigInt(HUGE_MDAT_BYTES)));
  await stripPipeline(
    writer,
    cases,
    'planIsoBmffStrip',
    'streams a 2 GiB mdat in copy-chunk reads without reading it all at once',
    placedContent(prefix.length + HUGE_MDAT_BYTES, [{ offset: 0, bytes: concat(prefix, hugeHeader) }]),
    { recordReads: true },
  );
  const tooManyFree = Array.from({ length: TOO_MANY_BOXES }, () => box('free', new Uint8Array(0)));
  const moovLimitDeclared = MOOV_MAX_BYTES + BOX_HEADER_BYTES;
  const edgeInputs: [string, Content, number?][] = [
    ['edge: no moov', contentOf(concat(ftyp('isom'), box('mdat', new Uint8Array(MOOF_BYTES))))],
    ['edge: two moov boxes', contentOf(concat(ftyp('isom'), moovAfter(), moovAfter()))],
    ['edge: more than 64 top-level boxes', contentOf(concat(ftyp('isom'), ...tooManyFree, moovAfter()))],
    ['edge: a truncated box header', contentOf(concat(ftyp('isom'), moovAfter(), Uint8Array.of(0, 0, 0)))],
    ['edge: a box larger than the file', contentOf(concat(ftyp('isom'), moovAfter(), u32be(LARGE_HEADER_BYTES), asciiBytes('free')))],
    [
      'edge: a moov larger than the limit',
      placedContent(ftyp('isom').length + moovLimitDeclared, [
        { offset: 0, bytes: ftyp('isom') },
        { offset: ftyp('isom').length, bytes: concat(u32be(moovLimitDeclared), asciiBytes('moov')) },
      ]),
    ],
    [
      'edge: a chunk table whose count exceeds its entries',
      contentOf(
        concat(
          ftyp('isom'),
          moov(box('trak', box('mdia', box('minf', box('stbl', fullBox('stco', 0, Uint8Array.from(u32be(TWO_ENTRIES)))))))),
          box('mdat', new Uint8Array(MOOF_BYTES)),
        ),
      ),
    ],
    [
      'edge: unknown top-level boxes are dropped and free, skip and wide are kept',
      contentOf(
        concat(
          ftyp('isom'),
          box('junk', new Uint8Array(FREE_PAYLOAD_BYTES)),
          box('wide', new Uint8Array(0)),
          box('skip', new Uint8Array(FREE_PAYLOAD_BYTES)),
          moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }), udta(xyzBox(PARIS_ISO))),
          box('free', new Uint8Array(FREE_PAYLOAD_BYTES)),
          box('mdat', seededBytes(MOOF_BYTES, STRIP_MDAT_SEED)),
        ),
      ),
    ],
    [
      'edge: an mdat with size 0 extends to the end and is copied in small chunks',
      contentOf(concat(ftyp('isom'), moovAfter(), u32be(0), asciiBytes('mdat'), seededBytes(STRIP_MDAT_BYTES, STRIP_MDAT_SEED))),
      SMALL_CHUNK_BYTES,
    ],
  ];
  for (const [name, content, chunkBytes] of edgeInputs) {
    await stripPipeline(writer, cases, 'planIsoBmffStrip', name, content, chunkBytes === undefined ? {} : { chunkBytes });
  }
  const dateBytes = Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8]);
  const webms: [string, Uint8Array][] = [
    ['overwrites DateUTC with zeros and changes nothing else', buildWebm({ date: dateBytes })],
    ['copies a WebM without DateUTC unchanged', buildWebm({ date: null })],
    ['edge: a Segment of unknown size is entered', buildWebm({ date: dateBytes, unknownSegmentSize: true })],
    ['edge: a DateUTC that is not 8 bytes is left alone', buildWebm({ date: Uint8Array.from([1, 2, 3, 4]) })],
    ['edge: a Cluster before Info stops the search', buildWebm({ date: dateBytes, clusterFirst: true })],
    ['edge: an empty file', new Uint8Array(0)],
    ['edge: a zero first byte stops the search', concat([0], buildWebm({ date: dateBytes }))],
  ];
  for (const [name, bytes] of webms) await stripPipeline(writer, cases, 'planWebmDateStrip', name, contentOf(bytes));
  const plainMoov = moovAfter();
  const xyzMoov = moov(xyzBox(PARIS_ISO));
  const brokenMoov = moov(Uint8Array.from(u32be(TINY_BOX_SIZE)), asciiBytes('free'));
  for (const [name, bytes] of [
    ['edge: a moov with only mvhd', plainMoov],
    ['edge: ©xyz directly inside moov', xyzMoov],
    ['edge: a malformed child throws', brokenMoov],
  ] as const) {
    cases.push(functionCase(`moovHasMetadata: ${name}`, 'moovHasMetadata', { moov: hex(bytes) }, () => moovHasMetadata(bytes)));
  }
  const plain = buildJpeg();
  const exifJpegs: [string, Uint8Array][] = [
    ['finds an APP1 Exif segment and ignores JPEGs without one (without)', plain],
    [
      'finds an APP1 Exif segment and ignores JPEGs without one (with)',
      insertExifIntoJpeg(plain, buildTiff({ dateTimeOriginal: '2026:01:01 00:00:00' })),
    ],
    ['edge: Exif after an APP0', insertExifIntoJpeg(buildJpeg({ segments: [jfifApp0()] }), buildTiff())],
    ['edge: only an XMP APP1', buildJpeg({ segments: [xmpApp1()] })],
    ['edge: bytes that are not a JPEG count as having Exif', seededBytes(RANDOM_VIDEO_BYTES, RANDOM_VIDEO_SEED)],
    ['edge: an empty file counts as having Exif', new Uint8Array(0)],
  ];
  for (const [name, bytes] of exifJpegs) {
    cases.push(
      functionCase(`hasExifSegment: ${name}`, 'hasExifSegment', { bytes: await inputRef(writer, `jpeg-${name}`, bytes) }, () =>
        hasExifSegment(bytes),
      ),
    );
  }
  const sample = hex(BYTE_SOURCE_SAMPLE);
  cases.push(functionCase('byteSourceFromBytes: reports the size', 'byteSourceFromBytes.size', { bytes: sample }, () => byteSourceFromBytes(BYTE_SOURCE_SAMPLE).size));
  const reads: { readonly name: string; readonly offset: number; readonly length: number }[] = [
    { name: 'reads a range', offset: 1, length: 3 },
    { name: 'returns the available bytes when the range crosses the end', offset: 3, length: 10 },
    { name: 'returns nothing at or after the end (at the end)', offset: 5, length: 4 },
    { name: 'returns nothing at or after the end (after the end)', offset: 99, length: 4 },
    { name: 'returns nothing for a zero length', offset: 2, length: 0 },
    { name: 'edge: a negative offset reads from 0', offset: -2, length: 3 },
    { name: 'edge: a negative length reads nothing', offset: 1, length: -3 },
    { name: 'edge: the whole buffer', offset: 0, length: BYTE_SOURCE_SAMPLE.length },
  ];
  for (const { name, offset, length } of reads) {
    cases.push(
      await asyncFunctionCase(`byteSourceFromBytes: ${name}`, 'byteSourceFromBytes.read', { bytes: sample, offset, length }, () =>
        byteSourceFromBytes(BYTE_SOURCE_SAMPLE).read(offset, length),
      ),
    );
  }
  return cases;
}

export async function writeMediaVideoGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATHS.isobmff, await isoBmffCases(writer));
  await writer.functionCases(PATHS.strip, await stripCases(writer));
}
