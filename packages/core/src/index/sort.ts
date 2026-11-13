import { SORT_BEFORE } from './constants.ts';
import type { GallerySort, IndexedEntry } from './types.ts';

const SORT_AFTER = 1;

const RANDOM_MULTIPLIER = 1_103_515_245;
const RANDOM_INCREMENT = 12_345;
const RANDOM_MODULUS = 2_147_483_648;

function seededRandom(seed: number): () => number {
  let state = Math.abs(Math.trunc(seed)) % RANDOM_MODULUS;
  return () => {
    state = (state * RANDOM_MULTIPLIER + RANDOM_INCREMENT) % RANDOM_MODULUS;
    return state / RANDOM_MODULUS;
  };
}

function measure(entry: IndexedEntry, key: GallerySort['key']): number | string {
  switch (key) {
    case 'noteLength':
      return entry.notePreview.length;
    case 'mediaCount':
      return entry.mediaCount;
    default:
      return entry.takenAtUtc;
  }
}

export function sortEntryIds(
  ids: readonly string[],
  get: (id: string) => IndexedEntry | null,
  sort: GallerySort,
  seed: number,
): string[] {
  if (sort.key === 'random') {
    const shuffled = [...ids];
    const next = seededRandom(seed);
    for (let position = shuffled.length - 1; position > 0; position -= 1) {
      const other = Math.floor(next() * (position + 1));
      [shuffled[position], shuffled[other]] = [shuffled[other] ?? '', shuffled[position] ?? ''];
    }
    return shuffled;
  }
  const direction = sort.descending ? SORT_BEFORE : SORT_AFTER;
  return [...ids].sort((left, right) => {
    const a = get(left);
    const b = get(right);
    if (a === null || b === null) return a === null ? SORT_AFTER : SORT_BEFORE;
    const ma = measure(a, sort.key);
    const mb = measure(b, sort.key);
    if (ma !== mb) return (ma < mb ? SORT_BEFORE : SORT_AFTER) * direction;
    if (a.takenAtUtc !== b.takenAtUtc) return a.takenAtUtc < b.takenAtUtc ? SORT_AFTER : SORT_BEFORE;
    return left < right ? SORT_BEFORE : SORT_AFTER;
  });
}
