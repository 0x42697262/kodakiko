import type { z } from 'zod';
import { MediaDescriptorV1 } from '@kodakiko/contract';
import {
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  SNIFF_HEADER_BYTES,
  STREAM_CHUNK_SIZE_EXPONENT,
  byteSourceFromBytes,
  createStreamEncryptor,
  deviceOffsetMinutes,
  readImageMetadata,
  readVideoMetadata,
  resolveCaptureTime,
  sniffFormat,
  streamLayout,
  wipe,
  type ByteSource,
  type GeoPoint,
  type ImageMetadata,
  type MediaFormat,
  type ResolvedTime,
  type Sodium,
  type VideoRotation,
} from '@kodakiko/core';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { MediaInput, ProbeResult, MediaToolkit } from '../../adapters/media/media-toolkit';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import {
  IMPORT_YIELD_MS,
  PHOTO_POSTER_TIME_MS,
  POSTER_DURATION_DIVISOR,
  POSTER_MAX_TIME_MS,
  QUARTER_TURN,
  THREE_QUARTER_TURN,
  THUMBNAIL_MIME_TYPE,
} from '../../constants/media-constants';
import type { ConfigService } from '../config/config-service';
import type { DeviceSettings } from '../settings/device-settings';
import { pickerExifToMetadata } from './picker-exif';

export type MediaDescriptor = z.infer<typeof MediaDescriptorV1>;

export type ImportedMedia = {
  readonly mediaId: string;
  readonly mediaKey: Uint8Array;
  readonly originalObjectId: string;
  readonly thumbnailObjectId: string;
  readonly originalCiphertextBytes: number;
  readonly thumbnailCiphertextBytes: number;
  readonly descriptor: MediaDescriptor;
  readonly time: ResolvedTime;
  readonly location: { readonly point: GeoPoint; readonly source: 'exif' } | null;
  readonly thumbnailBytes: Uint8Array;
};

export type ImportRefusal = 'unsupported' | 'undecodable' | 'tooLarge' | 'trayFull' | 'readFailed';

export type ImportOutcome =
  | { readonly ok: true; readonly media: ImportedMedia }
  | { readonly ok: false; readonly reason: ImportRefusal; readonly fileName: string | null };

export type ImportSlots = { readonly used: number; readonly limit: number };

export type ImportService = {
  readonly importOne: (input: MediaInput, slots: ImportSlots) => Promise<ImportOutcome>;
};

export type ImportServiceDeps = {
  readonly sodium: Sodium;
  readonly fileStore: FileStore;
  readonly toolkit: MediaToolkit;
  readonly clock: Clock;
  readonly config: ConfigService;
  readonly deviceSettings: DeviceSettings;
  readonly randomId: () => string;
};

type Embedded = {
  readonly dateTimeOriginal: string | null;
  readonly offsetTimeOriginal: string | null;
  readonly containerCreationUtc: string | null;
  readonly gps: GeoPoint | null;
  readonly rotationDegrees: VideoRotation;
};

const NO_EMBEDDED: Embedded = Object.freeze({
  dateTimeOriginal: null,
  offsetTimeOriginal: null,
  containerCreationUtc: null,
  gps: null,
  rotationDegrees: 0,
});

class Refusal extends Error {
  readonly reason: ImportRefusal;

  constructor(reason: ImportRefusal) {
    super(reason);
    this.reason = reason;
  }
}

function fillFrom(file: ImageMetadata | null, picker: ImageMetadata | null): Embedded {
  return {
    dateTimeOriginal: file?.dateTimeOriginal ?? picker?.dateTimeOriginal ?? null,
    offsetTimeOriginal: file?.offsetTimeOriginal ?? picker?.offsetTimeOriginal ?? null,
    containerCreationUtc: null,
    gps: file?.gps ?? picker?.gps ?? null,
    rotationDegrees: 0,
  };
}

async function readEmbedded(
  source: ByteSource,
  format: MediaFormat,
  input: MediaInput,
): Promise<Embedded> {
  const picker = input.pickerExif === null ? null : pickerExifToMetadata(input.pickerExif);
  switch (format.container) {
    case 'jpeg':
    case 'png':
    case 'webp':
      return fillFrom(await readImageMetadata(source, format.container), picker);
    case 'heif':
      return fillFrom(null, picker);
    case 'mp4':
    case 'mov': {
      const video = await readVideoMetadata(source);
      return {
        ...NO_EMBEDDED,
        containerCreationUtc: video.creationUtc,
        gps: video.location,
        rotationDegrees: video.rotationDegrees,
      };
    }
    default:
      return NO_EMBEDDED;
  }
}

function posterTimeMs(format: MediaFormat, probe: ProbeResult): number {
  if (format.kind !== 'video') return PHOTO_POSTER_TIME_MS;
  return Math.min(POSTER_MAX_TIME_MS, (probe.durationMs ?? 0) / POSTER_DURATION_DIVISOR);
}

function yieldToEventLoop(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, IMPORT_YIELD_MS));
}

async function* encryptedChunks(
  sodium: Sodium,
  variantKey: Uint8Array,
  source: ByteSource,
): AsyncGenerator<Uint8Array> {
  const encryptor = createStreamEncryptor(sodium, variantKey, source.size);
  yield encryptor.header;
  const { chunkSize, chunkCount } = encryptor.layout;
  for (let index = 0; index < chunkCount; index += 1) {
    const plaintext = await source.read(index * chunkSize, chunkSize);
    try {
      yield encryptor.encryptNext(plaintext);
    } finally {
      wipe(plaintext);
    }
    await yieldToEventLoop();
  }
}

