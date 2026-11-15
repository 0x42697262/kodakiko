import { TAG_MAX_CHARS } from '@kodakiko/contract';
import { localDateOf } from '../../src/domain/index.ts';
import {
  MAX_INDEX_TOKENS_PER_ENTRY,
  MONTH_DAY_START,
  attributesMatch,
  createGalleryIndex,
  sortEntryIds,
  tokenize,
  type AttributeFilters,
  type GalleryFilter,
  type GalleryIndex,
  type GallerySort,
  type IndexedEntry,
} from '../../src/index/index.ts';
import { JSON_INDENT, functionCase, toJson, type GoldenCase, type GoldenWriter, type Json } from './shared.ts';

const PATHS = Object.freeze({
  tokenize: 'index/tokenize.json',
  galleryQuery: 'index/gallery-query.json',
  onThisDay: 'index/on-this-day.json',
});

const INPUT_DIRECTORY = 'index/inputs';
const SYNTHETIC_FILE_NAME = 'synthetic-entries';
const JSON_EXTENSION = '.json';

const HOME = 'home-collection';
const SHARED = 'shared-collection';
const OTHER = 'other-collection';
const DEFAULT_TAKEN_AT = '2026-11-11T12:00:00.000Z';
const MS_PER_HOUR = 3_600_000;
const MS_PER_DAY = 86_400_000;
const BASE_MS = Date.parse(DEFAULT_TAKEN_AT);
const SYNTHETIC_COUNT = 50;
const SYNTHETIC_PER_DAY = 3;
const SYNTHETIC_ID_DIGITS = 2;
const SYNTHETIC_TRASH_EVERY = 10;
const SYNTHETIC_TRASH_REMAINDER = 9;
const SYNTHETIC_SHARED_EVERY = 7;
const SYNTHETIC_TAG_DIVISOR_OFFSET = 2;
const SYNTHETIC_SECOND_WORD_OFFSET = 3;
const WORDS = ['beach', 'sunset', 'lake', 'forest', 'city', 'dinner', 'family', 'river'];
const TAGS = ['a', 'b', 'c', 'd'];
const TOKEN_OVERFLOW = 20;
const SUGGESTION_LIMIT = 10;
const SUGGESTION_SMALL_LIMIT = 2;
const SHUFFLE_COUNT = 20;
const OFFSET_PLUS_ONE_HOUR = 60;
const OFFSET_MINUS_FIVE_HOURS = -300;
const SEED = Object.freeze({ first: 7, second: 8, negativeFraction: -7.9, aboveModulus: 2_147_483_655, maxBelowModulus: 2_147_483_647, small: 3, single: 5 });

const FN = Object.freeze({
  tokenize: 'tokenize',
  query: 'GalleryIndex.query',
  get: 'GalleryIndex.get',
  size: 'GalleryIndex.size',
  version: 'GalleryIndex.version',
  allTags: 'GalleryIndex.allTags',
  tagSuggestions: 'GalleryIndex.tagSuggestions',
  onThisDay: 'GalleryIndex.onThisDay',
  daysWithEntries: 'GalleryIndex.daysWithEntries',
  sortEntryIds: 'sortEntryIds',
  attributesMatch: 'attributesMatch',
});

type Scope = GalleryFilter['scope'];

type Step =
  | { readonly method: 'upsert'; readonly args: readonly [IndexedEntry, string] }
  | { readonly method: 'remove'; readonly args: readonly [string] }
  | { readonly method: 'clear'; readonly args: readonly [] }
  | { readonly method: 'get'; readonly args: readonly [string] }
  | { readonly method: 'size'; readonly args: readonly [] }
  | { readonly method: 'version'; readonly args: readonly [] }
  | { readonly method: 'query'; readonly args: readonly [GalleryFilter] }
  | { readonly method: 'allTags'; readonly args: readonly [] }
  | { readonly method: 'tagSuggestions'; readonly args: readonly [string, number] }
  | { readonly method: 'onThisDay'; readonly args: readonly [string, Scope] }
  | { readonly method: 'daysWithEntries'; readonly args: readonly [string, Scope] };

type Scenario = {
  readonly synthetic?: boolean;
  readonly setup?: readonly Step[];
  readonly calls: readonly Step[];
};

type EntryOverrides = Partial<Omit<IndexedEntry, 'localDate' | 'monthDay'>>;

function entry(id: string, overrides: EntryOverrides = {}): IndexedEntry {
  const takenAtUtc = overrides.takenAtUtc ?? DEFAULT_TAKEN_AT;
  const utcOffsetMinutes = overrides.utcOffsetMinutes ?? 0;
  const localDate = localDateOf(takenAtUtc, utcOffsetMinutes);
  return {
    id,
    isOwn: true,
    collectionIds: [HOME],
    trashed: false,
    unreadable: false,
    tags: [],
    coverMediaId: `${id}-media`,
    mediaCount: 1,
    hasVideo: false,
    notePreview: '',
    pending: false,
    ...overrides,
    takenAtUtc,
    utcOffsetMinutes,
    localDate,
    monthDay: localDate.slice(MONTH_DAY_START),
  };
}

function filter(overrides: Partial<GalleryFilter> = {}): GalleryFilter {
  return { scope: HOME_SCOPE, view: 'journal', tags: [], fromDate: null, toDate: null, text: '', ...overrides };
}

const HOME_SCOPE: Scope = { kind: 'home' };
const SHARED_SCOPE: Scope = { kind: 'collection', collectionId: SHARED };

