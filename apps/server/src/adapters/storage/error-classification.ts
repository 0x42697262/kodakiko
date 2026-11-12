import {
  HTTP_STATUS,
  S3_NOT_FOUND_ERROR_NAMES,
  S3_NO_SUCH_UPLOAD_ERROR_NAME,
} from '../../config/constants.ts';
import { ObjectMissingError, StorageUnavailableError, UploadMissingError } from './errors.ts';

type SdkError = {
  readonly name?: string;
  readonly $metadata?: { readonly httpStatusCode?: number };
};

export function statusOf(error: unknown): number | undefined {
  return (error as SdkError).$metadata?.httpStatusCode;
}

export function isNotFound(error: unknown): boolean {
  return S3_NOT_FOUND_ERROR_NAMES.includes((error as SdkError).name ?? '');
}

export function isNoSuchUpload(error: unknown): boolean {
  return (error as SdkError).name === S3_NO_SUCH_UPLOAD_ERROR_NAME;
}

export function isTransient(error: unknown): boolean {
  if (
    error instanceof StorageUnavailableError ||
    error instanceof ObjectMissingError ||
    error instanceof UploadMissingError
  ) {
    return false;
  }
  const status = statusOf(error);
  if (status === undefined) return true;
  return status >= HTTP_STATUS.serverErrorMin || status === HTTP_STATUS.tooManyRequests;
}
