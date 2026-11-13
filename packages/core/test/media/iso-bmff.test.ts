import { describe, expect, it } from 'vitest';
import {
  BOX_WALK_MAX_BOXES,
  ISO6709_MAX_BYTES,
  KEYS_NAME_MAX_BYTES,
  MOOV_MAX_BYTES,
  MP4_EPOCH_OFFSET_SECONDS,
  byteSourceFromBytes,
  parseIso6709,
  readVideoMetadata,
} from '../../src/index.ts';
import {
  appleMeta,
  asciiBytes,
  box,
  concat,
  ftyp,
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
  virtualSource,
  xyzBox,
} from './builders.ts';

const CREATED = '2026-05-01T08:00:00.000Z';
const CREATED_SECONDS = mp4Seconds(CREATED, MP4_EPOCH_OFFSET_SECONDS);
const COORDINATE_PRECISION = 4;
const MDAT_PAYLOAD_BYTES = 52_428_800;
const LOCATION_KEY = 'com.apple.quicktime.location.ISO6709';
const MAKE_KEY = 'com.apple.quicktime.make';
const HEADER_READ_BYTES = 16;
const WALK_LIMIT_FREE_BOXES = 70;
const FEW_FREE_BOXES = 10;
const READ_BUDGET_BYTES = 4096;
const OVERSIZE_MARGIN_BYTES = 1024;
const LONG_KEY_CHARS = 2_000_000;
const LONG_LOCATION_CHARS = 100;
const DIAGONAL_CELL = 46_341;

function file(...parts: readonly Uint8Array[]): Uint8Array {
  return concat(...parts);
}

async function metadataOf(bytes: Uint8Array) {
  return readVideoMetadata(byteSourceFromBytes(bytes));
}

function expectPoint(
  point: { latitude: number; longitude: number } | null,
  latitude: number,
  longitude: number,
): void {
  expect(point).not.toBeNull();
  expect(point?.latitude).toBeCloseTo(latitude, COORDINATE_PRECISION);
  expect(point?.longitude).toBeCloseTo(longitude, COORDINATE_PRECISION);
}

describe('readVideoMetadata creation time', () => {
  it.each([0, 1] as const)('reads the creation time of an mvhd version %i', async (version) => {
    const bytes = file(
      ftyp('isom', ['isom']),
      moov(mvhd({ version, creationSeconds1904: CREATED_SECONDS })),
    );
    expect(await metadataOf(bytes)).toEqual({
      creationUtc: CREATED,
      location: null,
      rotationDegrees: 0,
      malformed: false,
    });
  });

  it('treats a creation time of 0 as unknown', async () => {
    const bytes = file(ftyp('isom'), moov(mvhd({ version: 0, creationSeconds1904: 0 })));
    expect((await metadataOf(bytes)).creationUtc).toBeNull();
  });

  it('finds moov after an mdat that comes first', async () => {
    const bytes = file(
      ftyp('isom'),
      box('mdat', new Uint8Array(1000)),
      moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS })),
    );
    expect((await metadataOf(bytes)).creationUtc).toBe(CREATED);
  });

  it('finds moov after a 50 MiB mdat without reading the mdat', async () => {
    const head = ftyp('isom', ['isom']);
    const mdatHeader = concat(u32be(MDAT_PAYLOAD_BYTES + 8), asciiBytes('mdat'));
    const moovBytes = moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }));
    const moovOffset = head.length + 8 + MDAT_PAYLOAD_BYTES;
    const source = virtualSource(moovOffset + moovBytes.length, [
      { offset: 0, bytes: head },
      { offset: head.length, bytes: mdatHeader },
      { offset: moovOffset, bytes: moovBytes },
    ]);
    const result = await readVideoMetadata(source);
    expect(result.creationUtc).toBe(CREATED);
    expect(source.bytesRequested()).toBeLessThan(READ_BUDGET_BYTES);
    const largest = Math.max(...source.reads.map((record) => record.length));
    expect(largest).toBeLessThanOrEqual(Math.max(HEADER_READ_BYTES, moovBytes.length));
  });

  it('follows a 64-bit box size', async () => {
    const head = ftyp('isom');
    const moovBytes = moov(mvhd({ version: 1, creationSeconds1904: CREATED_SECONDS }));
    const largeSize = 16 + MDAT_PAYLOAD_BYTES;
    const largeHeader = concat(u32be(1), asciiBytes('mdat'), u64be(BigInt(largeSize)));
    const moovOffset = head.length + largeSize;
    const source = virtualSource(moovOffset + moovBytes.length, [
      { offset: 0, bytes: head },
      { offset: head.length, bytes: largeHeader },
      { offset: moovOffset, bytes: moovBytes },
    ]);
    expect((await readVideoMetadata(source)).creationUtc).toBe(CREATED);
  });

  it('returns empty fields when a box of size 0 extends to the end and no moov was seen', async () => {
    const bytes = file(ftyp('isom'), Uint8Array.from([0, 0, 0, 0, 0x6d, 0x64, 0x61, 0x74, 1, 2, 3]));
    const result = await metadataOf(bytes);
    expect(result.creationUtc).toBeNull();
    expect(result.location).toBeNull();
  });

  it('finds moov within the walk limit', async () => {
    const free = Array.from({ length: FEW_FREE_BOXES }, () => box('free', new Uint8Array(4)));
    const bytes = file(
      ftyp('isom'),
      ...free,
      moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS })),
    );
    expect((await metadataOf(bytes)).creationUtc).toBe(CREATED);
  });

  it('gives up after the box walk limit', async () => {
    expect(WALK_LIMIT_FREE_BOXES).toBeGreaterThan(BOX_WALK_MAX_BOXES);
    const free = Array.from({ length: WALK_LIMIT_FREE_BOXES }, () => box('free', new Uint8Array(4)));
    const bytes = file(
      ftyp('isom'),
      ...free,
      moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS })),
    );
    const result = await metadataOf(bytes);
    expect(result.creationUtc).toBeNull();
    expect(result.location).toBeNull();
  });
});