const step = {
  upsert: (value: IndexedEntry, note = ''): Step => ({ method: 'upsert', args: [value, note] }),
  remove: (id: string): Step => ({ method: 'remove', args: [id] }),
  clear: (): Step => ({ method: 'clear', args: [] }),
  get: (id: string): Step => ({ method: 'get', args: [id] }),
  size: (): Step => ({ method: 'size', args: [] }),
  version: (): Step => ({ method: 'version', args: [] }),
  query: (value: GalleryFilter = filter()): Step => ({ method: 'query', args: [value] }),
  allTags: (): Step => ({ method: 'allTags', args: [] }),
  tagSuggestions: (prefix: string, limit: number): Step => ({ method: 'tagSuggestions', args: [prefix, limit] }),
  onThisDay: (today: string, scope: Scope = HOME_SCOPE): Step => ({ method: 'onThisDay', args: [today, scope] }),
  daysWithEntries: (yearMonth: string, scope: Scope = HOME_SCOPE): Step => ({ method: 'daysWithEntries', args: [yearMonth, scope] }),
};

function run(index: GalleryIndex, value: Step): unknown {
  switch (value.method) {
    case 'upsert':
      index.upsert(value.args[0], value.args[1]);
      return null;
    case 'remove':
      index.remove(value.args[0]);
      return null;
    case 'clear':
      index.clear();
      return null;
    case 'get':
      return index.get(value.args[0]);
    case 'size':
      return index.size();
    case 'version':
      return index.version();
    case 'query':
      return index.query(value.args[0]);
    case 'allTags':
      return index.allTags();
    case 'tagSuggestions':
      return index.tagSuggestions(value.args[0], value.args[1]);
    case 'onThisDay':
      return index.onThisDay(value.args[0], value.args[1]);
    default:
      return index.daysWithEntries(value.args[0], value.args[1]);
  }
}

function syntheticSteps(): readonly Step[] {
  return Array.from({ length: SYNTHETIC_COUNT }, (_, index) => {
    const id = `e${String(index).padStart(SYNTHETIC_ID_DIGITS, '0')}`;
    const dayBack = Math.floor(index / SYNTHETIC_PER_DAY);
    const hourBack = index % SYNTHETIC_PER_DAY;
    const takenAtUtc = new Date(BASE_MS - dayBack * MS_PER_DAY - hourBack * MS_PER_HOUR).toISOString();
    const tags = TAGS.filter((_, tagIndex) => index % (tagIndex + SYNTHETIC_TAG_DIVISOR_OFFSET) === 0);
    const note = `${WORDS[index % WORDS.length] ?? ''} ${WORDS[(index + SYNTHETIC_SECOND_WORD_OFFSET) % WORDS.length] ?? ''} number${index}`;
    return step.upsert(
      entry(id, {
        takenAtUtc,
        tags,
        trashed: index % SYNTHETIC_TRASH_EVERY === SYNTHETIC_TRASH_REMAINDER,
        collectionIds: index % SYNTHETIC_SHARED_EVERY === 0 ? [HOME, SHARED] : [HOME],
      }),
      note,
    );
  });
}

class GalleryCases {
  private syntheticFile: Json | null = null;
  readonly cases: GoldenCase[] = [];
  private readonly writer: GoldenWriter;

  constructor(writer: GoldenWriter) {
    this.writer = writer;
  }

  private async synthetic(): Promise<Json> {
    if (this.syntheticFile === null) {
      const text = `${JSON.stringify(toJson(syntheticSteps()), null, JSON_INDENT)}\n`;
      this.syntheticFile = await this.writer.file(INPUT_DIRECTORY, SYNTHETIC_FILE_NAME, new TextEncoder().encode(text), JSON_EXTENSION);
    }
    return this.syntheticFile;
  }

  async add(name: string, fn: string, scenario: Scenario): Promise<void> {
    const setup = scenario.setup ?? [];
    const args: { [key: string]: Json } = { homeCollectionId: HOME };
    if (scenario.synthetic === true) args['setupFile'] = await this.synthetic();
    args['setup'] = toJson(setup);
    args['calls'] = toJson(scenario.calls);
    this.cases.push(
      functionCase(`${fn}: ${name}`, fn, args, () => {
        const index = createGalleryIndex({ homeCollectionId: HOME });
        if (scenario.synthetic === true) for (const value of syntheticSteps()) run(index, value);
        for (const value of setup) run(index, value);
        return scenario.calls.map((value) => run(index, value));
      }),
    );
  }
}

