import { describe, expect, it } from 'vitest';
import {
  isNoSuchUpload,
  isNotFound,
  isTransient,
} from '../../src/adapters/storage/error-classification.ts';
import {
  ObjectMissingError,
  StorageUnavailableError,
  UploadMissingError,
} from '../../src/adapters/storage/errors.ts';

function sdkError(name: string, httpStatusCode?: number): Error {
  const error = new Error(name);
  error.name = name;
  return Object.assign(error, {
    $metadata: httpStatusCode === undefined ? {} : { httpStatusCode },
  });
}

describe('isNotFound', () => {
  it('treats NotFound and NoSuchKey as missing', () => {
    expect(isNotFound(sdkError('NotFound', 404))).toBe(true);
    expect(isNotFound(sdkError('NoSuchKey', 404))).toBe(true);
  });

  it('does not treat NoSuchBucket or any other 404 as a missing object', () => {
    expect(isNotFound(sdkError('NoSuchBucket', 404))).toBe(false);
    expect(isNotFound(sdkError('NoSuchUpload', 404))).toBe(false);
    expect(isNotFound(sdkError('SomethingElse', 404))).toBe(false);
    expect(isNotFound(sdkError('AccessDenied', 403))).toBe(false);
  });
});

describe('isNoSuchUpload', () => {
  it('matches only NoSuchUpload', () => {
    expect(isNoSuchUpload(sdkError('NoSuchUpload', 404))).toBe(true);
    expect(isNoSuchUpload(sdkError('NoSuchKey', 404))).toBe(false);
  });
});

describe('isTransient', () => {
  it('retries network errors, 5xx and 429', () => {
    expect(isTransient(sdkError('TimeoutError'))).toBe(true);
    expect(isTransient(sdkError('InternalError', 500))).toBe(true);
    expect(isTransient(sdkError('ServiceUnavailable', 503))).toBe(true);
    expect(isTransient(sdkError('SlowDown', 429))).toBe(true);
  });

  it('does not retry client errors', () => {
    expect(isTransient(sdkError('AccessDenied', 403))).toBe(false);
    expect(isTransient(sdkError('NotFound', 404))).toBe(false);
  });

  it('does not retry the adapter own error types', () => {
    expect(isTransient(new StorageUnavailableError(new Error('down')))).toBe(false);
    expect(isTransient(new ObjectMissingError('gone'))).toBe(false);
    expect(isTransient(new UploadMissingError('gone'))).toBe(false);
  });
});
