export type CompletedPart = { readonly partNumber: number; readonly etag: string };
export type UploadedPart = {
  readonly partNumber: number;
  readonly etag: string;
  readonly sizeBytes: number;
};
export type ByteRange = { readonly start: number; readonly end: number };
export type ObjectStream = {
  readonly body: NodeJS.ReadableStream;
  readonly sizeBytes: number;
  readonly totalSizeBytes: number;
};

export interface ObjectStore {
  readonly headObject: (key: string) => Promise<{ sizeBytes: number; etag: string } | null>;
  readonly presignPut: (
    key: string,
    sizeBytes: number,
    expiresInSeconds: number,
  ) => Promise<string>;
  readonly createMultipartUpload: (key: string) => Promise<string>;
  readonly presignPart: (
    key: string,
    uploadId: string,
    partNumber: number,
    partSizeBytes: number,
    expiresInSeconds: number,
  ) => Promise<string>;
  readonly listParts: (key: string, uploadId: string) => Promise<readonly UploadedPart[]>;
  readonly completeMultipartUpload: (
    key: string,
    uploadId: string,
    parts: readonly CompletedPart[],
  ) => Promise<{ etag: string }>;
  readonly abortMultipartUpload: (key: string, uploadId: string) => Promise<void>;
  readonly presignGet: (key: string, expiresInSeconds: number) => Promise<string>;
  readonly deleteObject: (key: string) => Promise<void>;
  readonly headBucket: () => Promise<boolean>;
  readonly getObjectStream: (key: string, range: ByteRange | null) => Promise<ObjectStream>;
}
