import type { ByteSource, MediaFormat } from '@kodakiko/core';

export type PickerExif = Readonly<Record<string, unknown>>;

export type PlatformMediaHandle = { readonly uri: string } | { readonly blob: Blob };

export type MediaInput = {
  readonly origin: 'camera' | 'picker' | 'drop' | 'paste';
  readonly handle: PlatformMediaHandle;
  readonly fileName: string | null;
  readonly pickerExif: PickerExif | null;
};

export type ProbeResult = {
  readonly width: number;
  readonly height: number;
  readonly durationMs: number | null;
  readonly decodable: boolean;
};

export type ThumbnailResult = {
  readonly bytes: Uint8Array;
  readonly width: number;
  readonly height: number;
};

export interface MediaToolkit {
  readonly byteSource: (input: MediaInput) => Promise<ByteSource>;
  readonly probe: (input: MediaInput, format: MediaFormat) => Promise<ProbeResult>;
  readonly makeThumbnail: (
    input: MediaInput,
    format: MediaFormat,
    posterTimeMs: number,
  ) => Promise<ThumbnailResult>;
  readonly modifiedUtc: (input: MediaInput) => Promise<string | null>;
  readonly discard: (input: MediaInput) => Promise<void>;
}

export declare function createMediaToolkit(): MediaToolkit;
