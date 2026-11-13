import {
  EXIF_DATE_PATTERN,
  EXIF_OFFSET_PATTERN,
  GPS_EAST,
  GPS_NORTH,
  GPS_SOUTH,
  GPS_WEST,
  LATITUDE_LIMIT,
  LONGITUDE_LIMIT,
  MEDIA_MINUTES_PER_HOUR,
  MEDIA_SECONDS_PER_HOUR,
  ORIENTATION_MAX,
  ORIENTATION_MIN,
  type GeoPoint,
  type ImageMetadata,
} from '@kodakiko/core';
import type { PickerExif } from '../../adapters/media/media-toolkit';
import {
  PICKER_COORDINATE_PARTS,
  PICKER_EXIF_FRACTION_SEPARATOR,
  PICKER_EXIF_PART_SEPARATOR,
} from '../../constants/media-constants';

function matchingString(value: unknown, pattern: RegExp): string | null {
  return typeof value === 'string' && pattern.test(value) ? value : null;
}

function fractionValue(part: string): number {
  const [numerator, denominator] = part.split(PICKER_EXIF_FRACTION_SEPARATOR).map(Number);
  if (numerator === undefined || denominator === undefined || denominator === 0) return Number.NaN;
  return numerator / denominator;
}

function degreesOf(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? Math.abs(value) : null;
  if (typeof value !== 'string') return null;
  const parts = value.split(PICKER_EXIF_PART_SEPARATOR);
  if (parts.length !== PICKER_COORDINATE_PARTS) return null;
  const [degrees = Number.NaN, minutes = Number.NaN, seconds = Number.NaN] = parts.map(fractionValue);
  const total =
    degrees + minutes / MEDIA_MINUTES_PER_HOUR + seconds / MEDIA_SECONDS_PER_HOUR;
  return Number.isFinite(total) ? total : null;
}

function signed(value: unknown, ref: unknown, positive: string, negative: string): number | null {
  const degrees = degreesOf(value);
  if (degrees === null) return null;
  if (ref === positive) return degrees;
  return ref === negative ? 0 - degrees : null;
}

function gpsOf(exif: PickerExif): GeoPoint | null {
  const latitude = signed(exif['GPSLatitude'], exif['GPSLatitudeRef'], GPS_NORTH, GPS_SOUTH);
  const longitude = signed(exif['GPSLongitude'], exif['GPSLongitudeRef'], GPS_EAST, GPS_WEST);
  if (latitude === null || longitude === null) return null;
  if (Math.abs(latitude) > LATITUDE_LIMIT || Math.abs(longitude) > LONGITUDE_LIMIT) return null;
  if (latitude === 0 && longitude === 0) return null;
  return { latitude, longitude };
}

function orientationOf(value: unknown): number | null {
  return typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= ORIENTATION_MIN &&
    value <= ORIENTATION_MAX
    ? value
    : null;
}

export function pickerExifToMetadata(exif: PickerExif): ImageMetadata {
  return {
    dateTimeOriginal: matchingString(exif['DateTimeOriginal'], EXIF_DATE_PATTERN),
    offsetTimeOriginal: matchingString(exif['OffsetTimeOriginal'], EXIF_OFFSET_PATTERN),
    gps: gpsOf(exif),
    orientation: orientationOf(exif['Orientation']),
    malformed: false,
  };
}