function variantKeyOf(sodium: Sodium, mediaKey: Uint8Array, subkeyId: number): Uint8Array {
  return sodium.kdfDerive(KEY_BYTES, subkeyId, KDF_CONTEXTS.media, mediaKey);
}

export function createImportService(deps: ImportServiceDeps): ImportService {
  async function discardQuietly(input: MediaInput): Promise<void> {
    try {
      await deps.toolkit.discard(input);
    } catch {
      return;
    }
  }

  async function deleteQuietly(ids: {
    readonly mediaId: string;
    readonly originalObjectId: string;
    readonly thumbnailObjectId: string;
  }): Promise<void> {
    await Promise.allSettled([
      deps.fileStore.delete(ids.originalObjectId),
      deps.fileStore.delete(ids.thumbnailObjectId),
      deps.fileStore.deletePlainThumbnail(ids.mediaId),
    ]);
  }

  async function build(input: MediaInput, slots: ImportSlots): Promise<ImportedMedia> {
    if (slots.used >= slots.limit) throw new Refusal('trayFull');
    const limits = deps.config.get() ?? CONFIG_FALLBACK;
    const source = await deps.toolkit.byteSource(input);
    const format = sniffFormat(await source.read(0, SNIFF_HEADER_BYTES));
    if (format === null) throw new Refusal('unsupported');
    const probe = await deps.toolkit.probe(input, format);
    if (!probe.decodable) throw new Refusal('undecodable');
    const originalLayout = streamLayout(source.size, STREAM_CHUNK_SIZE_EXPONENT);
    if (originalLayout.ciphertextSize > limits.maxUploadBytes) throw new Refusal('tooLarge');

    const camera = input.origin === 'camera';
    const embedded = await readEmbedded(source, format, input);
    const time = resolveCaptureTime({
      exifDateTimeOriginal: camera ? null : embedded.dateTimeOriginal,
      exifOffset: camera ? null : embedded.offsetTimeOriginal,
      containerCreationUtc: camera ? null : embedded.containerCreationUtc,
      fileModifiedUtc: camera ? null : await deps.toolkit.modifiedUtc(input),
      nowUtc: deps.clock.nowIso(),
      deviceOffsetMinutes: deviceOffsetMinutes(new Date(deps.clock.now())),
    });
    const thumbnail = await deps.toolkit.makeThumbnail(input, format, posterTimeMs(format, probe));

    const mediaId = deps.randomId();
    const originalObjectId = deps.randomId();
    const thumbnailObjectId = deps.randomId();
    const mediaKey = deps.sodium.randomBytes(KEY_BYTES);
    const originalKey = variantKeyOf(deps.sodium, mediaKey, KDF_SUBKEY_IDS.mediaOriginal);
    const thumbnailKey = variantKeyOf(deps.sodium, mediaKey, KDF_SUBKEY_IDS.mediaThumbnail);
    const ids = { mediaId, originalObjectId, thumbnailObjectId };
    try {
      await deps.fileStore.writeObject(
        originalObjectId,
        encryptedChunks(deps.sodium, originalKey, source),
      );
      const thumbnailSource = byteSourceFromBytes(thumbnail.bytes);
      await deps.fileStore.writeObject(
        thumbnailObjectId,
        encryptedChunks(deps.sodium, thumbnailKey, thumbnailSource),
      );
      const encryptThumbnails =
        !deps.deviceSettings.loaded() || deps.deviceSettings.get().encryptThumbnails;
      if (!encryptThumbnails) {
        await deps.fileStore.writePlainThumbnail(mediaId, thumbnail.bytes);
      }
      const turned = embedded.rotationDegrees === QUARTER_TURN || embedded.rotationDegrees === THREE_QUARTER_TURN;
      const size = turned ? { width: probe.height, height: probe.width } : probe;
      const descriptor = MediaDescriptorV1.parse({
        mediaId,
        kind: format.kind,
        mimeType: format.mimeType,
        width: size.width,
        height: size.height,
        durationMs: format.kind === 'video' && probe.durationMs !== null ? Math.round(probe.durationMs) : null,
        originalPlaintextBytes: source.size,
        thumbnail: {
          width: thumbnail.width,
          height: thumbnail.height,
          mimeType: THUMBNAIL_MIME_TYPE,
          plaintextBytes: thumbnail.bytes.length,
        },
        capturedAtUtc: time.capturedAtUtc,
        originalFileName: camera ? null : input.fileName,
      });
      return {
        mediaId,
        mediaKey,
        originalObjectId,
        thumbnailObjectId,
        originalCiphertextBytes: originalLayout.ciphertextSize,
        thumbnailCiphertextBytes: streamLayout(thumbnail.bytes.length, STREAM_CHUNK_SIZE_EXPONENT)
          .ciphertextSize,
        descriptor,
        time,
        location:
          camera || embedded.gps === null ? null : { point: embedded.gps, source: 'exif' },
        thumbnailBytes: thumbnail.bytes,
      };
    } catch (error) {
      await deleteQuietly(ids);
      wipe(mediaKey);
      throw error;
    } finally {
      wipe(originalKey);
      wipe(thumbnailKey);
    }
  }

  return {
    importOne: async (input, slots) => {
      try {
        const media = await build(input, slots);
        await discardQuietly(input);
        return { ok: true, media };
      } catch (error) {
        await discardQuietly(input);
        return {
          ok: false,
          reason: error instanceof Refusal ? error.reason : 'readFailed',
          fileName: input.fileName,
        };
      }
    },
  };
}
