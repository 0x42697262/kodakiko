import {
  AbortMultipartUploadCommand,
  CompleteMultipartUploadCommand,
  CreateMultipartUploadCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  ListPartsCommand,
  PutObjectCommand,
  S3Client,
  UploadPartCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import {
  CONTENT_RANGE_TOTAL_SEPARATOR,
  OBJECT_CONTENT_TYPE,
  RANGE_HEADER_PREFIX,
  S3_CHECKSUM_MODE,
  S3_LIST_PARTS_PAGE_SIZE,
  STORAGE_RETRY_ATTEMPTS,
  STORAGE_RETRY_BASE_DELAY_MS,
  STORAGE_RETRY_CAP_DELAY_MS,
} from '../../config/constants.ts';
import type { StorageSettings } from '../../config/settings.ts';
import { STORAGE_TEXT } from '../../text/storage.ts';
import { isNoSuchUpload, isNotFound, isTransient } from './error-classification.ts';
import {
  ObjectMissingError,
  StorageUnavailableError,
  UploadMissingError,
} from './errors.ts';
import type {
  ByteRange,
  CompletedPart,
  ObjectStore,
  ObjectStream,
  UploadedPart,
} from './object-store.ts';
import { withRetry, type RetryOptions } from './retry.ts';

export type S3Clients = { readonly internalClient: S3Client; readonly presignClient: S3Client };

function createClient(config: StorageSettings, endpoint: string): S3Client {
  return new S3Client({
    endpoint,
    region: config.region,
    forcePathStyle: config.forcePathStyle,
    credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
    requestHandler: { requestTimeout: config.requestTimeoutMs },
    maxAttempts: 1,
    requestChecksumCalculation: S3_CHECKSUM_MODE,
    responseChecksumValidation: S3_CHECKSUM_MODE,
  });
}

export function createS3Clients(config: StorageSettings): S3Clients {
  return {
    internalClient: createClient(config, config.endpoint),
    presignClient: createClient(config, config.publicEndpoint),
  };
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

const RETRY_OPTIONS: RetryOptions = Object.freeze({
  attempts: STORAGE_RETRY_ATTEMPTS,
  baseDelayMs: STORAGE_RETRY_BASE_DELAY_MS,
  capDelayMs: STORAGE_RETRY_CAP_DELAY_MS,
  isRetryable: isTransient,
  random: Math.random,
  sleep,
});

function toStorageError(error: unknown): Error {
  if (
    error instanceof StorageUnavailableError ||
    error instanceof ObjectMissingError ||
    error instanceof UploadMissingError
  ) {
    return error;
  }
  return new StorageUnavailableError(error);
}

async function guarded<Result>(
  operation: () => Promise<Result>,
  options: { readonly retried: boolean },
): Promise<Result> {
  try {
    return await (options.retried ? withRetry(operation, RETRY_OPTIONS) : operation());
  } catch (error) {
    throw toStorageError(error);
  }
}

function totalSizeOf(contentRange: string | undefined, contentLength: number): number {
  const total = contentRange?.split(CONTENT_RANGE_TOTAL_SEPARATOR)[1];
  return total === undefined ? contentLength : Number(total);
}

export function createS3ObjectStore(config: StorageSettings): ObjectStore {
  const { internalClient, presignClient } = createS3Clients(config);
  const Bucket = config.bucket;

  async function headObject(key: string) {
    return guarded(
      async () => {
        try {
          const head = await internalClient.send(new HeadObjectCommand({ Bucket, Key: key }));
          return { sizeBytes: head.ContentLength ?? 0, etag: head.ETag ?? '' };
        } catch (error) {
          if (isNotFound(error)) return null;
          throw error;
        }
      },
      { retried: true },
    );
  }

  async function listParts(key: string, uploadId: string): Promise<readonly UploadedPart[]> {
    return guarded(
      async () => {
        const collected: UploadedPart[] = [];
        let marker: string | undefined;
        do {
          let page;
          try {
            page = await internalClient.send(
              new ListPartsCommand({
                Bucket,
                Key: key,
                UploadId: uploadId,
                MaxParts: S3_LIST_PARTS_PAGE_SIZE,
                ...(marker === undefined ? {} : { PartNumberMarker: marker }),
              }),
            );
          } catch (error) {
            if (isNoSuchUpload(error)) throw new UploadMissingError(STORAGE_TEXT.uploadMissing);
            throw error;
          }
          for (const part of page.Parts ?? []) {
            collected.push({
              partNumber: part.PartNumber ?? 0,
              etag: part.ETag ?? '',
              sizeBytes: part.Size ?? 0,
            });
          }
          marker = page.IsTruncated === true ? page.NextPartNumberMarker : undefined;
        } while (marker !== undefined);
        return collected;
      },
      { retried: true },
    );
  }

  async function completeMultipartUpload(
    key: string,
    uploadId: string,
    parts: readonly CompletedPart[],
  ): Promise<{ etag: string }> {
    return guarded(
      async () => {
        try {
          const completed = await internalClient.send(
            new CompleteMultipartUploadCommand({
              Bucket,
              Key: key,
              UploadId: uploadId,
              MultipartUpload: {
                Parts: parts.map((part) => ({ PartNumber: part.partNumber, ETag: part.etag })),
              },
            }),
          );
          return { etag: completed.ETag ?? '' };
        } catch (error) {
          if (isNoSuchUpload(error)) throw new UploadMissingError(STORAGE_TEXT.uploadMissing);
          throw error;
        }
      },
      { retried: false },
    );
  }

  async function abortMultipartUpload(key: string, uploadId: string): Promise<void> {
    return guarded(
      async () => {
        try {
          await internalClient.send(
            new AbortMultipartUploadCommand({ Bucket, Key: key, UploadId: uploadId }),
          );
        } catch (error) {
          if (!isNoSuchUpload(error) && !isNotFound(error)) throw error;
        }
      },
      { retried: true },
    );
  }

  async function deleteObject(key: string): Promise<void> {
    return guarded(
      async () => {
        try {
          await internalClient.send(new DeleteObjectCommand({ Bucket, Key: key }));
        } catch (error) {
          if (!isNotFound(error)) throw error;
        }
      },
      { retried: true },
    );
  }

  async function headBucket(): Promise<boolean> {
    return guarded(
      async () => {
        try {
          await internalClient.send(new HeadBucketCommand({ Bucket }));
          return true;
        } catch (error) {
          if (isNotFound(error)) return false;
          throw error;
        }
      },
      { retried: true },
    );
  }

  async function getObjectStream(key: string, range: ByteRange | null): Promise<ObjectStream> {
    return guarded(
      async () => {
        try {
          const response = await internalClient.send(
            new GetObjectCommand({
              Bucket,
              Key: key,
              ...(range === null
                ? {}
                : { Range: `${RANGE_HEADER_PREFIX}${range.start}-${range.end}` }),
            }),
          );
          if (response.Body === undefined) throw new Error(STORAGE_TEXT.emptyBody);
          const sizeBytes = response.ContentLength ?? 0;
          return {
            body: response.Body as NodeJS.ReadableStream,
            sizeBytes,
            totalSizeBytes: totalSizeOf(response.ContentRange, sizeBytes),
          };
        } catch (error) {
          if (isNotFound(error)) throw new ObjectMissingError(STORAGE_TEXT.objectMissing);
          throw error;
        }
      },
      { retried: false },
    );
  }

  return {
    headObject,
    listParts,
    completeMultipartUpload,
    abortMultipartUpload,
    deleteObject,
    headBucket,
    getObjectStream,

    createMultipartUpload: (key) =>
      guarded(
        async () => {
          const created = await internalClient.send(
            new CreateMultipartUploadCommand({
              Bucket,
              Key: key,
              ContentType: OBJECT_CONTENT_TYPE,
            }),
          );
          if (created.UploadId === undefined) throw new Error(STORAGE_TEXT.noUploadId);
          return created.UploadId;
        },
        { retried: false },
      ),

    presignPut: (key, sizeBytes, expiresInSeconds) =>
      guarded(
        () =>
          getSignedUrl(
            presignClient,
            new PutObjectCommand({
              Bucket,
              Key: key,
              ContentType: OBJECT_CONTENT_TYPE,
              ContentLength: sizeBytes,
            }),
            { expiresIn: expiresInSeconds },
          ),
        { retried: false },
      ),

    presignPart: (key, uploadId, partNumber, partSizeBytes, expiresInSeconds) =>
      guarded(
        () =>
          getSignedUrl(
            presignClient,
            new UploadPartCommand({
              Bucket,
              Key: key,
              UploadId: uploadId,
              PartNumber: partNumber,
              ContentLength: partSizeBytes,
            }),
            { expiresIn: expiresInSeconds },
          ),
        { retried: false },
      ),

    presignGet: (key, expiresInSeconds) =>
      guarded(
        () =>
          getSignedUrl(presignClient, new GetObjectCommand({ Bucket, Key: key }), {
            expiresIn: expiresInSeconds,
          }),
        { retried: false },
      ),
  };
}
