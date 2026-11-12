import type { FileStore } from '../../adapters/filestore/filestore';
import { SELF_TEST_FAILURE_MESSAGE } from '../../constants/diagnostics-constants';
import { SELF_TEST_CHECKS } from '../../keys/self-test-keys';
import {
  SELF_TEST_ACCOUNT_KEY,
  SELF_TEST_CHUNKS,
  SELF_TEST_FAILED_OBJECT_ID,
  SELF_TEST_OBJECT_ID,
  SELF_TEST_READ_LENGTH,
  SELF_TEST_READ_OFFSETS,
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

export async function fileStoreSelfTest(fileStore: FileStore): Promise<void> {
  await fileStore.open(SELF_TEST_ACCOUNT_KEY);
  try {
    await checkWriteAndRead(fileStore);
    await checkFailedWrite(fileStore);
    await fileStore.delete(SELF_TEST_OBJECT_ID);
    expectCheck((await fileStore.size(SELF_TEST_OBJECT_ID)) === null, SELF_TEST_CHECKS.fileDelete);
  } finally {
    await fileStore.destroy(SELF_TEST_ACCOUNT_KEY);
  }
}
