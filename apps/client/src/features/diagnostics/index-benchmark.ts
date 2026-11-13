import type { z } from 'zod';
import type { EntryMetadataV1, Id } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  KEY_BYTES,
  createGalleryIndex,
  sealJson,
  unwrapKey,
  wipe,
  wrapKey,
  type Sodium,
} from '@kodakiko/core';
import type { Clock } from '../../adapters/clock';
import type { EntryRow, MembershipRow } from '../../adapters/rowstore/rowstore';
import {
  INDEX_BENCHMARK_QUERY_PREFIX,
  INDEX_BENCHMARK_SPACING_MINUTES,
  INDEX_BENCHMARK_TAGS,
  INDEX_BENCHMARK_TAGS_PER_ENTRY,
  INDEX_BENCHMARK_WORDS_PER_NOTE,
  INDEX_BENCHMARK_WORD_LENGTH,
  INDEX_BENCHMARK_WORD_LETTERS,
  MS_PER_SECOND,
  UNLOCK_BUDGET_MS,
} from '../../constants/gallery-constants';
import { MS_PER_MINUTE } from '../../constants/entry-constants';
import { openEntryMetadata } from '../../services/entries/entries-service';
import { indexEntryRows } from '../../services/index/index-service';

type Metadata = z.infer<typeof EntryMetadataV1>;

export type IndexBenchmarkResult = {
  readonly entries: number;
  readonly unreadable: number;
  readonly buildMs: number;
  readonly entriesPerSecond: number;
  readonly queryMs: number;
  readonly withinBudget: boolean;
};

export type IndexBenchmarkDeps = {
  readonly sodium: Sodium;
  readonly randomId: () => string;
  readonly clock: Clock;
  readonly count: number;
};

const BENCHMARK_OWNER = 'benchmark-owner';
const BENCHMARK_COLLECTION = 'benchmark-collection';
const BENCHMARK_MIME = 'image/jpeg';
const BENCHMARK_THUMBNAIL_MIME = 'image/webp';
const BENCHMARK_SIDE = 512;
const BENCHMARK_BYTES = 1024;
const BENCHMARK_TAG_PREFIX = 'tag';

function randomWord(sodium: Sodium): string {
  const letters = sodium.randomBytes(INDEX_BENCHMARK_WORD_LENGTH);
  return Array.from(
    letters,
    (byte) => INDEX_BENCHMARK_WORD_LETTERS[byte % INDEX_BENCHMARK_WORD_LETTERS.length],
  ).join('');
}

function randomTags(sodium: Sodium): string[] {
  const picks = sodium.randomBytes(INDEX_BENCHMARK_TAGS_PER_ENTRY);
  return [...new Set(Array.from(picks, (byte) => `${BENCHMARK_TAG_PREFIX}${byte % INDEX_BENCHMARK_TAGS}`))].sort();
}

function syntheticMetadata(deps: IndexBenchmarkDeps, position: number): Metadata {
  const now = deps.clock.now();
  const takenAtUtc = new Date(now - position * INDEX_BENCHMARK_SPACING_MINUTES * MS_PER_MINUTE).toISOString();
  const words = Array.from({ length: INDEX_BENCHMARK_WORDS_PER_NOTE }, () => randomWord(deps.sodium));
  return {
    schemaVersion: 1,
    takenAtUtc,
    utcOffsetMinutes: 0,
    timeZone: null,
    note: words.join(' '),
    tags: randomTags(deps.sodium),
    location: null,
    media: [
      {
        mediaId: deps.randomId() as Id,
        kind: 'photo',
        mimeType: BENCHMARK_MIME,
        width: BENCHMARK_SIDE,
        height: BENCHMARK_SIDE,
        durationMs: null,
        originalPlaintextBytes: BENCHMARK_BYTES,
        thumbnail: {
          width: BENCHMARK_SIDE,
          height: BENCHMARK_SIDE,
          mimeType: BENCHMARK_THUMBNAIL_MIME,
          plaintextBytes: BENCHMARK_BYTES,
        },
        capturedAtUtc: null,
        originalFileName: null,
      },
    ],
    createdAtUtc: takenAtUtc,
    editedAtUtc: takenAtUtc,
  };
}

export async function runIndexBenchmark(deps: IndexBenchmarkDeps): Promise<IndexBenchmarkResult> {
  const { sodium } = deps;
  const collectionKey = sodium.randomBytes(KEY_BYTES);
  const entries: EntryRow[] = [];
  const memberships: MembershipRow[] = [];
  const nowIso = deps.clock.nowIso();
  for (let position = 0; position < deps.count; position += 1) {
    const entryId = deps.randomId();
    const entryKey = sodium.randomBytes(KEY_BYTES);
    entries.push({
      id: entryId,
      ownerId: BENCHMARK_OWNER,
      purpose: 'journal',
      revision: 1,
      metadataBlob: sealJson(sodium, entryKey, syntheticMetadata(deps, position), AD_CONTEXTS.entryMetadata, entryId),
      baseMetadataBlob: null,
      baseRevision: 1,
      serverPendingBlob: null,
      serverPendingRevision: null,
      trashedAt: null,
      createdAt: nowIso,
      updatedAt: nowIso,
      localState: 'synced',
    });
    memberships.push({
      collectionId: BENCHMARK_COLLECTION,
      entryId,
      entryKeyWrap: wrapKey(sodium, collectionKey, entryKey, AD_CONTEXTS.entryKeyByCollection, `${BENCHMARK_COLLECTION}/${entryId}`),
      seq: 0,
    });
    wipe(entryKey);
  }
  const wraps = new Map(memberships.map((row) => [row.entryId, row.entryKeyWrap]));
  const index = createGalleryIndex({ homeCollectionId: BENCHMARK_COLLECTION });
  const started = deps.clock.now();
  const counts = await indexEntryRows(
    { entries, memberships, media: [], objects: [] },
    BENCHMARK_OWNER,
    async (row) => {
      const entryKey = unwrapKey(
        sodium,
        collectionKey,
        wraps.get(row.id) ?? new Uint8Array(0),
        AD_CONTEXTS.entryKeyByCollection,
        `${BENCHMARK_COLLECTION}/${row.id}`,
      );
      try {
        return openEntryMetadata(sodium, entryKey, row.id, row.metadataBlob);
      } finally {
        wipe(entryKey);
      }
    },
    (entry, noteText) => index.upsert(entry, noteText),
  );
  const buildMs = deps.clock.now() - started;
  const queryStarted = deps.clock.now();
  index.query({
    scope: { kind: 'home' },
    view: 'journal',
    tags: [`${BENCHMARK_TAG_PREFIX}0`],
    fromDate: null,
    toDate: null,
    text: INDEX_BENCHMARK_QUERY_PREFIX,
  });
  const queryMs = deps.clock.now() - queryStarted;
  wipe(collectionKey);
  return {
    entries: deps.count,
    unreadable: counts.unreadable,
    buildMs,
    entriesPerSecond: buildMs === 0 ? deps.count : Math.round((deps.count * MS_PER_SECOND) / buildMs),
    queryMs,
    withinBudget: buildMs <= UNLOCK_BUDGET_MS,
  };
}
