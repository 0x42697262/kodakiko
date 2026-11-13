import { describe, expect, it } from 'vitest';
import {
  EXIF_ASCII_MAX_BYTES,
  EXIF_MAX_IFD_ENTRIES,
  EXIF_SCAN_MAX_BYTES,
  byteSourceFromBytes,
  parseTiffExif,
  readImageMetadata,
} from '../../src/index.ts';
import {
  buildJpeg,
  buildPng,
  buildTiff,
  buildWebp,
  exifApp1,
  fillerApp2,
  jfifApp0,
  recordingSource,
  seededBytes,
  xmpApp1,
  type Endian,
} from './builders.ts';

const DATE = '2026:05:01 10:00:00';
const OFFSET = '+02:00';
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const SANTIAGO = { latitude: -33.8688, longitude: -70.6693 };
const COORDINATE_PRECISION = 5;
const ORIENTATION_ROTATE_90 = 6;
const ENDIANS: readonly Endian[] = ['little', 'big'];
const IFD0_COUNT_OFFSET = 8;
const OVERSIZED_IFD_COUNT = 600;
const HUGE_ASCII_CHARS = 200_000;

function fullTiff(endian: Endian = 'little'): Uint8Array {
  return buildTiff({
    endian,
    dateTimeOriginal: DATE,
    offsetTimeOriginal: OFFSET,
    gps: PARIS,
    orientation: ORIENTATION_ROTATE_90,
  });
}

function expectParis(gps: { latitude: number; longitude: number } | null): void {
  expect(gps).not.toBeNull();
  expect(gps?.latitude).toBeCloseTo(PARIS.latitude, COORDINATE_PRECISION);
  expect(gps?.longitude).toBeCloseTo(PARIS.longitude, COORDINATE_PRECISION);
}

