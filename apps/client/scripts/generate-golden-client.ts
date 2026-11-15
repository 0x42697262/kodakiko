import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeArchiveGoldens } from './golden-client/archive';
import { writeCalendarGoldens } from './golden-client/calendar';
import { writeGalleryGoldens } from './golden-client/gallery';
import { writeMarkdownGoldens } from './golden-client/markdown';
import { GoldenWriter } from './golden-client/shared';

const GOLDEN_DIRECTORY = fileURLToPath(new URL('../../../tests/fixtures/golden-client/', import.meta.url));

export async function generateClientGoldens(outputDirectory: string): Promise<readonly string[]> {
  const writer = new GoldenWriter(outputDirectory);
  await writeMarkdownGoldens(writer);
  await writeCalendarGoldens(writer);
  await writeGalleryGoldens(writer);
  await writeArchiveGoldens(writer);
  return writer.written;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  const written = await generateClientGoldens(GOLDEN_DIRECTORY);
  process.stdout.write(`${written.length} golden files written to ${GOLDEN_DIRECTORY}\n`);
}
