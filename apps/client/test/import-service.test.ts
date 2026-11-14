import { UI_SETTINGS_DEFAULTS } from '../src/constants/ui-settings-constants';
import { beforeAll, describe, expect, it } from 'vitest';
import { MediaDescriptorV1, type PublicConfigDto } from '@kodakiko/contract';
import {
  KDF_CONTEXTS,
  KDF_SUBKEY_IDS,
  KEY_BYTES,
  STREAM_CHUNK_SIZE_EXPONENT,
  STREAM_HEADER_BYTES,
  byteSourceFromBytes,
  concatBytes,
  createStreamDecryptor,
  deviceOffsetMinutes,
  streamLayout,
  type Sodium,
} from '@kodakiko/core';
import {
  box,
  buildJpeg,
  buildTiff,
  concat,
  exifApp1,
  ftyp,
  jfifApp0,
  moov,
  mp4Seconds,
  mvhd,
  rotationMatrix,
  tkhd,
  trak,
  udta,
  xyzBox,
  type Rotation,
} from '../../../packages/core/test/media/builders';
import type { Clock } from '../src/adapters/clock';
import type { FileStore } from '../src/adapters/filestore/filestore';
import type {
  MediaInput,
  MediaToolkit,
  ProbeResult,
} from '../src/adapters/media/media-toolkit';
import type { ConfigService } from '../src/services/config/config-service';
import { createImportService } from '../src/services/media/import-service';
import type { ImportOutcome } from '../src/services/media/import-service';
import type { DeviceSettingsValue } from '../src/services/settings/device-settings';
import { createMemoryFileStore, createNodeSodium } from './support';

const ACCOUNT_KEY = 'host/user';
const NOW_ISO = '2026-11-12T10:00:00.000Z';
const NOW_MS = Date.parse(NOW_ISO);
const CLOCK: Clock = { now: () => NOW_MS, nowIso: () => NOW_ISO };
const MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800;
const EXIF_DATE = '2026:05:01 10:00:00';
const EXIF_OFFSET = '+02:00';
const EXIF_CAPTURED_UTC = '2026-05-01T08:00:00.000Z';
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const COORDINATE_PRECISION = 5;
const MEDIA_ID = 'id00000000000000000001';
const ORIGINAL_OBJECT_ID = 'id00000000000000000002';
const THUMBNAIL_OBJECT_ID = 'id00000000000000000003';
const THUMBNAIL_BYTES = Uint8Array.from({ length: 100 }, (_, index) => (index * 3) % 251);
const THUMBNAIL_WIDTH = 512;
const THUMBNAIL_HEIGHT = 384;
const FILE_MODIFIED_UTC = '2026-03-01T12:00:00.000Z';
const MEBIBYTE = 1_048_576;
const PHOTO_PROBE: ProbeResult = { width: 4000, height: 3000, durationMs: null, decodable: true };
const SLOTS = { used: 0, limit: 20 };
const WEBM_HEADER = Uint8Array.of(0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x86, 0x81);

const CONFIG: PublicConfigDto = {
  apiVersion: 1,
  signupMode: 'invite',
  sharingEnabled: false,
  publicLinksEnabled: false,
  maxMediaPerEntry: 20,
  maxUploadBytes: 3_758_096_384,
  defaultQuotaBytes: 0,
  trashDays: 30,
  accountDeletionGraceDays: 7,
  serverVersion: 'test',
};

const SETTINGS: DeviceSettingsValue = {
  encryptThumbnails: true,
  originalCacheCapBytes: 0,
  largeUploadsOnWifiOnly: true,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: false,
  autoLockMinutes: 5,
  ...UI_SETTINGS_DEFAULTS,
};

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function parisTiff(): Uint8Array {
  return buildTiff({ dateTimeOriginal: EXIF_DATE, offsetTimeOriginal: EXIF_OFFSET, gps: PARIS });
}