function tokenizeCases(): GoldenCase[] {
  const rows: readonly (readonly [string, string])[] = [
    ['"Beach Sunset"', 'Beach Sunset'],
    ['"  beach,sunset!! beach "', '  beach,sunset!! beach '],
    ['"a b cd"', 'a b cd'],
    ['"ＢＥＡＣＨ" (fullwidth)', 'ＢＥＡＣＨ'],
    ['"Cafe\\u0301 2026" (decomposed accent)', 'Caf\u0065\u0301 2026'],
    ['"**bold** _it_ #tag"', '**bold** _it_ #tag'],
    ['"" (empty)', ''],
    ['"日本 東京"', '日本 東京'],
    ['drops tokens shorter than TOKEN_MIN_CHARS', 'x yy z'],
    [
      'keeps at most MAX_INDEX_TOKENS_PER_ENTRY tokens, the first ones in note order',
      Array.from({ length: MAX_INDEX_TOKENS_PER_ENTRY + TOKEN_OVERFLOW }, (_, index) => `w${index}`).join(' '),
    ],
    ['deduplicates before applying the cap', `${'same '.repeat(MAX_INDEX_TOKENS_PER_ENTRY)} other`],
    [
      'edge: exactly the cap of distinct tokens, then a repeat and a new one',
      [...Array.from({ length: MAX_INDEX_TOKENS_PER_ENTRY }, (_, index) => `t${index}`), 't0', 'late'].join(' '),
    ],
    ['edge: NFKC expands the fi ligature, superscripts and roman numerals', 'ﬁsh x² Ⅻ ①②'],
    ['edge: toLowerCase of Greek final sigma', 'ΟΔΟΣ ΣΑΣ'],
    ['edge: toLowerCase of dotted capital I and capital sharp s', 'İSTANBUL STRAẞE Straße'],
    ['edge: a combining mark that does not compose splits the word', 'x\u0301yz q\u0308rs'],
    ['edge: Devanagari vowel signs are marks and split words', 'नमस्ते दुनिया'],
    ['edge: a supplementary-plane letter counts as 2 UTF-16 units', '𠀀 𝐀𝐁 a'],
    ['edge: emoji, punctuation, apostrophes and underscores separate', "don't🌸stop snake_case 1.5 v2.0 e-mail"],
    ['edge: Unicode whitespace and control characters separate', 'one\u00a0two\u2003three\tfour\nfive\u200bsix'],
    ['edge: digits of other scripts are kept as numbers', '٢٠٢٦ ２０２６ 2026'],
    ['edge: an Arabic word with diacritics', 'مَرْحَبًا مرحبا'],
    ['edge: only separators', ' ,.;!? '],
  ];
  return rows.map(([name, text]) => functionCase(`${FN.tokenize}: ${name}`, FN.tokenize, { text }, () => tokenize(text)));
}

async function orderingCases(gallery: GalleryCases): Promise<void> {
  const syntheticIds = syntheticSteps().flatMap((value) => (value.method === 'upsert' ? [value.args[0].id] : []));
  await gallery.add('orders by takenAtUtc descending over 50 entries and groups by local date', FN.query, {
    synthetic: true,
    calls: [step.size(), step.query(), ...syntheticIds.map((id) => step.get(id))],
  });
  await gallery.add('breaks ties on equal times by id ascending', FN.query, {
    setup: ['c', 'a', 'b'].map((id) => step.upsert(entry(id))),
    calls: [step.query()],
  });
  await gallery.add('orders mixed ISO precisions by instant, not by string', FN.query, {
    setup: [
      step.upsert(entry('whole', { takenAtUtc: '2026-11-10T10:00:00Z' })),
      step.upsert(entry('fraction', { takenAtUtc: '2026-11-10T10:00:00.500Z' })),
    ],
    calls: [step.query()],
  });
  await gallery.add('puts an entry at 2026-11-10T23:30Z with offset +60 on 2026-11-11', FN.query, {
    setup: [
      step.upsert(entry('late', { takenAtUtc: '2026-11-10T23:30:00.000Z', utcOffsetMinutes: OFFSET_PLUS_ONE_HOUR })),
      step.upsert(entry('earlier', { takenAtUtc: '2026-11-10T22:30:00.000Z', utcOffsetMinutes: 0 })),
      step.upsert(entry('west', { takenAtUtc: '2026-11-11T02:00:00.000Z', utcOffsetMinutes: OFFSET_MINUS_FIVE_HOURS })),
    ],
    calls: [step.query(), step.get('late')],
  });
  await gallery.add('edge: ids tie-break in UTF-16 code-unit order, not code-point or locale order', FN.query, {
    setup: ['b', 'B', 'ä', 'z', '\uff41', '🌊', 'a10', 'a9'].map((id) => step.upsert(entry(id))),
    calls: [step.query()],
  });
  await gallery.add('edge: an explicit +02:00 offset in takenAtUtc is ordered by instant', FN.query, {
    setup: [
      step.upsert({ ...entry('offset'), takenAtUtc: '2026-11-11T13:30:00+02:00' }),
      step.upsert(entry('utc', { takenAtUtc: '2026-11-11T12:00:00.000Z' })),
    ],
    calls: [step.query()],
  });
  await gallery.add('edge: sections follow time order, so one local date can appear twice', FN.query, {
    setup: [
      step.upsert(entry('tokyo', { takenAtUtc: '2026-11-11T16:00:00.000Z', utcOffsetMinutes: 540 })),
      step.upsert(entry('london', { takenAtUtc: '2026-11-11T15:00:00.000Z', utcOffsetMinutes: 0 })),
      step.upsert(entry('auckland', { takenAtUtc: '2026-11-11T14:00:00.000Z', utcOffsetMinutes: 780 })),
    ],
    calls: [step.query()],
  });
  await gallery.add('edge: an empty index', FN.query, {
    calls: [step.size(), step.query(), step.get('missing'), step.allTags(), step.tagSuggestions('', SUGGESTION_LIMIT)],
  });
}

