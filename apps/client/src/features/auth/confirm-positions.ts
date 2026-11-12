import { RANDOM_INDEX_BYTES, RANDOM_INDEX_LIMIT } from '../../constants/auth-constants';

export function pickConfirmPositions(
  randomBytes: (length: number) => Uint8Array,
  total: number,
  count: number,
): readonly number[] {
  const limit = RANDOM_INDEX_LIMIT - (RANDOM_INDEX_LIMIT % total);
  const chosen = new Set<number>();
  while (chosen.size < count) {
    const value = randomBytes(RANDOM_INDEX_BYTES)[0] ?? limit;
    if (value < limit) chosen.add((value % total) + 1);
  }
  return [...chosen].sort((left, right) => left - right);
}
