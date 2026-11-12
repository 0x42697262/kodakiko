export const STORAGE_TEXT = Object.freeze({
  uploadMissing: 'The multipart upload no longer exists.',
  objectMissing: 'The object does not exist.',
  emptyBody: 'The storage response had no body.',
  noUploadId: 'The storage response had no upload id.',
  bucketNotFound: 'The bucket does not exist.',
  corsApplied: 'Applied bucket CORS rule:',
  corsFailed: (message: string) => `Could not apply the bucket CORS rule: ${message}`,
});
