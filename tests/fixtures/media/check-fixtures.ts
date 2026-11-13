import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  byteSourceFromBytes,
  readImageMetadata,
  readVideoMetadata,
} from '../../../packages/core/src/media/index.ts';

const DIRECTORY = fileURLToPath(new URL('./', import.meta.url));
const PHOTO_FILE = 'photo-gps.jpg';
const VIDEO_FILE = 'video-gps.mov';
const EXPECTED_DATE_TIME_ORIGINAL = '2026:05:01 10:00:00';
const EXPECTED_OFFSET_TIME_ORIGINAL = '+02:00';
const EXPECTED_VIDEO_CREATION_UTC = '2026-05-01T08:00:00.000Z';
const EXPECTED_LATITUDE = 48.8584;
const EXPECTED_LONGITUDE = 2.2945;
const COORDINATE_TOLERANCE = 0.00001;

function assertNear(actual: number | undefined, expected: number, label: string): void {
  assert.ok(
    actual !== undefined && Math.abs(actual - expected) <= COORDINATE_TOLERANCE,
    `${label}: expected ${expected}, got ${actual}`,
  );
}

async function checkPhoto(): Promise<void> {
  const bytes = new Uint8Array(await readFile(join(DIRECTORY, PHOTO_FILE)));
  const metadata = await readImageMetadata(byteSourceFromBytes(bytes), 'jpeg');
  assert.equal(metadata.malformed, false, `${PHOTO_FILE} is malformed`);
  assert.equal(metadata.dateTimeOriginal, EXPECTED_DATE_TIME_ORIGINAL);
  assert.equal(metadata.offsetTimeOriginal, EXPECTED_OFFSET_TIME_ORIGINAL);
  assertNear(metadata.gps?.latitude, EXPECTED_LATITUDE, `${PHOTO_FILE} latitude`);
  assertNear(metadata.gps?.longitude, EXPECTED_LONGITUDE, `${PHOTO_FILE} longitude`);
  console.log(`${PHOTO_FILE}: date, offset and GPS found`);
}

async function checkVideo(): Promise<void> {
  const bytes = new Uint8Array(await readFile(join(DIRECTORY, VIDEO_FILE)));
  const metadata = await readVideoMetadata(byteSourceFromBytes(bytes));
  assert.equal(metadata.malformed, false, `${VIDEO_FILE} is malformed`);
  assert.equal(metadata.creationUtc, EXPECTED_VIDEO_CREATION_UTC);
  assertNear(metadata.location?.latitude, EXPECTED_LATITUDE, `${VIDEO_FILE} latitude`);
  assertNear(metadata.location?.longitude, EXPECTED_LONGITUDE, `${VIDEO_FILE} longitude`);
  console.log(`${VIDEO_FILE}: creation time and location found`);
}

await checkPhoto();
await checkVideo();
