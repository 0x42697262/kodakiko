import type { GoldenWriter } from './shared.ts';
import { writeMediaImageGoldens } from './media-image.ts';
import { writeMediaVideoGoldens } from './media-video.ts';

export async function writeMediaGoldens(writer: GoldenWriter): Promise<void> {
  await writeMediaImageGoldens(writer);
  await writeMediaVideoGoldens(writer);
}
