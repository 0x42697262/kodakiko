import type { FileStore } from '../../adapters/filestore/filestore';
import { SELF_TEST_FAILURE_MESSAGE } from '../../constants/diagnostics-constants';
import { SELF_TEST_CHECKS } from '../../keys/self-test-keys';
import {
  SELF_TEST_ACCOUNT_KEY,
  SELF_TEST_CHUNKS,
  SELF_TEST_FAILED_OBJECT_ID,
  SELF_TEST_OBJECT_ID,
  SELF_TEST_ORPHAN_OBJECT_ID,
  SELF_TEST_PLAIN_MEDIA_ID,
  SELF_TEST_READ_LENGTH,
  SELF_TEST_READ_OFFSETS,
  SELF_TEST_SWEEP_AGE_MS,
  SELF_TEST_SWEEP_EVERYTHING_AGE_MS,
  SELF_TEST_TOTAL_BYTES,
} from './self-test-constants';
import { expectCheck, sameValue } from './self-test-support';

async function* chunkSource(chunks: readonly Uint8Array[]): AsyncGenerator<Uint8Array> {
  for (const chunk of chunks) yield chunk;
}

async function* failingChunkSource(): AsyncGenerator<Uint8Array> {
  yield SELF_TEST_CHUNKS[0] ?? new Uint8Array(0);
  throw new Error(SELF_TEST_FAILURE_MESSAGE);
}

async function checkWriteAndRead(fileStore: FileStore): Promise<void> {
  const written = await fileStore.writeObject(SELF_TEST_OBJECT_ID, chunkSource(SELF_TEST_CHUNKS));
  const size = await fileStore.size(SELF_TEST_OBJECT_ID);
  expectCheck(
    written === SELF_TEST_TOTAL_BYTES && size === SELF_TEST_TOTAL_BYTES,
    SELF_TEST_CHECKS.fileWriteSize,
  );
  const whole = SELF_TEST_CHUNKS.flatMap((chunk) => [...chunk]);
  for (const offset of SELF_TEST_READ_OFFSETS) {
    const bytes = await fileStore.readRange(SELF_TEST_OBJECT_ID, offset, SELF_TEST_READ_LENGTH);
    const expected = whole.slice(offset, offset + SELF_TEST_READ_LENGTH);
    expectCheck(sameValue([...bytes], expected), SELF_TEST_CHECKS.fileReadRange);
  }
  const sizes = await fileStore.listSizes();
  expectCheck(
    sizes.get(SELF_TEST_OBJECT_ID) === SELF_TEST_TOTAL_BYTES,
    SELF_TEST_CHECKS.fileListSizes,
  );
  const blob = await fileStore.readBlob(SELF_TEST_OBJECT_ID);
  const nativeUri = fileStore.nativeUri(SELF_TEST_OBJECT_ID);
  expectCheck(
    (blob === null) === (nativeUri !== null) &&
      (blob === null || blob.size === SELF_TEST_TOTAL_BYTES),
    SELF_TEST_CHECKS.fileBlob,
  );
}

async function checkFailedWrite(fileStore: FileStore): Promise<void> {
  const failure = await fileStore
    .writeObject(SELF_TEST_FAILED_OBJECT_ID, failingChunkSource())
    .catch((error: unknown) => error);
  const absent = async (): Promise<boolean> =>
    (await fileStore.size(SELF_TEST_FAILED_OBJECT_ID)) === null &&
    !(await fileStore.listSizes()).has(SELF_TEST_FAILED_OBJECT_ID);
  const absentBefore = await absent();
  await fileStore.open(SELF_TEST_ACCOUNT_KEY);
  expectCheck(
    failure instanceof Error && absentBefore && (await absent()),
    SELF_TEST_CHECKS.fileFailedWrite,
  );
}

async function checkPlainThumbnail(fileStore: FileStore): Promise<void> {
  const before = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID);
  await fileStore.writePlainThumbnail(SELF_TEST_PLAIN_MEDIA_ID, SELF_TEST_CHUNKS[0] ?? new Uint8Array(0));
  const written = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID);
  await fileStore.deletePlainThumbnail(SELF_TEST_PLAIN_MEDIA_ID);
  const after = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID);
  expectCheck(
    before === null && (written === null || typeof written === 'string') && after === null,
    SELF_TEST_CHECKS.filePlainThumbnail,
  );
}

async function checkSweepOrphans(fileStore: FileStore): Promise<void> {
  await fileStore.writeObject(SELF_TEST_ORPHAN_OBJECT_ID, chunkSource(SELF_TEST_CHUNKS));
  const known = new Set([SELF_TEST_OBJECT_ID, SELF_TEST_ORPHAN_OBJECT_ID]);
  await fileStore.sweepOrphans(known, SELF_TEST_SWEEP_EVERYTHING_AGE_MS);
  const keptKnown = (await fileStore.size(SELF_TEST_ORPHAN_OBJECT_ID)) !== null;
  await fileStore.sweepOrphans(new Set([SELF_TEST_OBJECT_ID]), SELF_TEST_SWEEP_AGE_MS);
  const keptYoung = (await fileStore.size(SELF_TEST_ORPHAN_OBJECT_ID)) !== null;
  await fileStore.sweepOrphans(new Set([SELF_TEST_OBJECT_ID]), SELF_TEST_SWEEP_EVERYTHING_AGE_MS);
  const removedUnknown = (await fileStore.size(SELF_TEST_ORPHAN_OBJECT_ID)) === null;
  const keptListed = (await fileStore.size(SELF_TEST_OBJECT_ID)) !== null;
  expectCheck(
    keptKnown && keptYoung && removedUnknown && keptListed,
    SELF_TEST_CHECKS.fileSweepOrphans,
  );
}

async function checkPlainSweep(fileStore: FileStore): Promise<void> {
  const sweep = (ageMs: number, knownMedia: ReadonlySet<string>) =>
    fileStore.sweepOrphans(new Set([SELF_TEST_OBJECT_ID]), ageMs, knownMedia);
  await fileStore.writePlainThumbnail(SELF_TEST_PLAIN_MEDIA_ID, SELF_TEST_CHUNKS[0] ?? new Uint8Array(0));
  const written = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID);
  await sweep(SELF_TEST_SWEEP_EVERYTHING_AGE_MS, new Set([SELF_TEST_PLAIN_MEDIA_ID]));
  const keptKnown = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID) === written;
  await sweep(SELF_TEST_SWEEP_AGE_MS, new Set());
  const keptYoung = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID) === written;
  await sweep(SELF_TEST_SWEEP_EVERYTHING_AGE_MS, new Set());
  const removed = fileStore.plainThumbnailUri(SELF_TEST_PLAIN_MEDIA_ID) === null;
  expectCheck(keptKnown && keptYoung && removed, SELF_TEST_CHECKS.filePlainSweep);
}

export async function fileStoreSelfTest(fileStore: FileStore): Promise<void> {
  await fileStore.open(SELF_TEST_ACCOUNT_KEY);
  try {
    await checkWriteAndRead(fileStore);
    await checkFailedWrite(fileStore);
    await checkPlainThumbnail(fileStore);
    await checkSweepOrphans(fileStore);
    await checkPlainSweep(fileStore);
    await fileStore.delete(SELF_TEST_OBJECT_ID);
    expectCheck((await fileStore.size(SELF_TEST_OBJECT_ID)) === null, SELF_TEST_CHECKS.fileDelete);
  } finally {
    await fileStore.destroy(SELF_TEST_ACCOUNT_KEY);
  }
}