async function filterCases(gallery: GalleryCases): Promise<void> {
  for (const tags of [['a'], ['a', 'b'], ['b', 'c'], ['missing']]) {
    await gallery.add(`tags ${JSON.stringify(tags)} are AND-combined`, FN.query, {
      synthetic: true,
      calls: [step.query(filter({ tags }))],
    });
  }
  const ranges: readonly (readonly [string | null, string | null])[] = [
    ['2026-11-05', '2026-11-08'],
    ['2026-11-08', null],
    [null, '2026-11-01'],
    ['2026-11-11', '2026-11-11'],
  ];
  for (const [fromDate, toDate] of ranges) {
    await gallery.add(`date range ${String(fromDate)}..${String(toDate)} is inclusive`, FN.query, {
      synthetic: true,
      calls: [step.query(filter({ fromDate, toDate }))],
    });
  }
  await gallery.add('includes both range ends', FN.query, {
    synthetic: true,
    calls: [step.query(filter({ fromDate: '2026-11-10', toDate: '2026-11-10' }))],
  });
  const textSetup = [
    step.upsert(entry('one', { takenAtUtc: '2026-11-11T10:00:00.000Z' }), 'beach sunset'),
    step.upsert(entry('two', { takenAtUtc: '2026-11-11T09:00:00.000Z' }), 'beach day'),
    step.upsert(entry('three', { takenAtUtc: '2026-11-11T08:00:00.000Z' }), 'lake walk'),
  ];
  const texts = [
    'bea sun',
    'sun bea',
    'BEACH',
    'sunset',
    'bea lake',
    'zzz',
    'edge: x',
    'edge:   ',
    'edge: ＢＥＡ',
    'edge: walks',
    'edge: beach beach',
  ];
  for (const label of texts) {
    const text = label.replace(/^edge: /u, '');
    const name = label.startsWith('edge: ') ? `edge: text ${JSON.stringify(text)}` : `text ${JSON.stringify(text)} prefix-matches every query token`;
    await gallery.add(name, FN.query, { setup: textSetup, calls: [step.query(filter({ text }))] });
  }
  await gallery.add('combines tags, dates and text', FN.query, {
    synthetic: true,
    calls: [step.query(filter({ tags: ['a'], fromDate: '2026-11-01', toDate: '2026-11-10', text: 'lak' }))],
  });
  await gallery.add('trash view shows only trashed entries', FN.query, {
    synthetic: true,
    calls: [step.query(filter({ view: 'trash' }))],
  });
  await gallery.add('scope home vs collection', FN.query, {
    synthetic: true,
    setup: [step.upsert(entry('shared-only', { collectionIds: [SHARED], takenAtUtc: '2026-11-12T00:00:00.000Z' }), 'shared')],
    calls: [step.query(), step.query(filter({ scope: SHARED_SCOPE }))],
  });
  await gallery.add('edge: tags are matched exactly, without normalization', FN.query, {
    setup: [step.upsert(entry('x', { tags: ['beach'] }))],
    calls: [step.query(filter({ tags: ['Beach'] })), step.query(filter({ tags: ['beach', 'beach'] })), step.query(filter({ tags: ['bea'] }))],
  });
  await gallery.add('edge: dates compare as strings; a malformed bound still compares by code unit', FN.query, {
    setup: [step.upsert(entry('x', { takenAtUtc: '2026-11-05T12:00:00.000Z' }))],
    calls: [step.query(filter({ fromDate: '2026-11-5' })), step.query(filter({ toDate: '2026-11-05T' })), step.query(filter({ fromDate: '2026-11-06', toDate: '2026-11-04' }))],
  });
}

const REFINE_ENTRIES: readonly IndexedEntry[] = [
  entry('plain'),
  entry('video', { hasVideo: true }),
  entry('noted', { notePreview: 'a day at the lake' }),
  entry('many', { mediaCount: 3 }),
  entry('shared', { collectionIds: [HOME, SHARED] }),
  entry('pending', { pending: true }),
];

function refineSetup(): Step[] {
  return REFINE_ENTRIES.map((value) => step.upsert(value, value.notePreview));
}

function attributeFilter(attributes: AttributeFilters | undefined, scope: Scope = HOME_SCOPE): GalleryFilter {
  return { ...filter({ scope }), attributes };
}

async function refineCases(gallery: GalleryCases): Promise<void> {
  await gallery.add('includes or excludes each attribute', FN.query, {
    setup: refineSetup(),
    calls: [
      step.query(attributeFilter({ video: 'include' })),
      step.query(attributeFilter({ note: 'include' })),
      step.query(attributeFilter({ multiple: 'include' })),
      step.query(attributeFilter({ shared: 'include' })),
      step.query(attributeFilter({ pending: 'include' })),
      step.query(attributeFilter({ video: 'exclude' })),
    ],
  });
  await gallery.add('combines filters with AND and treats off and missing as no filter', FN.query, {
    setup: [...refineSetup(), step.upsert(entry('video-note', { hasVideo: true, notePreview: 'clip' }), 'clip')],
    calls: [
      step.query(attributeFilter({ video: 'include', note: 'include' })),
      step.query(attributeFilter({ video: 'include', note: 'exclude' })),
      step.query(attributeFilter({ video: 'off' })),
      step.query(attributeFilter(undefined)),
    ],
  });
  await gallery.add('counts an entry from another account as shared', FN.query, {
    setup: [...refineSetup(), step.upsert(entry('theirs', { isOwn: false, collectionIds: [SHARED] }))],
    calls: [step.query(attributeFilter({ shared: 'include' }, SHARED_SCOPE))],
  });
  await gallery.add('edge: favorite include and exclude (absent favorite counts as false)', FN.query, {
    setup: [
      step.upsert(entry('fav', { favorite: true })),
      step.upsert(entry('not-fav', { favorite: false })),
      step.upsert(entry('unset')),
    ],
    calls: [step.query(attributeFilter({ favorite: 'include' })), step.query(attributeFilter({ favorite: 'exclude' })), step.query(attributeFilter({}))],
  });
}

