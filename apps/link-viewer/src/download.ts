import { DOWNLOAD_MAX_BYTES, MEMORY_PLAYBACK_MAX_BYTES } from './constants.ts';
import { BINARY_EXTENSION, DOWNLOAD_PREFIX, PHOTO_EXTENSION, VIDEO_EXTENSIONS } from './keys.ts';

export type DownloadDescriptor = {
  readonly kind: 'photo' | 'video';
  readonly mimeType: string;
  readonly originalPlaintextBytes: number;
};

export type PlaybackMode = 'stream' | 'memory' | 'unsupported';

function extension(descriptor: DownloadDescriptor): string {
  if (descriptor.kind === 'photo') return PHOTO_EXTENSION;
  return Object.hasOwn(VIDEO_EXTENSIONS, descriptor.mimeType)
    ? (VIDEO_EXTENSIONS[descriptor.mimeType] ?? BINARY_EXTENSION)
    : BINARY_EXTENSION;
}

export function downloadName(index: number, descriptor: DownloadDescriptor): string {
  return `${DOWNLOAD_PREFIX}${index + 1}.${extension(descriptor)}`;
}

export function canDownload(descriptor: DownloadDescriptor): boolean {
  return descriptor.kind === 'photo' || descriptor.originalPlaintextBytes <= DOWNLOAD_MAX_BYTES;
}

export function playbackMode(
  serviceWorkerAvailable: boolean,
  plaintextBytes: number,
): PlaybackMode {
  if (serviceWorkerAvailable) return 'stream';
  return plaintextBytes <= MEMORY_PLAYBACK_MAX_BYTES ? 'memory' : 'unsupported';
}
