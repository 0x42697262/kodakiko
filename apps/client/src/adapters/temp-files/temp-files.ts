import type { Sodium } from '@kodakiko/core';

export type TempPurpose = 'import' | 'view' | 'play';

export type TempFile = {
  readonly uri: string;
  readonly write: (bytes: Uint8Array) => Promise<void>;
  readonly close: () => Promise<void>;
  readonly delete: () => Promise<void>;
};

export interface TempFiles {
  readonly create: (purpose: TempPurpose, extension: string) => Promise<TempFile>;
  readonly wipe: () => Promise<void>;
  readonly wipePurpose: (purpose: TempPurpose) => Promise<void>;
  readonly list: () => Promise<readonly string[]>;
}

export type TempFilesDeps = {
  readonly sodium: Sodium;
  readonly activeAccountKey: () => string | null;
};

export { TempFilesUnsupportedError } from './temp-files-errors';

export declare function createTempFiles(deps: TempFilesDeps): TempFiles;
