export class DownloadSizeMismatchError extends Error {
  readonly expected: number;
  readonly actual: number;
  constructor(expected: number, actual: number) {
    super(`Downloaded ${actual} bytes, expected ${expected}.`);
    this.expected = expected;
    this.actual = actual;
  }
}

export class DownloadHttpError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}
