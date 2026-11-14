import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';

type Descriptor = z.infer<typeof EntryMetadataV1>['media'][number];

const EXTENSIONS: Readonly<Record<string, string>> = Object.freeze({ 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'video/mp4': 'mp4', 'video/webm': 'webm', 'audio/mp4': 'm4a', 'audio/webm': 'weba' });
const FALLBACK_EXTENSION = 'bin';

export function fileNameOf(media: Descriptor, position: number): string {
  return media.originalFileName ?? `kodakiko-${position + 1}.${EXTENSIONS[media.mimeType] ?? FALLBACK_EXTENSION}`;
}