function jpegBytes(options: { tiff?: Uint8Array; scanBytes?: number } = {}): Uint8Array {
  return buildJpeg({
    segments: [jfifApp0(), ...(options.tiff === undefined ? [] : [exifApp1(options.tiff)])],
    scanBytes: options.scanBytes ?? 256,
  });
}

function heicBytes(): Uint8Array {
  return concat(ftyp('heic', ['mif1', 'heic']), new Uint8Array(200));
}

function mp4Bytes(rotation: Rotation = 0): Uint8Array {
  const created = mp4Seconds(EXIF_CAPTURED_UTC, MP4_EPOCH_OFFSET_SECONDS);
  return concat(
    ftyp('isom', ['isom']),
    box('mdat', new Uint8Array(100)),
    moov(
      mvhd({ version: 0, creationSeconds1904: created }),
      trak('vide', tkhd({ version: 0, matrix: rotationMatrix(rotation) })),
      udta(xyzBox('+37.3861-122.0839/')),
    ),
  );
}

function webmBytes(): Uint8Array {
  return concat(WEBM_HEADER, new Uint8Array(100));
}

type ToolkitCalls = {
  byteSource: number;
  probe: number;
  makeThumbnail: number;
  readonly posterTimes: number[];
  readonly discarded: MediaInput[];
};

type ToolkitScript = {
  readonly bytes: Uint8Array;
  readonly probe?: ProbeResult;
  readonly modifiedUtc?: string | null;
};

function createFakeToolkit(script: ToolkitScript): { toolkit: MediaToolkit; calls: ToolkitCalls } {
  const calls: ToolkitCalls = {
    byteSource: 0,
    probe: 0,
    makeThumbnail: 0,
    posterTimes: [],
    discarded: [],
  };
  const toolkit: MediaToolkit = {
    byteSource: async () => {
      calls.byteSource += 1;
      return byteSourceFromBytes(script.bytes);
    },
    probe: async () => {
      calls.probe += 1;
      return script.probe ?? PHOTO_PROBE;
    },
    makeThumbnail: async (_input, _format, posterTimeMs) => {
      calls.makeThumbnail += 1;
      calls.posterTimes.push(posterTimeMs);
      return { bytes: THUMBNAIL_BYTES, width: THUMBNAIL_WIDTH, height: THUMBNAIL_HEIGHT };
    },
    modifiedUtc: async () => script.modifiedUtc ?? null,
    discard: async (input) => {
      calls.discarded.push(input);
    },
  };
  return { toolkit, calls };
}

function sequentialIds(): () => string {
  let counter = 0;
  return () => {
    counter += 1;
    return `id${String(counter).padStart(20, '0')}`;
  };
}

function configStub(value: PublicConfigDto | null): ConfigService {
  return {
    get: () => value,
    refresh: async () => value,
    accept: () => undefined,
    subscribe: () => () => undefined,
  };
}

function pickerInput(overrides: Partial<MediaInput> = {}): MediaInput {
  return {
    origin: 'picker',
    handle: { uri: 'file:///cache/a.jpg' },
    fileName: 'a.jpg',
    pickerExif: null,
    ...overrides,
  };
}

type HarnessOptions = ToolkitScript & {
  readonly config?: PublicConfigDto | null;
  readonly settings?: Partial<DeviceSettingsValue>;
  readonly settingsLoaded?: boolean;
  readonly wrapFileStore?: (store: FileStore) => FileStore;
};

async function createHarness(options: HarnessOptions) {
  const baseStore = createMemoryFileStore();
  await baseStore.open(ACCOUNT_KEY);
  const fileStore = options.wrapFileStore === undefined ? baseStore : options.wrapFileStore(baseStore);
  const { toolkit, calls } = createFakeToolkit(options);
  const settings: DeviceSettingsValue = { ...SETTINGS, ...options.settings };
  const service = createImportService({
    sodium,
    fileStore,
    toolkit,
    clock: CLOCK,
    config: configStub(options.config === undefined ? CONFIG : options.config),
    deviceSettings: {
      get: () => settings,
      loaded: () => options.settingsLoaded ?? true,
      update: async () => undefined,
      subscribe: () => () => undefined,
    },
    randomId: sequentialIds(),
  });
  return { service, fileStore, calls };
}

