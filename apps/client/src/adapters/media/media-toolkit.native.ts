import { File, FileMode, type FileHandle } from 'expo-file-system';
import { Image } from 'expo-image';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { createVideoPlayer } from 'expo-video';
import { getThumbnailAsync } from 'expo-video-thumbnails';
import { fitWithin } from '@kodakiko/core';
import {
  MEDIA_PROBE_TIMEOUT_MS,
  MILLISECONDS_PER_SECOND,
  THUMBNAIL_MAX_EDGE,
  THUMBNAIL_QUALITY,
  THUMBNAIL_QUALITY_SCALE,
} from '../../constants/media-constants';
import { VIDEO_PLAYER_ERROR_STATUS, VIDEO_PLAYER_EVENTS } from '../../keys/media-keys';
import { EN } from '../../text/en';
import type {
  MediaInput,
  MediaToolkit,
  ProbeResult,
  ThumbnailResult,
} from './media-toolkit';

const UNDECODABLE: ProbeResult = { width: 0, height: 0, durationMs: null, decodable: false };

type SourceLoad = {
  readonly duration: number;
  readonly availableVideoTracks: readonly { readonly size: { readonly width: number; readonly height: number } }[];
};

function uriOf(input: MediaInput): string {
  if ('uri' in input.handle) return input.handle.uri;
  throw new Error(EN['media.nativeHandleRequired']);
}

function deleteIfExists(uri: string): void {
  const file = new File(uri);
  if (file.exists) file.delete();
}

async function probeVideo(uri: string): Promise<ProbeResult> {
  const player = createVideoPlayer(uri);
  try {
    const loaded = await new Promise<SourceLoad>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(EN['media.probeTimeout'])), MEDIA_PROBE_TIMEOUT_MS);
      const loadSubscription = player.addListener(VIDEO_PLAYER_EVENTS.sourceLoad, (payload) => {
        clearTimeout(timer);
        loadSubscription.remove();
        statusSubscription.remove();
        resolve(payload as SourceLoad);
      });
      const statusSubscription = player.addListener(
        VIDEO_PLAYER_EVENTS.statusChange,
        (payload: { readonly status: string }) => {
          if (payload.status !== VIDEO_PLAYER_ERROR_STATUS) return;
          clearTimeout(timer);
          loadSubscription.remove();
          statusSubscription.remove();
          reject(new Error(EN['media.videoUnreadable']));
        },
      );
    });
    const size = loaded.availableVideoTracks[0]?.size;
    if (size === undefined) throw new Error(EN['media.videoMetadataMissing']);
    return {
      width: size.width,
      height: size.height,
      durationMs: Math.round(loaded.duration * MILLISECONDS_PER_SECOND),
      decodable: true,
    };
  } finally {
    player.release();
  }
}

async function encodeThumbnail(
  uri: string,
  width: number,
  height: number,
): Promise<ThumbnailResult> {
  const context = ImageManipulator.manipulate(uri);
  context.resize(fitWithin(width, height, THUMBNAIL_MAX_EDGE));
  const rendered = await context.renderAsync();
  const saved = await rendered.saveAsync({
    format: SaveFormat.WEBP,
    compress: THUMBNAIL_QUALITY / THUMBNAIL_QUALITY_SCALE,
  });
  try {
    return { bytes: await new File(saved.uri).bytes(), width: saved.width, height: saved.height };
  } finally {
    deleteIfExists(saved.uri);
  }
}

export function createMediaToolkit(): MediaToolkit {
  const handles = new Map<string, FileHandle>();

  function closeHandle(uri: string): void {
    handles.get(uri)?.close();
    handles.delete(uri);
  }

  return {
    byteSource: async (input) => {
      const uri = uriOf(input);
      const file = new File(uri);
      closeHandle(uri);
      const handle = file.open(FileMode.ReadOnly);
      handles.set(uri, handle);
      return {
        size: file.size,
        read: async (offset, length) => {
          handle.offset = offset;
          return handle.readBytes(length);
        },
      };
    },
    probe: async (input, format) => {
      const uri = uriOf(input);
      try {
        if (format.kind === 'video') return await probeVideo(uri);
        const image = await Image.loadAsync(uri);
        return { width: image.width, height: image.height, durationMs: null, decodable: true };
      } catch {
        return UNDECODABLE;
      }
    },
    makeThumbnail: async (input, format, posterTimeMs) => {
      const uri = uriOf(input);
      if (format.kind === 'video') {
        const poster = await getThumbnailAsync(uri, { time: posterTimeMs });
        try {
          return await encodeThumbnail(poster.uri, poster.width, poster.height);
        } finally {
          deleteIfExists(poster.uri);
        }
      }
      const image = await Image.loadAsync(uri);
      return encodeThumbnail(uri, image.width, image.height);
    },
    modifiedUtc: async (input) => {
      const modified = new File(uriOf(input)).modificationTime;
      return modified === null || modified === undefined ? null : new Date(modified).toISOString();
    },
    discard: async (input) => {
      const uri = uriOf(input);
      closeHandle(uri);
      deleteIfExists(uri);
    },
  };
}
