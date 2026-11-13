import { bytesSchema, decodeBytes } from '@kodakiko/contract';
import type { ByteSource } from '@kodakiko/core';
import {
  MEMORY_PROBE_HEIGHT,
  MEMORY_PROBE_WIDTH,
  MEMORY_THUMBNAIL_BASE64URL,
  MEMORY_THUMBNAIL_HEIGHT,
  MEMORY_THUMBNAIL_WIDTH,
  MEMORY_VIDEO_DURATION_MS,
} from '../../constants/entry-constants';
import { EN } from '../../text/en';
import type { MediaInput, MediaToolkit } from '../media/media-toolkit';

function blobOf(input: MediaInput): Blob {
  if (!('blob' in input.handle)) throw new Error(EN['media.webHandleRequired']);
  return input.handle.blob;
}

export function createMemoryMediaToolkit(): MediaToolkit {
  return {
    byteSource: async (input): Promise<ByteSource> => {
      const blob = blobOf(input);
      return {
        size: blob.size,
        read: async (offset, length) =>
          new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer()),
      };
    },
    probe: async (_input, format) => ({
      width: MEMORY_PROBE_WIDTH,
      height: MEMORY_PROBE_HEIGHT,
      durationMs: format.kind === 'video' ? MEMORY_VIDEO_DURATION_MS : null,
      decodable: true,
    }),
    makeThumbnail: async () => ({
      bytes: decodeBytes(bytesSchema.parse(MEMORY_THUMBNAIL_BASE64URL)),
      width: MEMORY_THUMBNAIL_WIDTH,
      height: MEMORY_THUMBNAIL_HEIGHT,
    }),
    modifiedUtc: async () => null,
    discard: async () => undefined,
  };
}