function expectOk(outcome: ImportOutcome) {
  if (!outcome.ok) throw new Error(`Expected an imported item, got ${outcome.reason}`);
  return outcome.media;
}

async function decryptObject(
  fileStore: FileStore,
  objectId: string,
  mediaKey: Uint8Array,
  variant: 'original' | 'thumbnail',
  plaintextSize: number,
): Promise<Uint8Array> {
  const variantKey = sodium.kdfDerive(
    KEY_BYTES,
    variant === 'original' ? KDF_SUBKEY_IDS.mediaOriginal : KDF_SUBKEY_IDS.mediaThumbnail,
    KDF_CONTEXTS.media,
    mediaKey,
  );
  const header = await fileStore.readRange(objectId, 0, STREAM_HEADER_BYTES);
  const decryptor = createStreamDecryptor(sodium, variantKey, header, plaintextSize);
  const { chunkCount, chunkCiphertextSize } = decryptor.layout;
  const parts: Uint8Array[] = [];
  for (let index = 0; index < chunkCount; index += 1) {
    const chunk = await fileStore.readRange(
      objectId,
      STREAM_HEADER_BYTES + index * chunkCiphertextSize,
      chunkCiphertextSize,
    );
    parts.push(decryptor.decryptNext(chunk));
  }
  decryptor.finish();
  return concatBytes(...parts);
}

async function* observeChunks(
  chunks: AsyncIterable<Uint8Array>,
  onChunk: (position: number) => void,
): AsyncGenerator<Uint8Array> {
  let position = 0;
  for await (const chunk of chunks) {
    position += 1;
    onChunk(position);
    yield chunk;
  }
}

function failingWrites(store: FileStore, failingCall: number): FileStore {
  let calls = 0;
  return {
    ...store,
    writeObject: async (objectId, chunks) => {
      calls += 1;
      if (calls === failingCall) throw new Error('disk full');
      return store.writeObject(objectId, chunks);
    },
  };
}

