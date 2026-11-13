import {
  STRIP_COPY_CHUNK_BYTES,
  UnsupportedVideoError,
  byteSourceFromBytes,
  hasExifSegment,
  moovHasMetadata,
  planIsoBmffStrip,
  planWebmDateStrip,
  readImageMetadata,
  readVideoMetadata,
  streamStripped,
  type ByteSource,
} from '@kodakiko/core';
import type { MediaDescriptorV1 } from '@kodakiko/contract';
import type { z } from 'zod';

type Descriptor = z.infer<typeof MediaDescriptorV1>;

export const LINK_PHOTO_MAX_EDGE = 1600;
export const LINK_PHOTO_QUALITY = 0.85;
export const LINK_PHOTO_MIME = 'image/jpeg';
const WEBM_MIME = 'video/webm';

export class StripUndecodableError extends Error {}
export class StripFailedError extends Error {}
export { UnsupportedVideoError };

export type StrippedMedia = {
  readonly chunks: AsyncIterable<Uint8Array>;
  readonly plaintextSize: number;
  readonly mimeType: string;
  readonly width: number;
  readonly height: number;
  readonly durationMs: number | null;
};

export interface LinkStripper {
  readonly strip: (source: ByteSource, descriptor: Descriptor) => Promise<StrippedMedia>;
}

export type ReencodedPhoto = { readonly bytes: Uint8Array; readonly width: number; readonly height: number };
export type PhotoReencoder = (bytes: Uint8Array, mimeType: string) => Promise<ReencodedPhoto>;

async function* single(bytes: Uint8Array): AsyncGenerator<Uint8Array> {
  yield bytes;
}

async function collect(chunks: AsyncIterable<Uint8Array>, size: number): Promise<Uint8Array> {
  const out = new Uint8Array(size);
  let position = 0;
  for await (const chunk of chunks) {
    out.set(chunk, position);
    position += chunk.length;
  }
  return out;
}

export function createLinkStripper(deps: { readonly reencodePhoto: PhotoReencoder }): LinkStripper {
  async function stripPhoto(source: ByteSource, descriptor: Descriptor): Promise<StrippedMedia> {
    const original = await source.read(0, source.size);
    let photo: ReencodedPhoto;
    try {
      photo = await deps.reencodePhoto(original, descriptor.mimeType);
    } catch {
      throw new StripUndecodableError();
    }
    const metadata = await readImageMetadata(byteSourceFromBytes(photo.bytes), 'jpeg').catch(() => null);
    const leaks =
      metadata !== null && (metadata.dateTimeOriginal !== null || metadata.offsetTimeOriginal !== null || metadata.gps !== null);
    if (leaks || hasExifSegment(photo.bytes)) throw new StripFailedError();
    return {
      chunks: single(photo.bytes),
      plaintextSize: photo.bytes.length,
      mimeType: LINK_PHOTO_MIME,
      width: photo.width,
      height: photo.height,
      durationMs: null,
    };
  }

  async function stripVideo(source: ByteSource, descriptor: Descriptor): Promise<StrippedMedia> {
    const webm = descriptor.mimeType === WEBM_MIME;
    const plan = webm ? await planWebmDateStrip(source) : await planIsoBmffStrip(source);
    if (!webm && moovHasMetadata(plan.moov)) throw new StripFailedError();
    if (!webm) {
      const head = await collect(
        streamStripped(source, { segments: plan.segments.filter((segment) => segment.kind === 'bytes') }, STRIP_COPY_CHUNK_BYTES),
        plan.segments.reduce((sum, segment) => sum + (segment.kind === 'bytes' ? segment.bytes.length : 0), 0),
      );
      const check = await readVideoMetadata(byteSourceFromBytes(head));
      if (check.creationUtc !== null || check.location !== null) throw new StripFailedError();
    }
    return {
      chunks: streamStripped(source, plan, STRIP_COPY_CHUNK_BYTES),
      plaintextSize: plan.outputSize,
      mimeType: descriptor.mimeType,
      width: descriptor.width,
      height: descriptor.height,
      durationMs: descriptor.durationMs,
    };
  }

  return {
    strip: (source, descriptor) => (descriptor.kind === 'photo' ? stripPhoto(source, descriptor) : stripVideo(source, descriptor)),
  };
}
