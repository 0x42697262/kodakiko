import { describe, expect, it } from 'vitest';
import { createGalleryIndex, localDateOf, type IndexedEntry } from '../../src/index.ts';

const HOME = 'home';
const ENTRY_COUNT = 20_000;
const WORDS_PER_ENTRY = 30;
const VOCABULARY = 5_000;
const TAG_COUNT = 40;
const BUDGET_MS = 50;
const MINUTE_MS = 60_000;
const START_MS = Date.parse('2026-11-11T12:00:00.000Z');

function seededRandom(seed: number): () => number {
  let state = seed;
  return () => {
    state = (state * 1_103_515_245 + 12_345) % 2_147_483_648;
    return state / 2_147_483_648;
  };
}

function word(position: number): string {
  let value = position;
  let text = '';
  do {
    text += String.fromCharCode(97 + (value % 26));
    value = Math.floor(value / 26);
  } while (value > 0);
  return `${text}word`;
}

describe('gallery index performance', () => {
  it('queries 20,000 entries with one tag and a 3-letter prefix (target < 50 ms)', () => {
    const random = seededRandom(7);
    const index = createGalleryIndex({ homeCollectionId: HOME });
    for (let position = 0; position < ENTRY_COUNT; position += 1) {
      const takenAtUtc = new Date(START_MS - position * 37 * MINUTE_MS).toISOString();
      const localDate = localDateOf(takenAtUtc, 0);
      const entry: IndexedEntry = {
        id: `entry-${position}`,
        isOwn: true,
        collectionIds: [HOME],
        trashed: false,
        unreadable: false,
        takenAtUtc,
        utcOffsetMinutes: 0,
        localDate,
        monthDay: localDate.slice(5),
        tags: [`tag${Math.floor(random() * TAG_COUNT)}`, `tag${Math.floor(random() * TAG_COUNT)}`],
        coverMediaId: `media-${position}`,
        mediaCount: 1,
        hasVideo: false,
        notePreview: '',
        pending: false,
      };
      const note = Array.from({ length: WORDS_PER_ENTRY }, () =>
        word(Math.floor(random() * VOCABULARY)),
      ).join(' ');
      index.upsert(entry, note);
    }
    const filter = {
      scope: { kind: 'home' },
      view: 'journal',
      tags: ['tag3'],
      fromDate: null,
      toDate: null,
      text: 'abc',
    } as const;
    index.query({ ...filter, text: '' });
    const started = performance.now();
    const sections = index.query(filter);
    const elapsedMs = performance.now() - started;
    console.info(`gallery index query over ${ENTRY_COUNT} entries: ${elapsedMs.toFixed(2)} ms (target ${BUDGET_MS} ms)`);
    const ids = sections.flatMap((section) => [...section.entryIds]);
    expect(ids.length).toBeGreaterThan(0);
    for (const id of ids) expect(index.get(id)?.tags).toContain('tag3');
  });
});