describe('parseTiffExif', () => {
  it.each(ENDIANS)('reads date, offset, GPS and orientation (%s-endian)', (endian) => {
    const result = parseTiffExif(fullTiff(endian));
    expect(result.dateTimeOriginal).toBe(DATE);
    expect(result.offsetTimeOriginal).toBe(OFFSET);
    expect(result.orientation).toBe(ORIENTATION_ROTATE_90);
    expect(result.malformed).toBe(false);
    expectParis(result.gps);
  });

  it.each(ENDIANS)('negates southern and western coordinates (%s-endian)', (endian) => {
    const result = parseTiffExif(buildTiff({ endian, gps: SANTIAGO }));
    expect(result.gps?.latitude).toBeCloseTo(SANTIAGO.latitude, COORDINATE_PRECISION);
    expect(result.gps?.longitude).toBeCloseTo(SANTIAGO.longitude, COORDINATE_PRECISION);
  });

  it('returns empty fields for a TIFF block without tags', () => {
    expect(parseTiffExif(buildTiff())).toEqual({
      dateTimeOriginal: null,
      offsetTimeOriginal: null,
      gps: null,
      orientation: null,
      malformed: false,
    });
  });

  it('rejects the exact 0,0 point and keeps the other fields', () => {
    const result = parseTiffExif(
      buildTiff({ dateTimeOriginal: DATE, gps: { latitude: 0, longitude: 0 } }),
    );
    expect(result.gps).toBeNull();
    expect(result.dateTimeOriginal).toBe(DATE);
  });

  it('rejects latitudes beyond 90 degrees', () => {
    expect(parseTiffExif(buildTiff({ gps: { latitude: 91, longitude: 10 } })).gps).toBeNull();
    expect(parseTiffExif(buildTiff({ gps: { latitude: -91, longitude: 10 } })).gps).toBeNull();
  });

  it('rejects longitudes beyond 180 degrees', () => {
    expect(parseTiffExif(buildTiff({ gps: { latitude: 10, longitude: 181 } })).gps).toBeNull();
    expect(parseTiffExif(buildTiff({ gps: { latitude: 10, longitude: -181 } })).gps).toBeNull();
  });

  it('turns a date that does not match YYYY:MM:DD HH:MM:SS into null', () => {
    expect(parseTiffExif(buildTiff({ dateTimeOriginal: '2026-05-01 10:00:00' })).dateTimeOriginal).toBeNull();
    expect(parseTiffExif(buildTiff({ dateTimeOriginal: '    :  :     :  :  ' })).dateTimeOriginal).toBeNull();
    expect(parseTiffExif(buildTiff({ dateTimeOriginal: 'yesterday' })).dateTimeOriginal).toBeNull();
  });

  it('turns an offset that does not match the sign-hours-minutes format into null', () => {
    expect(parseTiffExif(buildTiff({ offsetTimeOriginal: 'later' })).offsetTimeOriginal).toBeNull();
    expect(parseTiffExif(buildTiff({ offsetTimeOriginal: '+2' })).offsetTimeOriginal).toBeNull();
  });

  it('keeps a valid negative offset', () => {
    expect(parseTiffExif(buildTiff({ offsetTimeOriginal: '-03:30' })).offsetTimeOriginal).toBe('-03:30');
  });

  it('flags a truncated block as malformed without throwing', () => {
    const tiff = fullTiff();
    for (const length of [0, 4, 12, tiff.length - 10]) {
      const result = parseTiffExif(tiff.slice(0, length));
      expect(result.malformed).toBe(true);
    }
  });

  it('flags a wrong byte order marker or magic number as malformed', () => {
    const wrongOrder = fullTiff();
    wrongOrder[0] = 0x58;
    wrongOrder[1] = 0x58;
    expect(parseTiffExif(wrongOrder).malformed).toBe(true);
    const wrongMagic = fullTiff();
    wrongMagic[2] = 43;
    expect(parseTiffExif(wrongMagic).malformed).toBe(true);
  });

  it('flags an IFD that claims more entries than the data holds as malformed', () => {
    expect(OVERSIZED_IFD_COUNT).toBeGreaterThan(EXIF_MAX_IFD_ENTRIES);
    const tiff = buildTiff({ orientation: ORIENTATION_ROTATE_90 });
    tiff[IFD0_COUNT_OFFSET] = OVERSIZED_IFD_COUNT & 0xff;
    tiff[IFD0_COUNT_OFFSET + 1] = OVERSIZED_IFD_COUNT >> 8;
    expect(parseTiffExif(tiff).malformed).toBe(true);
  });

  it('terminates when the sub-IFD pointers point back at IFD0', () => {
    const result = parseTiffExif(
      buildTiff({ orientation: ORIENTATION_ROTATE_90, selfReferencingPointers: true }),
    );
    expect(result.orientation).toBe(ORIENTATION_ROTATE_90);
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.gps).toBeNull();
  });
});

describe('EXIF reader hardening', () => {
  it('exports the ASCII limit', () => {
    expect(EXIF_ASCII_MAX_BYTES).toBe(64);
  });

  it('returns null for an ASCII value of 200,000 bytes without throwing', () => {
    const tiff = buildTiff({
      orientation: ORIENTATION_ROTATE_90,
      dateTimeOriginal: 'x'.repeat(HUGE_ASCII_CHARS),
    });

    const result = parseTiffExif(tiff);

    expect(result.dateTimeOriginal).toBeNull();
    expect(result.orientation).toBe(ORIENTATION_ROTATE_90);
    expect(result.malformed).toBe(false);
  });

  it('returns malformed instead of throwing when the source fails', async () => {
    const failing = {
      size: 1000,
      read: async () => {
        throw new Error('disk gone');
      },
    };

    for (const container of ['jpeg', 'png', 'webp'] as const) {
      const result = await readImageMetadata(failing, container);
      expect(result.malformed).toBe(true);
      expect(result.dateTimeOriginal).toBeNull();
    }
  });
});

