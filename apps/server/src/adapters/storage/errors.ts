export class StorageUnavailableError extends Error {
  constructor(cause: unknown) {
    super(cause instanceof Error ? cause.message : String(cause), { cause });
    this.name = 'StorageUnavailableError';
  }
}

export class ObjectMissingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ObjectMissingError';
  }
}

export class UploadMissingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UploadMissingError';
  }
}