async function suggestionCases(gallery: GalleryCases): Promise<void> {
  await gallery.add('ranks tag suggestions by count, then alphabetically, from own non-trashed entries', FN.tagSuggestions, {
    setup: [
      step.upsert(entry('1', { tags: ['beach', 'bike'] })),
      step.upsert(entry('2', { tags: ['beach', 'books'] })),
      step.upsert(entry('3', { tags: ['bike', 'beach'] })),
      step.upsert(entry('4', { tags: ['books'], trashed: true })),
      step.upsert(entry('5', { tags: ['books', 'boat'], isOwn: false })),
      step.upsert(entry('6', { tags: ['city'] })),
    ],
    calls: [
      step.tagSuggestions('b', SUGGESTION_LIMIT),
      step.tagSuggestions('', SUGGESTION_SMALL_LIMIT),
      step.tagSuggestions('  BI ', SUGGESTION_LIMIT),
      step.tagSuggestions('boat', SUGGESTION_LIMIT),
      step.tagSuggestions('', SUGGESTION_LIMIT),
    ],
  });
  await gallery.add('edge: prefixes are NFKC-normalized, trimmed, whitespace-collapsed and lowercased; limit 0', FN.tagSuggestions, {
    setup: [
      step.upsert(entry('1', { tags: ['new york', 'new year', 'fish'] })),
      step.upsert(entry('2', { tags: ['new york', 'été'] })),
      step.upsert(entry('3', { tags: ['ﬁ', 'σας'] })),
    ],
    calls: [
      step.tagSuggestions('NEW   Y', SUGGESTION_LIMIT),
      step.tagSuggestions('ＦＩ', SUGGESTION_LIMIT),
      step.tagSuggestions('ﬁ', SUGGESTION_LIMIT),
      step.tagSuggestions('E\u0301', SUGGESTION_LIMIT),
      step.tagSuggestions('ΣΑΣ', SUGGESTION_LIMIT),
      step.tagSuggestions(' \t ', SUGGESTION_LIMIT),
      step.tagSuggestions('x'.repeat(TAG_MAX_CHARS + 1), SUGGESTION_LIMIT),
      step.tagSuggestions('', 0),
    ],
  });
  await gallery.add('lists every tag of journal entries alphabetically with its count', FN.allTags, {
    setup: [
      step.upsert(entry('x', { tags: ['beach', 'family'] })),
      step.upsert(entry('y', { tags: ['beach'] })),
      step.upsert(entry('z', { tags: ['zoo'], trashed: true })),
    ],
    calls: [step.allTags()],
  });
  await gallery.add('edge: allTags uses UTF-16 order, counts duplicate tags once, skips unreadable and foreign entries', FN.allTags, {
    setup: [
      step.upsert(entry('1', { tags: ['zoo', 'Zoo', 'été', 'ａ', '🌊', '日本', 'a', 'a'] })),
      step.upsert(entry('2', { tags: ['a', '🌊'] })),
      step.upsert(entry('3', { tags: ['locked'], unreadable: true })),
      step.upsert(entry('4', { tags: ['theirs'], isOwn: false })),
    ],
    calls: [step.allTags(), step.tagSuggestions('', SUGGESTION_LIMIT), step.query(filter({ tags: ['theirs'] })), step.query(filter({ tags: ['locked'] }))],
  });
  await gallery.add('edge: equal counts tie-break in UTF-16 order', FN.tagSuggestions, {
    setup: [step.upsert(entry('1', { tags: ['ｂ', '🌊', 'b', 'B'] }))],
    calls: [step.tagSuggestions('', SUGGESTION_LIMIT)],
  });
}

async function calendarCases(gallery: GalleryCases): Promise<void> {
  await gallery.add('daysWithEntries maps each day of the month to its newest non-trashed entry in scope', FN.daysWithEntries, {
    setup: [
      step.upsert(entry('early', { takenAtUtc: '2026-11-03T08:00:00.000Z' })),
      step.upsert(entry('late', { takenAtUtc: '2026-11-03T20:00:00.000Z' })),
      step.upsert(entry('trashed', { takenAtUtc: '2026-11-03T22:00:00.000Z', trashed: true })),
      step.upsert(entry('seventeen', { takenAtUtc: '2026-11-17T08:00:00.000Z' })),
      step.upsert(entry('october', { takenAtUtc: '2026-10-31T08:00:00.000Z' })),
      step.upsert(entry('shared', { takenAtUtc: '2026-11-05T08:00:00.000Z', collectionIds: [SHARED] })),
    ],
    calls: [step.daysWithEntries('2026-11'), step.daysWithEntries('2026-11', SHARED_SCOPE), step.daysWithEntries('2026-10'), step.daysWithEntries('2026-1')],
  });
  await gallery.add('edge: daysWithEntries uses local dates and keeps insertion order of the time-ordered scan', FN.daysWithEntries, {
    setup: [
      step.upsert(entry('tokyo', { takenAtUtc: '2026-11-30T16:00:00.000Z', utcOffsetMinutes: 540 })),
      step.upsert(entry('london', { takenAtUtc: '2026-11-30T15:00:00.000Z' })),
      step.upsert(entry('tie-b', { takenAtUtc: '2026-11-02T10:00:00.000Z' })),
      step.upsert(entry('tie-a', { takenAtUtc: '2026-11-02T10:00:00.000Z' })),
      step.upsert(entry('unreadable', { takenAtUtc: '2026-11-04T10:00:00.000Z', unreadable: true })),
    ],
    calls: [step.daysWithEntries('2026-11'), step.daysWithEntries('2026-12')],
  });
}