describe('readVideoMetadata location', () => {
  it('reads ©xyz without altitude', async () => {
    const bytes = file(
      ftyp('isom'),
      moov(
        mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS }),
        udta(xyzBox('+37.3861-122.0839/')),
      ),
    );
    const result = await metadataOf(bytes);
    expectPoint(result.location, 37.3861, -122.0839);
    expect(result.malformed).toBe(false);
  });

  it('reads ©xyz with altitude', async () => {
    const bytes = file(
      ftyp('isom'),
      moov(udta(xyzBox('+37.3861-122.0839+012.0/')), mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS })),
    );
    const result = await metadataOf(bytes);
    expectPoint(result.location, 37.3861, -122.0839);
    expect(result.creationUtc).toBe(CREATED);
  });

  it.each([true, false])('reads the Apple location key in keys/ilst (full box: %s)', async (fullBox) => {
    const bytes = file(
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
    );
    const result = await metadataOf(bytes);
    expectPoint(result.location, 48.8584, 2.2945);
    expect(result.creationUtc).toBe(CREATED);
  });

  it('ignores Apple metadata without the location key', async () => {
    const bytes = file(
      ftyp('qt  '),
      moov(appleMeta([{ key: MAKE_KEY, value: 'Apple' }], false)),
    );
    expect((await metadataOf(bytes)).location).toBeNull();
  });

  it('returns no location for ©xyz text that is not ISO 6709', async () => {
    const bytes = file(ftyp('isom'), moov(udta(xyzBox('not a location'))));
    expect((await metadataOf(bytes)).location).toBeNull();
  });
});

describe('readVideoMetadata malformed input', () => {
  it('refuses a moov box larger than the limit without reading it', async () => {
    const head = ftyp('isom');
    const declaredSize = MOOV_MAX_BYTES + OVERSIZE_MARGIN_BYTES;
    const moovHeader = concat(u32be(declaredSize), asciiBytes('moov'));
    const source = virtualSource(head.length + declaredSize, [
      { offset: 0, bytes: head },
      { offset: head.length, bytes: moovHeader },
    ]);
    const result = await readVideoMetadata(source);
    expect(result).toEqual({
      creationUtc: null,
      location: null,
      rotationDegrees: 0,
      malformed: true,
    });
    const largest = Math.max(...source.reads.map((record) => record.length));
    expect(largest).toBeLessThanOrEqual(HEADER_READ_BYTES);
  });

  it('flags a moov box that claims more bytes than the file holds', async () => {
    const bytes = file(ftyp('isom'), moov(mvhd({ version: 0, creationSeconds1904: CREATED_SECONDS })));
    const result = await metadataOf(bytes.slice(0, bytes.length - 20));
    expect(result.malformed).toBe(true);
    expect(result.creationUtc).toBeNull();
  });

  it('flags a box whose size is smaller than its header', async () => {
    const bytes = file(ftyp('isom'), Uint8Array.from([0, 0, 0, 4, 0x66, 0x72, 0x65, 0x65]));
    expect((await metadataOf(bytes)).malformed).toBe(true);
  });

  it('does not throw on random bytes', async () => {
    const result = await metadataOf(seededBytes(2048, 9));
    expect(result.creationUtc).toBeNull();
    expect(result.location).toBeNull();
  });

  it('does not throw on an empty file', async () => {
    const result = await metadataOf(new Uint8Array(0));
    expect(result.creationUtc).toBeNull();
    expect(result.location).toBeNull();
  });
});

