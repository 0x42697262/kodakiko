import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTiff, insertExifIntoJpeg } from '../../../packages/core/test/media/builders.ts';

const DIRECTORY = fileURLToPath(new URL('./', import.meta.url));
const BASE_FILE = 'photo-gps-base.jpg';
const OUTPUT_FILE = 'photo-gps.jpg';
const SUMS_FILE = 'SHA256SUMS';
const SUM_SEPARATOR = '  ';
const DATE_TIME_ORIGINAL = '2026:05:01 10:00:00';
const OFFSET_TIME_ORIGINAL = '+02:00';
const LATITUDE = 48.8584;
const LONGITUDE = 2.2945;

async function main(): Promise<void> {
  const base = await readFile(join(DIRECTORY, BASE_FILE));
  const tiff = buildTiff({
    dateTimeOriginal: DATE_TIME_ORIGINAL,
    offsetTimeOriginal: OFFSET_TIME_ORIGINAL,
    gps: { latitude: LATITUDE, longitude: LONGITUDE },
  });
  const output = insertExifIntoJpeg(new Uint8Array(base), tiff);
  await writeFile(join(DIRECTORY, OUTPUT_FILE), output);

  const digest = createHash('sha256').update(output).digest('hex');
  const sums = await readFile(join(DIRECTORY, SUMS_FILE), 'utf8');
  const kept = sums
    .split('\n')
    .filter((line) => line !== '' && !line.endsWith(`${SUM_SEPARATOR}${OUTPUT_FILE}`));
  kept.push(`${digest}${SUM_SEPARATOR}${OUTPUT_FILE}`);
  await writeFile(join(DIRECTORY, SUMS_FILE), `${kept.join('\n')}\n`);
  console.log(`${OUTPUT_FILE} written (${output.length} bytes, sha256 ${digest})`);
}

await main();