async function updateCases(gallery: GalleryCases): Promise<void> {
  await gallery.add('upsert of a changed entry removes its old tags and words', FN.query, {
    setup: [step.upsert(entry('x', { tags: ['old'] }), 'beach'), step.upsert(entry('x', { tags: ['new'] }), 'forest')],
    calls: [
      step.query(filter({ tags: ['old'] })),
      step.query(filter({ tags: ['new'] })),
      step.query(filter({ text: 'bea' })),
      step.query(filter({ text: 'for' })),
      step.tagSuggestions('', SUGGESTION_LIMIT),
      step.size(),
    ],
  });
  await gallery.add('remove drops the entry from every structure', FN.query, {
    synthetic: true,
    setup: [step.remove('e00')],
    calls: [step.get('e00'), step.size(), step.query(), step.query(filter({ tags: ['a'] }))],
  });
  await gallery.add('unreadable entries are excluded from tag and text queries but present unfiltered', FN.query, {
    synthetic: true,
    setup: [step.upsert(entry('locked', { unreadable: true, tags: ['a'], coverMediaId: null, mediaCount: 0 }), 'beach')],
    calls: [step.query(), step.query(filter({ tags: ['a'] })), step.query(filter({ text: 'beach' })), step.tagSuggestions('a', SUGGESTION_LIMIT)],
  });
  await gallery.add('version increments on every change and clear empties the index', FN.version, {
    calls: [
      step.version(),
      step.upsert(entry('x')),
      step.version(),
      step.upsert(entry('x', { tags: ['t'] })),
      step.version(),
      step.remove('x'),
      step.version(),
      step.upsert(entry('y')),
      step.clear(),
      step.version(),
      step.size(),
      step.query(),
      step.tagSuggestions('', SUGGESTION_LIMIT),
    ],
  });
  await gallery.add('edge: removing an unknown id does not change the version; clear always does', FN.version, {
    calls: [step.version(), step.remove('missing'), step.version(), step.clear(), step.version(), step.upsert(entry('x')), step.remove('x'), step.remove('x'), step.version()],
  });
  await gallery.add('edge: get returns the entry exactly as upserted, the last upsert wins', FN.get, {
    setup: [
      step.upsert(entry('x', { tags: ['one'], favorite: true, videoCount: 2, videoMs: 1500, originalBytes: 1024 }), 'first'),
      step.upsert(entry('x', { tags: ['two'], notePreview: 'second' }), 'second'),
    ],
    calls: [step.get('x'), step.size(), step.allTags(), step.query(filter({ text: 'first' })), step.query(filter({ text: 'sec' }))],
  });
}