describe('readVideoMetadata review hardening', () => {
  it('exports the review limits', () => {
    expect(KEYS_NAME_MAX_BYTES).toBe(256);
    expect(ISO6709_MAX_BYTES).toBe(64);
  });

  it('skips a 2 MB Apple key name without throwing and still finds a later location key', async () => {
    const bytes = file(
      ftyp('qt  '),
      moov(
        appleMeta(
          [
            { key: 'x'.repeat(LONG_KEY_CHARS), value: 'ignored' },
            { key: LOCATION_KEY, value: '+48.8584+002.2945/' },
          ],
          false,
        ),
      ),
    );

    const result = await metadataOf(bytes);

    expectPoint(result.location, 48.8584, 2.2945);
  });

  it('ignores ©xyz text longer than the ISO 6709 limit', async () => {
    const text = `+37.3861-122.0839+${'0'.repeat(LONG_LOCATION_CHARS)}/`;
    const bytes = file(ftyp('isom'), moov(udta(xyzBox(text))));

    expect((await metadataOf(bytes)).location).toBeNull();
  });

  it('ignores Apple location text longer than the ISO 6709 limit', async () => {
    const bytes = file(
      ftyp('qt  '),
      moov(
        appleMeta(
          [{ key: LOCATION_KEY, value: `+48.8584+002.2945+${'0'.repeat(LONG_LOCATION_CHARS)}/` }],
          false,
        ),
      ),
    );

    expect((await metadataOf(bytes)).location).toBeNull();
  });

  it('returns malformed instead of throwing when the source fails', async () => {
    const failing = {
      size: 1000,
      read: async () => {
        throw new Error('disk gone');
      },
    };

    const result = await readVideoMetadata(failing);

    expect(result).toEqual({
      creationUtc: null,
      location: null,
      rotationDegrees: 0,
      malformed: true,
    });
  });

  it('returns no creation time when the year is above 9999', async () => {
    const seconds = Date.UTC(10_000, 0, 1) / 1000 + MP4_EPOCH_OFFSET_SECONDS;
    const bytes = file(ftyp('isom'), moov(mvhd({ version: 1, creationSeconds1904: seconds })));

    const result = await metadataOf(bytes);

    expect(result.creationUtc).toBeNull();
  });
});

describe('readVideoMetadata rotation', () => {
  it.each([0, 90, 180, 270] as const)('reads a %i degree rotation from the video tkhd', async (rotation) => {
    const bytes = file(
      ftyp('isom'),
      moov(trak('vide', tkhd({ version: 0, matrix: rotationMatrix(rotation) }))),
    );

    expect((await metadataOf(bytes)).rotationDegrees).toBe(rotation);
  });

  it('reads the rotation from a version 1 tkhd', async () => {
    const bytes = file(
      ftyp('isom'),
      moov(trak('vide', tkhd({ version: 1, matrix: rotationMatrix(90) }))),
    );

    expect((await metadataOf(bytes)).rotationDegrees).toBe(90);
  });

  it('uses the first video track and ignores an audio track before it', async () => {
    const bytes = file(
      ftyp('isom'),
      moov(
        trak('soun', tkhd({ version: 0, matrix: rotationMatrix(180) })),
        trak('vide', tkhd({ version: 0, matrix: rotationMatrix(90) })),
        trak('vide', tkhd({ version: 0, matrix: rotationMatrix(270) })),
      ),
    );

    expect((await metadataOf(bytes)).rotationDegrees).toBe(90);
  });

  it('treats a matrix that is not a quarter turn as no rotation', async () => {
    const slanted = [DIAGONAL_CELL, DIAGONAL_CELL, 0, DIAGONAL_CELL, DIAGONAL_CELL, 0, 0, 0, 0x4000_0000];
    const bytes = file(ftyp('isom'), moov(trak('vide', tkhd({ version: 0, matrix: slanted }))));

    expect((await metadataOf(bytes)).rotationDegrees).toBe(0);
  });

  it('reports no rotation when there is no video track', async () => {
    const bytes = file(
      ftyp('isom'),
      moov(trak('soun', tkhd({ version: 0, matrix: rotationMatrix(90) }))),
    );

    expect((await metadataOf(bytes)).rotationDegrees).toBe(0);
  });
});

describe('parseIso6709', () => {
  it.each([
    ['+37.3861-122.0839/', 37.3861, -122.0839],
    ['+37.3861-122.0839+012.0/', 37.3861, -122.0839],
    ['+48.8584+002.2945/', 48.8584, 2.2945],
    ['-33.8688+151.2093/', -33.8688, 151.2093],
    ['+48.8584+002.2945+035.000/', 48.8584, 2.2945],
  ])('parses %s', (text, latitude, longitude) => {
    expectPoint(parseIso6709(text), latitude, longitude);
  });

  it.each(['', 'abc', '+95.0000+002.0000/', '+45.0000+190.0000/', '+45.0000/', '/'])(
    'refuses "%s"',
    (text) => {
      expect(parseIso6709(text)).toBeNull();
    },
  );
});