describe('readImageMetadata for JPEG', () => {
  it('finds the Exif APP1 after an APP0', async () => {
    const jpeg = buildJpeg({ segments: [jfifApp0(), exifApp1(fullTiff())], scanBytes: 64 });
    const result = await readImageMetadata(byteSourceFromBytes(jpeg), 'jpeg');
    expect(result.dateTimeOriginal).toBe(DATE);
    expect(result.offsetTimeOriginal).toBe(OFFSET);
    expect(result.orientation).toBe(ORIENTATION_ROTATE_90);
    expect(result.malformed).toBe(false);
    expectParis(result.gps);
  });

  it('skips an XMP APP1 that comes before the Exif APP1', async () => {
    const jpeg = buildJpeg({ segments: [xmpApp1(), exifApp1(fullTiff('big'))] });
    const result = await readImageMetadata(byteSourceFromBytes(jpeg), 'jpeg');
    expect(result.dateTimeOriginal).toBe(DATE);
  });

  it('returns empty fields for a JPEG without EXIF', async () => {
    const jpeg = buildJpeg({ segments: [jfifApp0()], scanBytes: 32 });
    expect(await readImageMetadata(byteSourceFromBytes(jpeg), 'jpeg')).toEqual({
      dateTimeOriginal: null,
      offsetTimeOriginal: null,
      gps: null,
      orientation: null,
      malformed: false,
    });
  });

  it('stops at the start of scan and ignores an APP1 after it', async () => {
    const jpeg = buildJpeg({ segments: [jfifApp0()], scanBytes: 16, afterScan: [exifApp1(fullTiff())] });
    const result = await readImageMetadata(byteSourceFromBytes(jpeg), 'jpeg');
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.gps).toBeNull();
  });

  it('flags a JPEG cut inside the Exif segment as malformed', async () => {
    const jpeg = buildJpeg({ segments: [jfifApp0(), exifApp1(fullTiff())] });
    const result = await readImageMetadata(byteSourceFromBytes(jpeg.slice(0, 60)), 'jpeg');
    expect(result.malformed).toBe(true);
    expect(result.dateTimeOriginal).toBeNull();
  });

  it('flags bytes that are not a JPEG as malformed without throwing', async () => {
    const result = await readImageMetadata(byteSourceFromBytes(seededBytes(512, 3)), 'jpeg');
    expect(result.malformed).toBe(true);
    expect(result.dateTimeOriginal).toBeNull();
  });

  it('never reads beyond the scan limit and misses an Exif segment past it', async () => {
    const fillerCount = 5;
    const jpeg = buildJpeg({
      segments: [...Array.from({ length: fillerCount }, () => fillerApp2(65_000)), exifApp1(fullTiff())],
    });
    expect(jpeg.length).toBeGreaterThan(EXIF_SCAN_MAX_BYTES);
    const source = recordingSource(jpeg);
    const result = await readImageMetadata(source, 'jpeg');
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.gps).toBeNull();
    expect(source.furthestOffset()).toBeLessThanOrEqual(EXIF_SCAN_MAX_BYTES);
  });
});

describe('readImageMetadata for PNG', () => {
  it('reads the eXIf chunk', async () => {
    const png = buildPng({ exifTiff: fullTiff() });
    const result = await readImageMetadata(byteSourceFromBytes(png), 'png');
    expect(result.dateTimeOriginal).toBe(DATE);
    expect(result.malformed).toBe(false);
    expectParis(result.gps);
  });

  it('returns empty fields for a PNG without eXIf', async () => {
    const result = await readImageMetadata(byteSourceFromBytes(buildPng()), 'png');
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.malformed).toBe(false);
  });

  it('flags a PNG cut inside the eXIf chunk as malformed', async () => {
    const png = buildPng({ exifTiff: fullTiff() });
    const result = await readImageMetadata(byteSourceFromBytes(png.slice(0, 60)), 'png');
    expect(result.malformed).toBe(true);
  });
});

describe('readImageMetadata for WebP', () => {
  it('reads the EXIF chunk', async () => {
    const webp = buildWebp({ exifTiff: fullTiff() });
    const result = await readImageMetadata(byteSourceFromBytes(webp), 'webp');
    expect(result.dateTimeOriginal).toBe(DATE);
    expect(result.orientation).toBe(ORIENTATION_ROTATE_90);
    expectParis(result.gps);
  });

  it('skips a leading Exif identifier inside the EXIF chunk', async () => {
    const webp = buildWebp({ exifTiff: fullTiff('big'), exifPrefix: true });
    const result = await readImageMetadata(byteSourceFromBytes(webp), 'webp');
    expect(result.dateTimeOriginal).toBe(DATE);
  });

  it('returns empty fields for a WebP without EXIF', async () => {
    const result = await readImageMetadata(byteSourceFromBytes(buildWebp()), 'webp');
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.malformed).toBe(false);
  });
});