describe('importOne: a photo', () => {
  it('writes an original and a thumbnail that decrypt to the source bytes', async () => {
    const bytes = jpegBytes({ tiff: parisTiff(), scanBytes: 2.5 * MEBIBYTE });
    const { service, fileStore, calls } = await createHarness({ bytes });
    const input = pickerInput();

    const media = expectOk(await service.importOne(input, SLOTS));

    expect(media.mediaId).toBe(MEDIA_ID);
    expect(media.originalObjectId).toBe(ORIGINAL_OBJECT_ID);
    expect(media.thumbnailObjectId).toBe(THUMBNAIL_OBJECT_ID);
    expect(media.mediaKey).toHaveLength(KEY_BYTES);
    expect(
      await decryptObject(fileStore, media.originalObjectId, media.mediaKey, 'original', bytes.length),
    ).toEqual(bytes);
    expect(
      await decryptObject(
        fileStore,
        media.thumbnailObjectId,
        media.mediaKey,
        'thumbnail',
        THUMBNAIL_BYTES.length,
      ),
    ).toEqual(THUMBNAIL_BYTES);
    expect(media.thumbnailBytes).toEqual(THUMBNAIL_BYTES);
    expect(calls.discarded).toEqual([input]);
    expect(calls.discarded[0]).toBe(input);
  });

  it('reports the ciphertext sizes of the stored objects', async () => {
    const bytes = jpegBytes({ scanBytes: 2.5 * MEBIBYTE });
    const { service, fileStore } = await createHarness({ bytes });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    const originalLayout = streamLayout(bytes.length, STREAM_CHUNK_SIZE_EXPONENT);
    const thumbnailLayout = streamLayout(THUMBNAIL_BYTES.length, STREAM_CHUNK_SIZE_EXPONENT);
    expect(media.originalCiphertextBytes).toBe(originalLayout.ciphertextSize);
    expect(media.thumbnailCiphertextBytes).toBe(thumbnailLayout.ciphertextSize);
    expect(await fileStore.size(media.originalObjectId)).toBe(originalLayout.ciphertextSize);
    expect(await fileStore.size(media.thumbnailObjectId)).toBe(thumbnailLayout.ciphertextSize);
  });

  it('starts both stored objects with the KDKS magic', async () => {
    const { service, fileStore } = await createHarness({ bytes: jpegBytes() });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    for (const objectId of [media.originalObjectId, media.thumbnailObjectId]) {
      expect(new TextDecoder().decode(await fileStore.readRange(objectId, 0, 4))).toBe('KDKS');
    }
  });

  it('builds a descriptor that validates and carries the file facts', async () => {
    const bytes = jpegBytes({ tiff: parisTiff() });
    const { service } = await createHarness({ bytes });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(MediaDescriptorV1.safeParse(media.descriptor).success).toBe(true);
    expect(media.descriptor).toEqual({
      mediaId: MEDIA_ID,
      kind: 'photo',
      mimeType: 'image/jpeg',
      width: PHOTO_PROBE.width,
      height: PHOTO_PROBE.height,
      durationMs: null,
      originalPlaintextBytes: bytes.length,
      thumbnail: {
        width: THUMBNAIL_WIDTH,
        height: THUMBNAIL_HEIGHT,
        mimeType: 'image/webp',
        plaintextBytes: THUMBNAIL_BYTES.length,
      },
      capturedAtUtc: EXIF_CAPTURED_UTC,
      originalFileName: 'a.jpg',
    });
  });

  it('takes time and location from the EXIF of the file', async () => {
    const { service } = await createHarness({
      bytes: jpegBytes({ tiff: parisTiff() }),
      modifiedUtc: FILE_MODIFIED_UTC,
    });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.time).toEqual({ capturedAtUtc: EXIF_CAPTURED_UTC, utcOffsetMinutes: 120, source: 'exif' });
    expect(media.location?.source).toBe('exif');
    expect(media.location?.point.latitude).toBeCloseTo(PARIS.latitude, COORDINATE_PRECISION);
    expect(media.location?.point.longitude).toBeCloseTo(PARIS.longitude, COORDINATE_PRECISION);
  });

  it('has no location when the file has no GPS', async () => {
    const { service } = await createHarness({ bytes: jpegBytes() });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.location).toBeNull();
  });

  it('fills only the fields the file left empty from the picker EXIF', async () => {
    const fileTiff = buildTiff({ dateTimeOriginal: EXIF_DATE, offsetTimeOriginal: EXIF_OFFSET });
    const { service } = await createHarness({ bytes: jpegBytes({ tiff: fileTiff }) });
    const input = pickerInput({
      pickerExif: {
        DateTimeOriginal: '2020:01:01 00:00:00',
        OffsetTimeOriginal: '+00:00',
        GPSLatitude: PARIS.latitude,
        GPSLatitudeRef: 'N',
        GPSLongitude: PARIS.longitude,
        GPSLongitudeRef: 'E',
      },
    });

    const media = expectOk(await service.importOne(input, SLOTS));

    expect(media.time.capturedAtUtc).toBe(EXIF_CAPTURED_UTC);
    expect(media.location?.point.latitude).toBeCloseTo(PARIS.latitude, COORDINATE_PRECISION);
    expect(media.location?.source).toBe('exif');
  });

  it('uses the file modified time when there is no embedded time', async () => {
    const { service } = await createHarness({ bytes: jpegBytes(), modifiedUtc: FILE_MODIFIED_UTC });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.time).toMatchObject({ capturedAtUtc: FILE_MODIFIED_UTC, source: 'file' });
  });

  it('uses now when nothing else gives a time', async () => {
    const { service } = await createHarness({ bytes: jpegBytes() });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.time).toEqual({
      capturedAtUtc: NOW_ISO,
      utcOffsetMinutes: deviceOffsetMinutes(new Date(NOW_MS)),
      source: 'now',
    });
  });

  it('ignores every embedded field for a camera capture', async () => {
    const { service } = await createHarness({
      bytes: jpegBytes({ tiff: parisTiff() }),
      modifiedUtc: FILE_MODIFIED_UTC,
    });
    const input = pickerInput({ origin: 'camera', fileName: null });

    const media = expectOk(await service.importOne(input, SLOTS));

    expect(media.time).toEqual({
      capturedAtUtc: NOW_ISO,
      utcOffsetMinutes: deviceOffsetMinutes(new Date(NOW_MS)),
      source: 'now',
    });
    expect(media.descriptor.originalFileName).toBeNull();
    expect(media.location).toBeNull();
  });

  it('keeps the displayed probe size for EXIF orientation 6 and does not rotate again', async () => {
    const tiff = buildTiff({ orientation: 6 });
    const { service } = await createHarness({ bytes: jpegBytes({ tiff }) });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.descriptor.width).toBe(PHOTO_PROBE.width);
    expect(media.descriptor.height).toBe(PHOTO_PROBE.height);
  });

  it('keeps the displayed probe size for EXIF orientation 1', async () => {
    const tiff = buildTiff({ orientation: 1 });
    const { service } = await createHarness({ bytes: jpegBytes({ tiff }) });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(media.descriptor.width).toBe(PHOTO_PROBE.width);
    expect(media.descriptor.height).toBe(PHOTO_PROBE.height);
  });

  it('keeps the displayed probe size of a camera capture and still ignores its time and GPS', async () => {
    const tiff = buildTiff({ orientation: 6, gps: PARIS, dateTimeOriginal: EXIF_DATE });
    const { service } = await createHarness({ bytes: jpegBytes({ tiff }) });

    const media = expectOk(
      await service.importOne(pickerInput({ origin: 'camera', fileName: null }), SLOTS),
    );

    expect(media.descriptor.width).toBe(PHOTO_PROBE.width);
    expect(media.descriptor.height).toBe(PHOTO_PROBE.height);
    expect(media.location).toBeNull();
    expect(media.time.source).toBe('now');
  });

  it('keeps the displayed probe size of a HEIF photo with picker orientation 6', async () => {
    const { service } = await createHarness({ bytes: heicBytes() });
    const input = pickerInput({ fileName: 'a.heic', pickerExif: { Orientation: 6 } });

    const media = expectOk(await service.importOne(input, SLOTS));

    expect(media.descriptor.width).toBe(PHOTO_PROBE.width);
    expect(media.descriptor.height).toBe(PHOTO_PROBE.height);
  });
});

