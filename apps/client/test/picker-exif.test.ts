import { describe, expect, it } from 'vitest';
import { pickerExifToMetadata } from '../src/services/media/picker-exif';

const COORDINATE_PRECISION = 5;

describe('pickerExifToMetadata', () => {
  it('returns empty fields for an empty object', () => {
    expect(pickerExifToMetadata({})).toEqual({
      dateTimeOriginal: null,
      offsetTimeOriginal: null,
      gps: null,
      orientation: null,
      malformed: false,
    });
  });

  it('reads date, offset and orientation', () => {
    const result = pickerExifToMetadata({
      DateTimeOriginal: '2026:05:01 10:00:00',
      OffsetTimeOriginal: '+02:00',
      Orientation: 6,
    });
    expect(result.dateTimeOriginal).toBe('2026:05:01 10:00:00');
    expect(result.offsetTimeOriginal).toBe('+02:00');
    expect(result.orientation).toBe(6);
  });

  it('accepts GPS as decimal numbers with reference letters', () => {
    const result = pickerExifToMetadata({
      GPSLatitude: 48.8584,
      GPSLatitudeRef: 'N',
      GPSLongitude: 2.2945,
      GPSLongitudeRef: 'E',
    });
    expect(result.gps?.latitude).toBeCloseTo(48.8584, COORDINATE_PRECISION);
    expect(result.gps?.longitude).toBeCloseTo(2.2945, COORDINATE_PRECISION);
  });

  it('negates southern and western numbers', () => {
    const result = pickerExifToMetadata({
      GPSLatitude: 33.8688,
      GPSLatitudeRef: 'S',
      GPSLongitude: 70.6693,
      GPSLongitudeRef: 'W',
    });
    expect(result.gps?.latitude).toBeCloseTo(-33.8688, COORDINATE_PRECISION);
    expect(result.gps?.longitude).toBeCloseTo(-70.6693, COORDINATE_PRECISION);
  });

  it('accepts GPS as degree, minute, second fraction strings', () => {
    const result = pickerExifToMetadata({
      GPSLatitude: '48/1,51/1,3024/100',
      GPSLatitudeRef: 'N',
      GPSLongitude: '2/1,17/1,4020/100',
      GPSLongitudeRef: 'E',
    });
    expect(result.gps?.latitude).toBeCloseTo(48.8584, COORDINATE_PRECISION);
    expect(result.gps?.longitude).toBeCloseTo(2.2945, COORDINATE_PRECISION);
  });

  it('negates southern and western fraction strings', () => {
    const result = pickerExifToMetadata({
      GPSLatitude: '33/1,52/1,768/100',
      GPSLatitudeRef: 'S',
      GPSLongitude: '70/1,40/1,948/100',
      GPSLongitudeRef: 'W',
    });
    expect(result.gps?.latitude).toBeCloseTo(-33.8688, COORDINATE_PRECISION);
    expect(result.gps?.longitude).toBeCloseTo(-70.6693, COORDINATE_PRECISION);
  });

  it('returns null GPS for values it cannot read', () => {
    expect(
      pickerExifToMetadata({
        GPSLatitude: 'north-ish',
        GPSLatitudeRef: 'N',
        GPSLongitude: '2/1,17/1,4020/100',
        GPSLongitudeRef: 'E',
      }).gps,
    ).toBeNull();
    expect(pickerExifToMetadata({ GPSLatitude: 48.8584, GPSLatitudeRef: 'N' }).gps).toBeNull();
    expect(
      pickerExifToMetadata({
        GPSLatitude: {},
        GPSLatitudeRef: 'N',
        GPSLongitude: [],
        GPSLongitudeRef: 'E',
      }).gps,
    ).toBeNull();
  });

  it('returns null for date, offset and orientation of the wrong shape', () => {
    const result = pickerExifToMetadata({
      DateTimeOriginal: 'yesterday',
      OffsetTimeOriginal: 'later',
      Orientation: 'sideways',
    });
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.offsetTimeOriginal).toBeNull();
    expect(result.orientation).toBeNull();
  });

  it('returns null for values that are not strings or numbers at all', () => {
    const result = pickerExifToMetadata({
      DateTimeOriginal: 20260501,
      OffsetTimeOriginal: null,
      Orientation: { value: 6 },
    });
    expect(result.dateTimeOriginal).toBeNull();
    expect(result.offsetTimeOriginal).toBeNull();
    expect(result.orientation).toBeNull();
  });
});