async function onThisDayCases(gallery: GalleryCases): Promise<void> {
  const mainSetup = [
    step.upsert(entry('this-year', { takenAtUtc: '2026-11-11T08:00:00.000Z' })),
    step.upsert(entry('one-a', { takenAtUtc: '2025-11-11T08:00:00.000Z' })),
    step.upsert(entry('one-b', { takenAtUtc: '2025-11-11T18:00:00.000Z' })),
    step.upsert(entry('three', { takenAtUtc: '2023-11-11T08:00:00.000Z' })),
    step.upsert(entry('other-day', { takenAtUtc: '2025-11-12T08:00:00.000Z' })),
    step.upsert(entry('trashed', { takenAtUtc: '2024-11-11T08:00:00.000Z', trashed: true })),
    step.upsert(entry('shared', { takenAtUtc: '2024-11-11T08:00:00.000Z', collectionIds: [SHARED] })),
  ];
  const title = 'on this day: same MM-DD in earlier years, grouped by years ago, newest first';
  await gallery.add(`${title} (home scope)`, FN.onThisDay, { setup: mainSetup, calls: [step.onThisDay('2026-11-11')] });
  await gallery.add(`${title} (collection scope)`, FN.onThisDay, { setup: mainSetup, calls: [step.onThisDay('2026-11-11', SHARED_SCOPE)] });
  await gallery.add(`${title} (a day without entries)`, FN.onThisDay, { setup: mainSetup, calls: [step.onThisDay('2026-06-01')] });
  await gallery.add('upsert moves an entry to its new day and month-day', FN.onThisDay, {
    setup: [step.upsert(entry('x', { takenAtUtc: '2025-11-11T08:00:00.000Z' })), step.upsert(entry('x', { takenAtUtc: '2025-11-12T08:00:00.000Z' }))],
    calls: [step.onThisDay('2026-11-11'), step.onThisDay('2026-11-12')],
  });
  await gallery.add('edge: leap day matches only 02-29', FN.onThisDay, {
    setup: [
      step.upsert(entry('2024', { takenAtUtc: '2024-02-29T12:00:00.000Z' })),
      step.upsert(entry('2020', { takenAtUtc: '2020-02-29T12:00:00.000Z' })),
      step.upsert(entry('feb-28', { takenAtUtc: '2025-02-28T12:00:00.000Z' })),
      step.upsert(entry('mar-01', { takenAtUtc: '2025-03-01T12:00:00.000Z' })),
    ],
    calls: [step.onThisDay('2028-02-29'), step.onThisDay('2027-02-28'), step.onThisDay('2027-03-01')],
  });
  await gallery.add('edge: groups sort numerically and future or same-year entries are skipped', FN.onThisDay, {
    setup: [
      step.upsert(entry('ten', { takenAtUtc: '2016-11-11T08:00:00.000Z' })),
      step.upsert(entry('two', { takenAtUtc: '2024-11-11T08:00:00.000Z' })),
      step.upsert(entry('thirty-six', { takenAtUtc: '1990-11-11T08:00:00.000Z' })),
      step.upsert(entry('future', { takenAtUtc: '2027-11-11T08:00:00.000Z' })),
      step.upsert(entry('today', { takenAtUtc: '2026-11-11T23:00:00.000Z' })),
    ],
    calls: [step.onThisDay('2026-11-11')],
  });
  await gallery.add('edge: month-day and year come from the local date, not UTC', FN.onThisDay, {
    setup: [
      step.upsert(entry('east', { takenAtUtc: '2025-11-10T23:30:00.000Z', utcOffsetMinutes: OFFSET_PLUS_ONE_HOUR })),
      step.upsert(entry('west', { takenAtUtc: '2025-11-12T02:00:00.000Z', utcOffsetMinutes: OFFSET_MINUS_FIVE_HOURS })),
      step.upsert(entry('new-year', { takenAtUtc: '2025-12-31T23:30:00.000Z', utcOffsetMinutes: OFFSET_PLUS_ONE_HOUR })),
      step.upsert(entry('utc-11-11', { takenAtUtc: '2025-11-11T23:30:00.000Z', utcOffsetMinutes: OFFSET_PLUS_ONE_HOUR })),
    ],
    calls: [step.onThisDay('2026-11-11'), step.onThisDay('2027-01-01')],
  });
  await gallery.add('edge: ties within a group break by id; unreadable, pending and foreign entries count', FN.onThisDay, {
    setup: [
      step.upsert(entry('b', { takenAtUtc: '2025-05-05T08:00:00.000Z' })),
      step.upsert(entry('a', { takenAtUtc: '2025-05-05T08:00:00.000Z', unreadable: true })),
      step.upsert(entry('c', { takenAtUtc: '2025-05-05T09:00:00.000Z', pending: true })),
      step.upsert(entry('d', { takenAtUtc: '2025-05-05T07:00:00.000Z', isOwn: false })),
      step.upsert(entry('elsewhere', { takenAtUtc: '2025-05-05T10:00:00.000Z', collectionIds: [OTHER] })),
    ],
    calls: [step.onThisDay('2026-05-05'), step.onThisDay('2026-05-05', { kind: 'collection', collectionId: OTHER })],
  });
  await gallery.add('edge: removed and cleared entries disappear', FN.onThisDay, {
    setup: [step.upsert(entry('kept', { takenAtUtc: '2025-11-11T08:00:00.000Z' })), step.upsert(entry('gone', { takenAtUtc: '2024-11-11T08:00:00.000Z' }))],
    calls: [step.onThisDay('2026-11-11'), step.remove('gone'), step.onThisDay('2026-11-11'), step.clear(), step.onThisDay('2026-11-11')],
  });
}

type SortSpec = { readonly name: string; readonly ids: readonly string[]; readonly sort: GallerySort; readonly seed: number };

const SORT_LOOKUP: readonly IndexedEntry[] = [
  entry('a', { takenAtUtc: '2026-01-01T00:00:00.000Z', notePreview: 'xx', mediaCount: 2 }),
  entry('b', { takenAtUtc: '2026-03-01T00:00:00.000Z', notePreview: '', mediaCount: 5 }),
  entry('c', { takenAtUtc: '2026-02-01T00:00:00.000Z', notePreview: 'xxxxx', mediaCount: 1 }),
];

const SHUFFLE_IDS = Array.from({ length: SHUFFLE_COUNT }, (_, position) => `id${position}`);

const TIE_LOOKUP: readonly IndexedEntry[] = [
  entry('p', { takenAtUtc: '2026-01-01T00:00:00.000Z', notePreview: '🌸', mediaCount: 2 }),
  entry('q', { takenAtUtc: '2026-02-01T00:00:00.000Z', notePreview: 'ab', mediaCount: 2 }),
  entry('r', { takenAtUtc: '2026-02-01T00:00:00.000Z', notePreview: 'é', mediaCount: 2 }),
  entry('s', { takenAtUtc: '2026-02-01T00:00:00Z', notePreview: 'e\u0301', mediaCount: 2 }),
];

function sortCase(name: string, lookup: readonly IndexedEntry[], spec: Omit<SortSpec, 'name'>): GoldenCase {
  const byId = new Map(lookup.map((value) => [value.id, value]));
  return functionCase(
    `${FN.sortEntryIds}: ${name}`,
    FN.sortEntryIds,
    { ids: [...spec.ids], entries: toJson(lookup), sort: spec.sort, seed: spec.seed },
    () => sortEntryIds(spec.ids, (id) => byId.get(id) ?? null, spec.sort, spec.seed),
  );
}