describe('importOne: HEIF, video and WebM', () => {
  it('reads a HEIF photo time only from the picker EXIF', async () => {
    const { service } = await createHarness({ bytes: heicBytes(), modifiedUtc: FILE_MODIFIED_UTC });
    const input = pickerInput({
      fileName: 'a.heic',
      pickerExif: { DateTimeOriginal: EXIF_DATE, OffsetTimeOriginal: EXIF_OFFSET },
    });

    const media = expectOk(await service.importOne(input, SLOTS));

    expect(media.descriptor.mimeType).toBe('image/heic');
    expect(media.time).toEqual({ capturedAtUtc: EXIF_CAPTURED_UTC, utcOffsetMinutes: 120, source: 'exif' });
  });

  it('falls back to the file time for a HEIF photo without picker EXIF', async () => {
    const { service } = await createHarness({ bytes: heicBytes(), modifiedUtc: FILE_MODIFIED_UTC });

    const media = expectOk(await service.importOne(pickerInput({ fileName: 'a.heic' }), SLOTS));

    expect(media.time).toMatchObject({ capturedAtUtc: FILE_MODIFIED_UTC, source: 'file' });
  });

  it('describes an MP4 video with its container time, location and duration', async () => {
    const { service, calls } = await createHarness({
      bytes: mp4Bytes(),
      probe: { width: 1280, height: 720, durationMs: 5000, decodable: true },
    });

    const media = expectOk(await service.importOne(pickerInput({ fileName: 'clip.mp4' }), SLOTS));

    expect(MediaDescriptorV1.safeParse(media.descriptor).success).toBe(true);
    expect(media.descriptor).toMatchObject({
      kind: 'video',
      mimeType: 'video/mp4',
      width: 1280,
      height: 720,
      durationMs: 5000,
      capturedAtUtc: EXIF_CAPTURED_UTC,
      originalFileName: 'clip.mp4',
    });
    expect(media.time).toMatchObject({ capturedAtUtc: EXIF_CAPTURED_UTC, source: 'container' });
    expect(media.location?.point.latitude).toBeCloseTo(37.3861, COORDINATE_PRECISION);
    expect(media.location?.point.longitude).toBeCloseTo(-122.0839, COORDINATE_PRECISION);
    expect(calls.posterTimes).toEqual([1000]);
  });

  it('takes the poster frame at half the duration of a short video', async () => {
    const { service, calls } = await createHarness({
      bytes: mp4Bytes(),
      probe: { width: 1280, height: 720, durationMs: 1500, decodable: true },
    });

    expectOk(await service.importOne(pickerInput({ fileName: 'clip.mp4' }), SLOTS));

    expect(calls.posterTimes).toEqual([750]);
  });

  it('reads no metadata from WebM and uses the file time', async () => {
    const { service } = await createHarness({
      bytes: webmBytes(),
      probe: { width: 320, height: 240, durationMs: 2000, decodable: true },
      modifiedUtc: FILE_MODIFIED_UTC,
    });

    const media = expectOk(await service.importOne(pickerInput({ fileName: 'clip.webm' }), SLOTS));

    expect(media.descriptor.mimeType).toBe('video/webm');
    expect(media.time).toMatchObject({ capturedAtUtc: FILE_MODIFIED_UTC, source: 'file' });
    expect(media.location).toBeNull();
  });
});

