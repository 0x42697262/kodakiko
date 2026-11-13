import { fitWithin, type ByteSource } from '@kodakiko/core';
import {
  MEDIA_PROBE_TIMEOUT_MS,
  MEDIA_SEEK_TIMEOUT_MS,
  MILLISECONDS_PER_SECOND,
  THUMBNAIL_MAX_EDGE,
  THUMBNAIL_MIME_TYPE,
  THUMBNAIL_QUALITY,
  THUMBNAIL_QUALITY_SCALE,
} from '../../constants/media-constants';
import {
  CANVAS_CONTEXT_2D,
  IMAGE_ORIENTATION,
  VIDEO_ELEMENT,
  VIDEO_EVENTS,
  VIDEO_PRELOAD_AUTO,
  VIDEO_SRC_ATTRIBUTE,
} from '../../keys/media-keys';
import { EN } from '../../text/en';
import type {
  MediaInput,
  MediaToolkit,
  ProbeResult,
  ThumbnailResult,
} from './media-toolkit';

const UNDECODABLE: ProbeResult = { width: 0, height: 0, durationMs: null, decodable: false };

function blobOf(input: MediaInput): Blob {
  if ('blob' in input.handle) return input.handle.blob;
  throw new Error(EN['media.webHandleRequired']);
}

function waitForEvent(
  video: HTMLVideoElement,
  eventName: string,
  timeoutMs: number,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => cleanup(new Error(EN['media.probeTimeout'])), timeoutMs);
    function cleanup(error: Error | null): void {
      clearTimeout(timer);
      video.removeEventListener(eventName, onDone);
      video.removeEventListener(VIDEO_EVENTS.error, onError);
      if (error === null) resolve();
      else reject(error);
    }
    function onDone(): void {
      cleanup(null);
    }
    function onError(): void {
      cleanup(new Error(EN['media.videoUnreadable']));
    }
    video.addEventListener(eventName, onDone);
    video.addEventListener(VIDEO_EVENTS.error, onError);
  });
}

async function withVideo<Result>(
  blob: Blob,
  work: (video: HTMLVideoElement) => Promise<Result>,
): Promise<Result> {
  const url = URL.createObjectURL(blob);
  const video = document.createElement(VIDEO_ELEMENT);
  video.muted = true;
  video.preload = VIDEO_PRELOAD_AUTO;
  try {
    video.src = url;
    return await work(video);
  } finally {
    video.removeAttribute(VIDEO_SRC_ATTRIBUTE);
    video.load();
    URL.revokeObjectURL(url);
  }
}

async function probeVideo(blob: Blob): Promise<ProbeResult> {
  return withVideo(blob, async (video) => {
    await waitForEvent(video, VIDEO_EVENTS.loadedMetadata, MEDIA_PROBE_TIMEOUT_MS);
    if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
      await waitForEvent(video, VIDEO_EVENTS.loadedData, MEDIA_PROBE_TIMEOUT_MS);
    }
    return {
      width: video.videoWidth,
      height: video.videoHeight,
      durationMs: Number.isFinite(video.duration)
        ? Math.round(video.duration * MILLISECONDS_PER_SECOND)
        : null,
      decodable: true,
    };
  });
}

async function probePhoto(blob: Blob): Promise<ProbeResult> {
  const bitmap = await createImageBitmap(blob, { imageOrientation: IMAGE_ORIENTATION.fromImage });
  const result = { width: bitmap.width, height: bitmap.height, durationMs: null, decodable: true };
  bitmap.close();
  return result;
}

async function encodeThumbnail(
  source: CanvasImageSource,
  width: number,
  height: number,
): Promise<ThumbnailResult> {
  const size = fitWithin(width, height, THUMBNAIL_MAX_EDGE);
  const canvas = new OffscreenCanvas(size.width, size.height);
  const context = canvas.getContext(CANVAS_CONTEXT_2D);
  if (context === null) throw new Error(EN['media.canvasUnavailable']);
  context.drawImage(source, 0, 0, size.width, size.height);
  const encoded = await canvas.convertToBlob({
    type: THUMBNAIL_MIME_TYPE,
    quality: THUMBNAIL_QUALITY / THUMBNAIL_QUALITY_SCALE,
  });
  return {
    bytes: new Uint8Array(await encoded.arrayBuffer()),
    width: size.width,
    height: size.height,
  };
}

async function photoThumbnail(blob: Blob): Promise<ThumbnailResult> {
  const bitmap = await createImageBitmap(blob, { imageOrientation: IMAGE_ORIENTATION.fromImage });
  try {
    return await encodeThumbnail(bitmap, bitmap.width, bitmap.height);
  } finally {
    bitmap.close();
  }
}

async function videoThumbnail(blob: Blob, posterTimeMs: number): Promise<ThumbnailResult> {
  return withVideo(blob, async (video) => {
    await waitForEvent(video, VIDEO_EVENTS.loadedData, MEDIA_PROBE_TIMEOUT_MS);
    if (posterTimeMs > 0) {
      const seeked = waitForEvent(video, VIDEO_EVENTS.seeked, MEDIA_SEEK_TIMEOUT_MS);
      video.currentTime = posterTimeMs / MILLISECONDS_PER_SECOND;
      await seeked;
    }
    return encodeThumbnail(video, video.videoWidth, video.videoHeight);
  });
}

export function createMediaToolkit(): MediaToolkit {
  return {
    byteSource: async (input): Promise<ByteSource> => {
      const blob = blobOf(input);
      return {
        size: blob.size,
        read: async (offset, length) =>
          new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer()),
      };
    },
    probe: async (input, format) => {
      try {
        const blob = blobOf(input);
        return format.kind === 'photo' ? await probePhoto(blob) : await probeVideo(blob);
      } catch {
        return UNDECODABLE;
      }
    },
    makeThumbnail: async (input, format, posterTimeMs) => {
      const blob = blobOf(input);
      return format.kind === 'photo'
        ? photoThumbnail(blob)
        : videoThumbnail(blob, posterTimeMs);
    },
    modifiedUtc: async (input) => {
      const blob = blobOf(input);
      return input.origin !== 'paste' && blob instanceof File
        ? new Date(blob.lastModified).toISOString()
        : null;
    },
    discard: async () => undefined,
  };
}