function sortCases(): GoldenCase[] {
  const abc = ['a', 'b', 'c'];
  const title = 'sorts by date taken, note length and number of photos in both directions';
  const shuffleTitle = 'shuffles the same way for the same seed and differently for another seed';
  const shuffleLookup: readonly IndexedEntry[] = [];
  const random = (seed: number) => ({ ids: SHUFFLE_IDS, sort: { key: 'random', descending: false } as const, seed });
  const tieIds = ['s', 'r', 'q', 'p'];
  return [
    sortCase(`${title} (takenAt descending)`, SORT_LOOKUP, { ids: abc, sort: { key: 'takenAt', descending: true }, seed: 0 }),
    sortCase(`${title} (takenAt ascending)`, SORT_LOOKUP, { ids: abc, sort: { key: 'takenAt', descending: false }, seed: 0 }),
    sortCase(`${title} (noteLength descending)`, SORT_LOOKUP, { ids: abc, sort: { key: 'noteLength', descending: true }, seed: 0 }),
    sortCase(`${title} (mediaCount ascending)`, SORT_LOOKUP, { ids: abc, sort: { key: 'mediaCount', descending: false }, seed: 0 }),
    sortCase(`${shuffleTitle} (seed 7)`, shuffleLookup, random(SEED.first)),
    sortCase(`${shuffleTitle} (seed 8)`, shuffleLookup, random(SEED.second)),
    sortCase('edge: random with seed 0', shuffleLookup, random(0)),
    sortCase('edge: random with a negative fractional seed behaves like its truncated absolute value', shuffleLookup, random(SEED.negativeFraction)),
    sortCase('edge: random with a seed above 2^31 is reduced modulo 2^31', shuffleLookup, random(SEED.aboveModulus)),
    sortCase('edge: random with a large seed (double precision in the generator)', shuffleLookup, random(SEED.maxBelowModulus)),
    sortCase('edge: random ignores descending and missing entries', [], { ids: ['x', 'y', 'z'], sort: { key: 'random', descending: true }, seed: SEED.small }),
    sortCase('edge: random with one id', [], { ids: ['only'], sort: { key: 'random', descending: false }, seed: SEED.single }),
    sortCase('edge: random with no ids', [], { ids: [], sort: { key: 'random', descending: false }, seed: SEED.single }),
    sortCase('edge: mediaCount ties fall back to takenAt descending then id, in both directions (ascending)', TIE_LOOKUP, { ids: tieIds, sort: { key: 'mediaCount', descending: false }, seed: 0 }),
    sortCase('edge: mediaCount ties fall back to takenAt descending then id, in both directions (descending)', TIE_LOOKUP, { ids: tieIds, sort: { key: 'mediaCount', descending: true }, seed: 0 }),
    sortCase('edge: noteLength counts UTF-16 units', TIE_LOOKUP, { ids: tieIds, sort: { key: 'noteLength', descending: true }, seed: 0 }),
    sortCase('edge: takenAt compares the ISO strings, not instants', TIE_LOOKUP, { ids: tieIds, sort: { key: 'takenAt', descending: false }, seed: 0 }),
    sortCase('edge: an id without an entry sorts last in both directions', SORT_LOOKUP, { ids: ['a', 'missing', 'b', 'c'], sort: { key: 'takenAt', descending: true }, seed: 0 }),
    sortCase('edge: an id without an entry sorts last (ascending)', SORT_LOOKUP, { ids: ['missing', 'a', 'b', 'c'], sort: { key: 'takenAt', descending: false }, seed: 0 }),
  ];
}

function attributeCases(): GoldenCase[] {
  const rows: readonly { readonly name: string; readonly value: IndexedEntry; readonly filters: AttributeFilters }[] = [
    { name: 'no filters match everything', value: entry('x'), filters: {} },
    { name: 'off is no filter', value: entry('x'), filters: { video: 'off', note: 'off' } },
    { name: 'an own entry only in another collection is shared', value: entry('x', { collectionIds: [OTHER] }), filters: { shared: 'include' } },
    { name: 'an own entry only in the home collection is not shared', value: entry('x'), filters: { shared: 'include' } },
    { name: 'an entry in no collection that is not own is shared', value: entry('x', { collectionIds: [], isOwn: false }), filters: { shared: 'include' } },
    { name: 'mediaCount 1 is not multiple', value: entry('x', { mediaCount: 1 }), filters: { multiple: 'exclude' } },
    { name: 'mediaCount 2 is multiple', value: entry('x', { mediaCount: 2 }), filters: { multiple: 'include' } },
    { name: 'a whitespace note preview counts as a note', value: entry('x', { notePreview: ' ' }), filters: { note: 'include' } },
    { name: 'favorite absent fails include', value: entry('x'), filters: { favorite: 'include' } },
    { name: 'favorite true fails exclude', value: entry('x', { favorite: true }), filters: { favorite: 'exclude' } },
    { name: 'every filter must hold', value: entry('x', { hasVideo: true, pending: true }), filters: { video: 'include', pending: 'exclude' } },
  ];
  return rows.map((row) =>
    functionCase(
      `${FN.attributesMatch}: edge: ${row.name}`,
      FN.attributesMatch,
      { entry: toJson(row.value), filters: row.filters, homeCollectionId: HOME },
      () => attributesMatch(row.value, row.filters, HOME),
    ),
  );
}

export async function writeIndexGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATHS.tokenize, tokenizeCases());

  const query = new GalleryCases(writer);
  await orderingCases(query);
  await filterCases(query);
  await refineCases(query);
  await suggestionCases(query);
  await calendarCases(query);
  await updateCases(query);
  await writer.functionCases(PATHS.galleryQuery, [...query.cases, ...sortCases(), ...attributeCases()]);

  const onThisDay = new GalleryCases(writer);
  await onThisDayCases(onThisDay);
  await writer.functionCases(PATHS.onThisDay, onThisDay.cases);
}
