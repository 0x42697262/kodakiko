import type { FileStore } from '../filestore/filestore';

export type UploadPart = {
  readonly partNumber: number;
  readonly url: string;
  readonly offset: number;
  readonly length: number;
};

export type UploadJob = { readonly objectId: string; readonly parts: readonly UploadPart[] };

export type UploadFailureReason = 'expired' | 'network' | 'http' | 'missingFile';

export type UploadEvent =
  | { readonly type: 'progress'; readonly objectId: string; readonly sentBytes: number }
  | { readonly type: 'completed'; readonly objectId: string }
  | { readonly type: 'failed'; readonly objectId: string; readonly reason: UploadFailureReason }
  | { readonly type: 'stopped'; readonly reason: 'timeout' | 'cancelled' };

export type UploadStatus = 'queued' | 'uploading' | 'completed' | 'failed';

export interface Uploader {
  readonly enqueue: (jobs: readonly UploadJob[]) => Promise<void>;
  readonly statuses: () => Promise<ReadonlyMap<string, UploadStatus>>;
  readonly subscribe: (listener: (event: UploadEvent) => void) => () => void;
  readonly cancelAll: () => Promise<void>;
}

export type UploaderDeps = { readonly fileStore: FileStore };

export declare function createUploader(deps: UploaderDeps): Uploader;