describe('importOne: video rotation', () => {
  const VIDEO_PROBE: ProbeResult = { width: 1280, height: 720, durationMs: 5000, decodable: true };

  it.each([90, 270] as const)('swaps width and height for a %i degree rotation', async (rotation) => {
    const { service } = await createHarness({ bytes: mp4Bytes(rotation), probe: VIDEO_PROBE });

    const media = expectOk(await service.importOne(pickerInput({ fileName: 'clip.mp4' }), SLOTS));

    expect(media.descriptor.width).toBe(VIDEO_PROBE.height);
    expect(media.descriptor.height).toBe(VIDEO_PROBE.width);
  });

  it.each([0, 180] as const)('keeps width and height for a %i degree rotation', async (rotation) => {
    const { service } = await createHarness({ bytes: mp4Bytes(rotation), probe: VIDEO_PROBE });

    const media = expectOk(await service.importOne(pickerInput({ fileName: 'clip.mp4' }), SLOTS));

    expect(media.descriptor.width).toBe(VIDEO_PROBE.width);
    expect(media.descriptor.height).toBe(VIDEO_PROBE.height);
  });
});

describe('importOne: plain thumbnails', () => {
  it('treats thumbnails as encrypted while the device settings are not loaded', async () => {
    const { service, fileStore } = await createHarness({
      bytes: jpegBytes(),
      settings: { encryptThumbnails: false },
      settingsLoaded: false,
    });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(fileStore.plainThumbnailUri(media.mediaId)).toBeNull();
  });

  it('writes a plain thumbnail when thumbnails are not encrypted', async () => {
    const { service, fileStore } = await createHarness({
      bytes: jpegBytes(),
      settings: { encryptThumbnails: false },
    });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(fileStore.plainThumbnailUri(media.mediaId)).not.toBeNull();
  });

  it('writes no plain thumbnail when thumbnails are encrypted', async () => {
    const { service, fileStore } = await createHarness({ bytes: jpegBytes() });

    const media = expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(fileStore.plainThumbnailUri(media.mediaId)).toBeNull();
  });
});

describe('importOne: refusals', () => {
  it('refuses an unknown header as unsupported and writes nothing', async () => {
    const { service, fileStore, calls } = await createHarness({ bytes: new Uint8Array(512).fill(7) });

    const outcome = await service.importOne(pickerInput(), SLOTS);

    expect(outcome).toEqual({ ok: false, reason: 'unsupported', fileName: 'a.jpg' });
    expect((await fileStore.listSizes()).size).toBe(0);
    expect(calls.probe).toBe(0);
    expect(calls.makeThumbnail).toBe(0);
    expect(calls.discarded).toHaveLength(1);
  });

  it('refuses a file the toolkit cannot decode as undecodable and writes nothing', async () => {
    const { service, fileStore, calls } = await createHarness({
      bytes: heicBytes(),
      probe: { width: 0, height: 0, durationMs: null, decodable: false },
    });

    const outcome = await service.importOne(pickerInput({ fileName: 'a.heic' }), SLOTS);

    expect(outcome).toEqual({ ok: false, reason: 'undecodable', fileName: 'a.heic' });
    expect((await fileStore.listSizes()).size).toBe(0);
    expect(calls.makeThumbnail).toBe(0);
    expect(calls.discarded).toHaveLength(1);
  });

  it('accepts a file whose ciphertext is exactly the server limit and refuses one byte less', async () => {
    const bytes = jpegBytes({ scanBytes: 1500 });
    const limit = streamLayout(bytes.length, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize;

    const atLimit = await createHarness({ bytes, config: { ...CONFIG, maxUploadBytes: limit } });
    expectOk(await atLimit.service.importOne(pickerInput(), SLOTS));

    const below = await createHarness({ bytes, config: { ...CONFIG, maxUploadBytes: limit - 1 } });
    const outcome = await below.service.importOne(pickerInput(), SLOTS);
    expect(outcome).toEqual({ ok: false, reason: 'tooLarge', fileName: 'a.jpg' });
    expect((await below.fileStore.listSizes()).size).toBe(0);
    expect(below.calls.makeThumbnail).toBe(0);
    expect(below.calls.discarded).toHaveLength(1);
  });

  it('uses the built-in fallback limits when the server config is unknown', async () => {
    const { service } = await createHarness({ bytes: jpegBytes(), config: null });

    expectOk(await service.importOne(pickerInput(), SLOTS));
  });

  it('refuses with trayFull before reading anything when no slot is left', async () => {
    const { service, fileStore, calls } = await createHarness({ bytes: jpegBytes() });

    expect(await service.importOne(pickerInput(), { used: 20, limit: 20 })).toEqual({
      ok: false,
      reason: 'trayFull',
      fileName: 'a.jpg',
    });
    expect(await service.importOne(pickerInput(), { used: 25, limit: 20 })).toMatchObject({
      reason: 'trayFull',
    });
    expect(calls.byteSource).toBe(0);
    expect(calls.probe).toBe(0);
    expect(calls.discarded).toHaveLength(2);
    expect((await fileStore.listSizes()).size).toBe(0);
  });

  it('reports a null file name for a refused camera capture', async () => {
    const { service } = await createHarness({ bytes: jpegBytes() });

    const outcome = await service.importOne(pickerInput({ origin: 'camera', fileName: null }), {
      used: 1,
      limit: 1,
    });

    expect(outcome).toEqual({ ok: false, reason: 'trayFull', fileName: null });
  });
});

describe('importOne: failures after encryption started', () => {
  it('deletes the original when writing the thumbnail fails', async () => {
    const { service, fileStore, calls } = await createHarness({
      bytes: jpegBytes(),
      wrapFileStore: (store) => failingWrites(store, 2),
    });
    const input = pickerInput();

    const outcome = await service.importOne(input, SLOTS);

    expect(outcome).toEqual({ ok: false, reason: 'readFailed', fileName: 'a.jpg' });
    expect((await fileStore.listSizes()).size).toBe(0);
    expect(calls.discarded).toEqual([input]);
  });

  it('leaves nothing behind when writing the original fails', async () => {
    const { service, fileStore, calls } = await createHarness({
      bytes: jpegBytes(),
      wrapFileStore: (store) => failingWrites(store, 1),
    });

    const outcome = await service.importOne(pickerInput(), SLOTS);

    expect(outcome).toMatchObject({ ok: false, reason: 'readFailed' });
    expect((await fileStore.listSizes()).size).toBe(0);
    expect(calls.discarded).toHaveLength(1);
  });

  it('deletes both objects when writing the plain thumbnail fails', async () => {
    const { service, fileStore, calls } = await createHarness({
      bytes: jpegBytes(),
      settings: { encryptThumbnails: false },
      wrapFileStore: (store) => ({
        ...store,
        writePlainThumbnail: async () => {
          throw new Error('disk full');
        },
      }),
    });

    const outcome = await service.importOne(pickerInput(), SLOTS);

    expect(outcome).toMatchObject({ ok: false, reason: 'readFailed' });
    expect((await fileStore.listSizes()).size).toBe(0);
    expect(fileStore.plainThumbnailUri(MEDIA_ID)).toBeNull();
    expect(calls.discarded).toHaveLength(1);
  });
});

describe('importOne: responsiveness', () => {
  it('yields to the event loop between chunks', async () => {
    const bytes = jpegBytes({ scanBytes: 2.5 * MEBIBYTE });
    let timerFired = false;
    setTimeout(() => {
      timerFired = true;
    }, 0);
    let firedBeforeLastChunk: boolean | null = null;
    let writes = 0;
    const lastOriginalItem = 4;
    const { service } = await createHarness({
      bytes,
      wrapFileStore: (store) => ({
        ...store,
        writeObject: async (objectId, chunks) => {
          writes += 1;
          if (writes !== 1) return store.writeObject(objectId, chunks);
          return store.writeObject(
            objectId,
            observeChunks(chunks, (position) => {
              if (position === lastOriginalItem) firedBeforeLastChunk = timerFired;
            }),
          );
        },
      }),
    });

    expectOk(await service.importOne(pickerInput(), SLOTS));

    expect(firedBeforeLastChunk).toBe(true);
  });
});

describe('importVoiceNote (spec 25, D-95)', () => {
  it('stores an audio item with its duration and the bundled waveform thumbnail', async () => {
    const { service, calls } = await createHarness({ bytes: Uint8Array.of(1, 2, 3, 4) });
    const media = expectOk(await service.importVoiceNote(pickerInput({ origin: 'camera', fileName: null }), { durationMs: 4200, mimeType: 'audio/webm' }, { used: 0, limit: 20 }));
    expect(media.descriptor).toMatchObject({ kind: 'audio', mimeType: 'audio/webm', durationMs: 4200, width: 256, height: 256, originalPlaintextBytes: 4 });
    expect(media.descriptor.thumbnail.mimeType).toBe('image/webp');
    expect(calls.makeThumbnail).toBe(0);
    expect(calls.probe).toBe(0);
  });

  it('refuses when the entry is full', async () => {
    const { service } = await createHarness({ bytes: Uint8Array.of(1) });
    const outcome = await service.importVoiceNote(pickerInput(), { durationMs: 1000, mimeType: 'audio/webm' }, { used: 20, limit: 20 });
    expect(outcome).toMatchObject({ ok: false, reason: 'trayFull' });
  });
});
