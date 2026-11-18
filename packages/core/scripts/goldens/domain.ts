import { MIN_OBJECT_BYTES, TAGS_PER_ENTRY_MAX, TAG_MAX_CHARS, type EntryMetadataV1, type MediaDescriptorV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import {
  addMonths,
  addTag,
  applyEntryEdit,
  buildNewEntryMetadata,
  isValidYearMonth,
  localDateOf,
  localPartsToUtc,
  localTimeOf,
  monthGrid,
  normalizeTag,
  removeTag,
  sortTags,
  yearMonthOf,
  type EntryDraft,
  type EntryEdit,
  type WeekStart,
} from '../../src/domain/index.ts';
import { STREAM_MIN_OBJECT_BYTES } from '../../src/crypto/index.ts';
import type { ResolvedTime } from '../../src/media/index.ts';
import { attributesMatch, type AttributeFilters, type IndexedEntry } from '../../src/index/index.ts';
import { mergeMetadata, metadataEquivalent } from '../../src/sync/index.ts';
import { functionCase, toJson, type GoldenCase, type GoldenWriter } from './shared.ts';

type Metadata = z.infer<typeof EntryMetadataV1>;
type Descriptor = z.infer<typeof MediaDescriptorV1>;

const PATHS = Object.freeze({
  entryTime: 'domain/entry-time.json',
  calendar: 'domain/calendar.json',
  tags: 'domain/tags.json',
  entryEdit: 'domain/entry-edit.json',
});

const MINUTES_PER_HOUR = 60;
const UTC_PLUS_14 = 14 * MINUTES_PER_HOUR;
const UTC_MINUS_12 = -12 * MINUTES_PER_HOUR;
const MONDAY: WeekStart = 1;
const SUNDAY: WeekStart = 0;
const TODAY = '2026-11-12';

function entryTimeCases(): GoldenCase[] {
  const cases: GoldenCase[] = [];
  const local = [
    { name: 'utc', utc: '2026-11-11T14:05:00.000Z', offset: 0 },
    { name: 'a positive offset crossing midnight', utc: '2026-11-11T23:30:00.000Z', offset: 60 },
    { name: 'a negative offset crossing midnight', utc: '2026-11-12T00:30:00.000Z', offset: -120 },
    { name: 'the leap day', utc: '2028-02-29T12:00:00.000Z', offset: 0 },
    { name: 'the day after the leap day', utc: '2028-02-29T23:59:00.000Z', offset: 1 },
    { name: '23:59 local', utc: '2026-06-01T23:59:00.000Z', offset: 0 },
    { name: 'offset +14:00 into the next year', utc: '2026-12-31T10:00:00.000Z', offset: UTC_PLUS_14 },
    { name: 'offset -12:00 into the previous day', utc: '2026-01-01T11:59:00.000Z', offset: UTC_MINUS_12 },
    { name: 'an offset with minutes', utc: '2026-03-01T18:30:00.000Z', offset: 345 },
    { name: 'edge: an input without milliseconds', utc: '2026-11-11T14:05Z', offset: 0 },
    { name: 'edge: an input with an explicit offset', utc: '2026-11-11T14:05:00+02:00', offset: 0 },
    { name: 'edge: the year 0000', utc: '0000-01-01T00:30:00.000Z', offset: 0 },
    { name: 'edge: an unparsable input throws', utc: 'not a date', offset: 0 },
  ];
  for (const row of local) {
    const args = { takenAtUtc: row.utc, utcOffsetMinutes: row.offset };
    cases.push(functionCase(`localDateOf: ${row.name}`, 'localDateOf', args, () => localDateOf(row.utc, row.offset)));
    cases.push(functionCase(`localTimeOf: ${row.name}`, 'localTimeOf', args, () => localTimeOf(row.utc, row.offset)));
  }
  const parts = [
    { name: 'converts a plain time', date: '2026-11-11', time: '14:05', offset: 60 },
    { name: 'converts utc', date: '2026-11-11', time: '00:00', offset: 0 },
    { name: 'converts the leap day', date: '2028-02-29', time: '23:59', offset: 0 },
    { name: 'converts offset +14:00', date: '2026-01-01', time: '00:00', offset: UTC_PLUS_14 },
    { name: 'converts offset -12:00', date: '2026-12-31', time: '23:59', offset: UTC_MINUS_12 },
    { name: 'refuses a date that does not exist', date: '2026-02-30', time: '10:00', offset: 0 },
    { name: 'refuses a leap day in a common year', date: '2027-02-29', time: '10:00', offset: 0 },
    { name: 'refuses month 13', date: '2026-13-01', time: '10:00', offset: 0 },
    { name: 'refuses day 00', date: '2026-01-00', time: '10:00', offset: 0 },
    { name: 'refuses a date without padding', date: '2026-1-1', time: '10:00', offset: 0 },
    { name: 'refuses a date with other separators', date: '2026/01/01', time: '10:00', offset: 0 },
    { name: 'refuses hour 24', date: '2026-01-01', time: '24:00', offset: 0 },
    { name: 'refuses minute 60', date: '2026-01-01', time: '12:60', offset: 0 },
    { name: 'refuses a time without padding', date: '2026-01-01', time: '9:05', offset: 0 },
    { name: 'refuses a time with seconds', date: '2026-01-01', time: '09:05:00', offset: 0 },
    { name: 'refuses empty fields', date: '', time: '', offset: 0 },
    { name: 'refuses a date with trailing text', date: '2026-01-01x', time: '10:00', offset: 0 },
    { name: 'refuses a result outside the years 0000 to 9999', date: '9999-12-31', time: '23:59', offset: UTC_MINUS_12 },
    { name: 'edge: refuses a result before the year 0000', date: '0000-01-01', time: '00:00', offset: 60 },
    { name: 'edge: accepts the first minute of the year 0000', date: '0000-01-01', time: '00:00', offset: 0 },
    { name: 'edge: accepts a two-digit year without mapping it to 19xx', date: '0050-06-15', time: '12:00', offset: 0 },
    { name: 'edge: accepts the last minute of the year 9999', date: '9999-12-31', time: '23:59', offset: 0 },
    { name: 'edge: refuses non-ASCII digits', date: '２０２６-01-01', time: '10:00', offset: 0 },
  ];
  for (const row of parts) {
    cases.push(
      functionCase(
        `localPartsToUtc: ${row.name}`,
        'localPartsToUtc',
        { date: row.date, time: row.time, utcOffsetMinutes: row.offset },
        () => localPartsToUtc(row.date, row.time, row.offset),
      ),
    );
  }
  const roundTripUtc = '2026-11-11T14:05:00.000Z';
  for (const offset of [0, 60, -300, UTC_PLUS_14, UTC_MINUS_12]) {
    const date = localDateOf(roundTripUtc, offset);
    const time = localTimeOf(roundTripUtc, offset);
    cases.push(
      functionCase(
        `localPartsToUtc: round-trips localDateOf and localTimeOf at offset ${offset}`,
        'localPartsToUtc',
        { date, time, utcOffsetMinutes: offset },
        () => localPartsToUtc(date, time, offset),
      ),
    );
  }
  return cases;
}

function calendarCases(): GoldenCase[] {
  const cases: GoldenCase[] = [];
  const grids: { name: string; yearMonth: string; today: string; weekStartsOn: WeekStart }[] = [
    { name: 'always returns 6 rows of 7 cells (2026-02)', yearMonth: '2026-02', today: TODAY, weekStartsOn: MONDAY },
    { name: 'always returns 6 rows of 7 cells (2026-11)', yearMonth: '2026-11', today: TODAY, weekStartsOn: MONDAY },
    { name: 'always returns 6 rows of 7 cells (2015-02)', yearMonth: '2015-02', today: TODAY, weekStartsOn: MONDAY },
    { name: 'always returns 6 rows of 7 cells (2024-09)', yearMonth: '2024-09', today: TODAY, weekStartsOn: MONDAY },
    { name: 'covers February in a leap year', yearMonth: '2024-02', today: TODAY, weekStartsOn: MONDAY },
    { name: 'covers February in a non-leap year', yearMonth: '2026-02', today: TODAY, weekStartsOn: MONDAY },
    { name: 'pads a month that begins on Sunday with the previous month (Monday start)', yearMonth: '2026-11', today: TODAY, weekStartsOn: MONDAY },
    { name: 'fills the trailing cells with the next month', yearMonth: '2026-11', today: TODAY, weekStartsOn: MONDAY },
    { name: 'marks exactly today', yearMonth: '2026-11', today: TODAY, weekStartsOn: MONDAY },
    { name: 'starts a month that begins on Monday in the first cell (Monday start)', yearMonth: '2026-06', today: TODAY, weekStartsOn: MONDAY },
    { name: 'starts on Sunday when weekStartsOn is 0', yearMonth: '2026-11', today: TODAY, weekStartsOn: SUNDAY },
    { name: 'marks exactly today, not in another month', yearMonth: '2026-10', today: TODAY, weekStartsOn: MONDAY },
    { name: 'edge: today in the leading days of the next grid', yearMonth: '2026-12', today: '2026-11-30', weekStartsOn: MONDAY },
    { name: 'edge: the first month of the year 0001', yearMonth: '0001-01', today: '0001-01-01', weekStartsOn: SUNDAY },
    { name: 'edge: a month that begins on Sunday with a Sunday start', yearMonth: '2026-03', today: TODAY, weekStartsOn: SUNDAY },
  ];
  for (const grid of grids) {
    cases.push(
      functionCase(
        `monthGrid: ${grid.name}`,
        'monthGrid',
        { yearMonth: grid.yearMonth, todayLocalDate: grid.today, weekStartsOn: grid.weekStartsOn },
        () => monthGrid(grid.yearMonth, grid.today, grid.weekStartsOn),
      ),
    );
  }
  const additions = [
    { name: 'moves forward within a year', yearMonth: '2026-05', delta: 1 },
    { name: 'moves backward within a year', yearMonth: '2026-05', delta: -4 },
    { name: 'wraps December to January', yearMonth: '2026-12', delta: 1 },
    { name: 'wraps January back to December', yearMonth: '2027-01', delta: -1 },
    { name: 'moves back almost two years', yearMonth: '2026-11', delta: -23 },
    { name: 'edge: a zero delta', yearMonth: '2026-11', delta: 0 },
    { name: 'edge: exactly one year forward', yearMonth: '2026-11', delta: 12 },
    { name: 'edge: exactly one year back', yearMonth: '2026-01', delta: -12 },
    { name: 'edge: into the year 0000', yearMonth: '0001-01', delta: -1 },
    { name: 'edge: many years forward', yearMonth: '2026-11', delta: 1201 },
  ];
  for (const row of additions) {
    cases.push(
      functionCase(`addMonths: ${row.name}`, 'addMonths', { yearMonth: row.yearMonth, delta: row.delta }, () =>
        addMonths(row.yearMonth, row.delta),
      ),
    );
  }
  for (const [name, localDate] of [
    ['takes the year and month of a local date', '2026-11-09'],
    ['edge: a value shorter than YYYY-MM is returned as is', '2026'],
  ] as const) {
    cases.push(functionCase(`yearMonthOf: ${name}`, 'yearMonthOf', { localDate }, () => yearMonthOf(localDate)));
  }
  for (const value of ['2026-11', '0001-01', '0000-12', '2026-13', '2026-00', '2026-1', '26-11', '2026/11', '2026-11-01', '', ' 2026-11', '２０２６-11']) {
    cases.push(
      functionCase(`isValidYearMonth: ${JSON.stringify(value)}`, 'isValidYearMonth', { value }, () => isValidYearMonth(value)),
    );
  }
  return cases;
}

const FULL_LIST = Array.from({ length: TAGS_PER_ENTRY_MAX }, (_, index) => `tag-${String(index).padStart(2, '0')}`);
const EMOJI = '\u{1F30A}';

function tagCases(): GoldenCase[] {
  const cases: GoldenCase[] = [];
  const normalize = [
    { name: 'lowercases and trims', input: '  Family ' },
    { name: 'collapses whitespace runs to one space', input: 'road \t  trip\n2026' },
    { name: 'applies NFKC (full-width letters)', input: 'ＦＡＭＩＬＹ' },
    { name: 'applies NFKC (a decomposed accent)', input: 'e\u0301' },
    { name: 'rejects empty input', input: '' },
    { name: 'rejects blank input', input: '   ' },
    { name: 'accepts exactly TAG_MAX_CHARS after normalization', input: 'a'.repeat(TAG_MAX_CHARS) },
    { name: 'rejects input longer than TAG_MAX_CHARS after normalization', input: 'a'.repeat(TAG_MAX_CHARS + 1) },
    { name: 'is idempotent (first pass)', input: '  Mixed   Case ' },
    { name: 'is idempotent (second pass)', input: 'mixed case' },
    { name: 'edge: NFKC expands a ligature', input: 'ﬁsh' },
    { name: 'edge: NFKC can make a short input long enough to be rejected', input: '㍿'.repeat(TAG_MAX_CHARS / 2) },
    { name: 'edge: the length limit counts UTF-16 code units (32 emoji)', input: EMOJI.repeat(TAG_MAX_CHARS / 2) },
    { name: 'edge: the length limit counts UTF-16 code units (33 emoji)', input: EMOJI.repeat(TAG_MAX_CHARS / 2 + 1) },
    { name: 'edge: dotted capital I lowercases to i + U+0307', input: 'İstanbul' },
    { name: 'edge: no-break spaces and the ideographic space become spaces', input: 'a\u00a0\u00a0b\u3000c' },
    { name: 'edge: trim removes a byte order mark', input: '\ufefftag\ufeff' },
    { name: 'edge: the information separator U+001F is not whitespace', input: 'a\u001fb' },
    { name: 'edge: line and paragraph separators are whitespace', input: 'a\u2028\u2029b' },
    { name: 'edge: a zero-width space is not whitespace', input: 'a\u200bb' },
  ];
  for (const row of normalize) {
    cases.push(functionCase(`normalizeTag: ${row.name}`, 'normalizeTag', { input: row.input }, () => normalizeTag(row.input)));
  }
  const sorts = [
    { name: 'orders already sorted by code unit', input: ['a', 'b'] },
    { name: 'orders unsorted by code unit', input: ['b', 'a', 'c'] },
    { name: 'orders code unit order puts digits before letters by code unit', input: ['a', '1', 'B'] },
    { name: 'orders empty by code unit', input: [] },
    { name: 'does not change its input', input: ['b', 'a'] },
    { name: 'edge: an astral character sorts before U+FFFD (UTF-16 order, not code point order)', input: ['\ufffd', EMOJI, 'z'] },
    { name: 'edge: accented letters sort after ASCII', input: ['é', 'f', 'e'] },
  ];
  for (const row of sorts) {
    cases.push(functionCase(`sortTags: ${row.name}`, 'sortTags', { tags: row.input }, () => sortTags(row.input)));
  }
  const adds: { name: string; tags: readonly string[]; raw: string }[] = [
    { name: 'adds a plain tag and returns the sorted list', tags: [], raw: 'family' },
    { name: 'adds a tag that needs normalizing and returns the sorted list', tags: ['b'], raw: '  Road   Trip ' },
    { name: 'adds a tag that sorts first and returns the sorted list', tags: ['b', 'c'], raw: 'a' },
    { name: 'adds a full-width tag and returns the sorted list', tags: [], raw: 'ＦＡＭＩＬＹ' },
    { name: 'rejects empty text and leaves the list unchanged', tags: ['a'], raw: '' },
    { name: 'rejects blank text and leaves the list unchanged', tags: ['a'], raw: '   ' },
    { name: 'rejects text over the limit and leaves the list unchanged', tags: ['a'], raw: 'x'.repeat(TAG_MAX_CHARS + 1) },
    { name: 'rejects a duplicate and leaves the list unchanged', tags: ['a'], raw: 'a' },
    { name: 'rejects a duplicate after normalizing and leaves the list unchanged', tags: ['road trip'], raw: ' ROAD  trip' },
    { name: 'rejects a full list and leaves the list unchanged', tags: FULL_LIST, raw: 'one more' },
    { name: 'accepts a tag of exactly the maximum length', tags: [], raw: 'x'.repeat(TAG_MAX_CHARS) },
    { name: 'accepts the last free slot', tags: FULL_LIST.slice(1), raw: 'zzz' },
    { name: 'reports a duplicate before a full list', tags: FULL_LIST, raw: 'tag-00' },
    { name: 'edge: an empty check comes before a full list', tags: FULL_LIST, raw: ' ' },
    { name: 'edge: an unsorted input list is sorted after an addition', tags: ['c', 'a'], raw: 'b' },
  ];
  for (const row of adds) {
    cases.push(functionCase(`addTag: ${row.name}`, 'addTag', { tags: row.tags, raw: row.raw }, () => addTag(row.tags, row.raw)));
  }
  const removals = [
    { name: 'removes a present tag', tags: ['a', 'b', 'c'], tag: 'b' },
    { name: 'removes a tag typed in another case', tags: ['a', 'b'], tag: ' B ' },
    { name: 'removes an absent tag', tags: ['a'], tag: 'z' },
    { name: 'removes blank text', tags: ['a'], tag: ' ' },
    { name: 'removes the only tag', tags: ['a'], tag: 'a' },
    { name: 'edge: removes every copy of a repeated tag', tags: ['a', 'b', 'a'], tag: 'a' },
  ];
  for (const row of removals) {
    cases.push(functionCase(`removeTag: ${row.name}`, 'removeTag', { tags: row.tags, tag: row.tag }, () => removeTag(row.tags, row.tag)));
  }
  return cases;
}

const NOW = '2026-11-11T12:00:00.000Z';
const LATER = '2026-11-12T08:00:00.000Z';
const PARIS = { latitude: 48.8584, longitude: 2.2945 };
const BERLIN = { latitude: 52.52, longitude: 13.405 };
const DEVICE_POINT = { latitude: 1.5, longitude: 2.5 };
const DEVICE_ACCURACY_METERS = 12;
const OFFSET_MINUTES = 120;
const IDS = ['AAAAAAAAAAAAAAAAAAAAAA', 'BBBBBBBBBBBBBBBBBBBBBB', 'CCCCCCCCCCCCCCCCCCCCCC'] as const;
const UNKNOWN_ID = 'DDDDDDDDDDDDDDDDDDDDDD';
const ADDED_ID = 'EEEEEEEEEEEEEEEEEEEEEE';
const [ID_ONE, ID_TWO, ID_THREE] = IDS;

function descriptor(mediaId: string): Descriptor {
  return {
    mediaId,
    kind: 'photo',
    mimeType: 'image/jpeg',
    width: 100,
    height: 80,
    durationMs: null,
    originalPlaintextBytes: 10,
    thumbnail: { width: 10, height: 8, mimeType: 'image/webp', plaintextBytes: 5 },
    capturedAtUtc: null,
    originalFileName: null,
  } as Descriptor;
}

function time(capturedAtUtc: string, utcOffsetMinutes = 0): ResolvedTime {
  return { capturedAtUtc, utcOffsetMinutes, source: 'exif' };
}

function draft(overrides: Partial<EntryDraft> = {}): EntryDraft {
  return {
    media: [descriptor(ID_ONE), descriptor(ID_TWO)],
    times: [time('2026-05-01T08:00:00.000Z', OFFSET_MINUTES), time('2026-05-01T07:00:00.000Z', OFFSET_MINUTES)],
    note: 'Picnic',
    tags: ['b', 'a'],
    mediaLocations: [null, null],
    deviceLocation: null,
    timeZone: 'Europe/Paris',
    nowUtc: NOW,
    ...overrides,
  };
}

function existing(overrides: Partial<Metadata> = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-05-01T08:00:00.000Z',
    utcOffsetMinutes: OFFSET_MINUTES,
    timeZone: 'Europe/Paris',
    note: 'before',
    tags: ['a'],
    location: null,
    media: IDS.map(descriptor),
    createdAtUtc: NOW,
    editedAtUtc: NOW,
    ...overrides,
  } as Metadata;
}

function favoriteMetadata(patch: Partial<Metadata> = {}): Metadata {
  return {
    schemaVersion: 1,
    takenAtUtc: '2026-11-01T08:00:00.000Z',
    utcOffsetMinutes: 0,
    timeZone: null,
    note: '',
    tags: [],
    location: null,
    media: [
      {
        mediaId: 'mmmmmmmmmmmmmmmmmmmm01',
        kind: 'photo',
        mimeType: 'image/jpeg',
        width: 10,
        height: 10,
        durationMs: null,
        originalPlaintextBytes: 100,
        thumbnail: { width: 10, height: 10, mimeType: 'image/webp', plaintextBytes: 10 },
        capturedAtUtc: null,
        originalFileName: null,
      },
    ],
    createdAtUtc: '2026-11-01T08:00:00.000Z',
    editedAtUtc: '2026-11-01T08:00:00.000Z',
    ...patch,
  } as Metadata;
}

function buildCase(name: string, value: EntryDraft): GoldenCase {
  return functionCase(`buildNewEntryMetadata: ${name}`, 'buildNewEntryMetadata', { draft: toJson(value) }, () => buildNewEntryMetadata(value));
}

function editCase(name: string, current: Metadata, edit: EntryEdit, nowUtc: string): GoldenCase {
  return functionCase(
    `applyEntryEdit: ${name}`,
    'applyEntryEdit',
    { current: toJson(current), edit: toJson(edit), nowUtc },
    () => applyEntryEdit(current, edit, nowUtc),
  );
}

const FAVORITE_HOME = 'home';
const NOW_FAVORITE = '2026-11-14T10:00:00.000Z';
const FAVORITE_ENTRY = {
  isOwn: true,
  collectionIds: [FAVORITE_HOME],
  hasVideo: false,
  notePreview: '',
  mediaCount: 1,
  pending: false,
} as unknown as IndexedEntry;

function mergeCase(name: string, ancestor: Metadata, local: Metadata, server: Metadata): GoldenCase {
  return functionCase(
    `favorites: ${name}`,
    'mergeMetadata',
    { ancestor: toJson(ancestor), local: toJson(local), server: toJson(server), nowUtc: NOW_FAVORITE },
    () => mergeMetadata(ancestor, local, server, NOW_FAVORITE),
  );
}

function equivalentCase(name: string, left: Metadata, right: Metadata): GoldenCase {
  return functionCase(`favorites: ${name}`, 'metadataEquivalent', { left: toJson(left), right: toJson(right) }, () =>
    metadataEquivalent(left, right),
  );
}

function attributeCase(name: string, entry: IndexedEntry, filters: AttributeFilters): GoldenCase {
  return functionCase(
    `favorites: ${name}`,
    'attributesMatch',
    { entry: toJson(entry), filters: toJson(filters), homeCollectionId: FAVORITE_HOME },
    () => attributesMatch(entry, filters, FAVORITE_HOME),
  );
}

function favoriteCases(): GoldenCase[] {
  const plain = favoriteMetadata();
  const marked = favoriteMetadata({ favorite: true });
  return [
    mergeCase('merges the flag three ways: the local side set it', plain, marked, plain),
    mergeCase('merges the flag three ways: the server side cleared it', marked, marked, plain),
    mergeCase('merges the flag three ways: nobody has it', plain, plain, plain),
    mergeCase('edge: merges the flag three ways: the server side set it', plain, plain, marked),
    equivalentCase('treats a favorite change as a real change', plain, marked),
    equivalentCase('treats equal favorite flags as equivalent', marked, marked),
    attributeCase('filters favorites with the tri-state attribute: include a favorite', { ...FAVORITE_ENTRY, favorite: true }, { favorite: 'include' }),
    attributeCase('filters favorites with the tri-state attribute: include a non-favorite', FAVORITE_ENTRY, { favorite: 'include' }),
    attributeCase('filters favorites with the tri-state attribute: exclude a non-favorite', FAVORITE_ENTRY, { favorite: 'exclude' }),
    attributeCase('edge: filters favorites with the tri-state attribute: exclude a favorite', { ...FAVORITE_ENTRY, favorite: true }, { favorite: 'exclude' }),
    attributeCase('edge: filters favorites with the tri-state attribute: off', FAVORITE_ENTRY, { favorite: 'off' }),
  ];
}

function entryEditCases(): GoldenCase[] {
  const place = { ...PARIS, accuracyMeters: null, source: 'exif' as const };
  const withPlace = applyEntryEdit(existing(), { location: place }, LATER);
  const captioned = applyEntryEdit(existing(), { captions: { [ID_ONE]: '  Sunset  ' } }, LATER);
  const marked = applyEntryEdit(favoriteMetadata(), { favorite: true }, NOW);
  const device = { point: DEVICE_POINT, accuracyMeters: DEVICE_ACCURACY_METERS };
  return [
    buildCase('takes the earliest media time with its offset', draft()),
    buildCase('sets the creation and edit times to now, the note and sorted tags', draft({ note: 'Picnic', tags: ['b', 'a'] })),
    buildCase('uses the location of the earliest media that has one, as source exif', draft({ mediaLocations: [PARIS, BERLIN] })),
    buildCase('skips media without a location even when they are the earliest', draft({ mediaLocations: [PARIS, null] })),
    buildCase('prefers a media location over the device location', draft({ mediaLocations: [PARIS, null], deviceLocation: device })),
    buildCase('falls back to the device location with its accuracy, source device', draft({ deviceLocation: device })),
    buildCase('has no location when there is neither', draft({ timeZone: null })),
    buildCase('returns metadata that the contract schema accepts', draft()),
    buildCase('normalizes, deduplicates and sorts the tags', draft({ tags: [' Beach ', 'family', 'BEACH', 'Road   Trip'] })),
    buildCase('refuses an empty tag', draft({ tags: ['ok', '   '] })),
    buildCase('refuses a tag over the length limit', draft({ tags: ['x'.repeat(TAG_MAX_CHARS + 1)] })),
    buildCase('edge: no media times throws', draft({ times: [] })),
    buildCase('edge: equal times keep the first one', draft({ times: [time('2026-05-01T07:00:00.000Z', 60), time('2026-05-01T07:00:00.000Z', OFFSET_MINUTES)], mediaLocations: [BERLIN, PARIS] })),
    buildCase('edge: the device location without accuracy', draft({ deviceLocation: { point: DEVICE_POINT, accuracyMeters: null } })),
    editCase('changes only the fields that are present and stamps the edit time', existing(), { note: 'after' }, LATER),
    editCase('replaces the tags', existing(), { tags: ['a', 'z'] }, LATER),
    editCase('replaces the time but keeps the zone label', existing(), { time: { takenAtUtc: '2026-05-02T10:00:00.000Z', utcOffsetMinutes: OFFSET_MINUTES } }, LATER),
    editCase('normalizes, deduplicates and sorts edited tags', existing(), { tags: ['Zoo', 'apple', ' ZOO '] }, LATER),
    editCase('refuses an edit with an empty tag', existing(), { tags: [''] }, LATER),
    editCase('refuses an edit with a tag over the length limit', existing(), { tags: ['y'.repeat(TAG_MAX_CHARS + 1)] }, LATER),
    editCase('sets the location', existing(), { location: place }, LATER),
    editCase('clears the location', withPlace, { location: null }, LATER),
    editCase('reorders the media by the given permutation', existing(), { mediaOrder: [ID_THREE, ID_ONE, ID_TWO] }, LATER),
    editCase('refuses a media order with an id that is missing', existing(), { mediaOrder: [ID_ONE, ID_TWO] }, LATER),
    editCase('refuses a media order with an unknown id', existing(), { mediaOrder: [ID_ONE, ID_TWO, UNKNOWN_ID] }, LATER),
    editCase('refuses a media order with a repeated id', existing(), { mediaOrder: [ID_ONE, ID_ONE, ID_TWO] }, LATER),
    editCase('refuses a media order with an extra id', existing(), { mediaOrder: [...IDS, ID_ONE] }, LATER),
    editCase('refuses a media order with no ids', existing(), { mediaOrder: [] }, LATER),
    editCase('appends added media after the current ones', existing(), { addMedia: [descriptor(ADDED_ID)] }, LATER),
    editCase('removes one media item and keeps the rest in order', existing(), { removeMediaId: ID_TWO }, LATER),
    editCase('refuses to remove the last media item', existing({ media: [descriptor(ID_ONE)] }), { removeMediaId: ID_ONE }, LATER),
    editCase('refuses to remove a media item that is not in the entry', existing(), { removeMediaId: UNKNOWN_ID }, LATER),
    editCase('does not change the input', existing(), { note: 'x', tags: [], removeMediaId: ID_ONE }, LATER),
    editCase('returns metadata that the contract schema accepts', existing(), { note: 'x', tags: ['q'] }, LATER),
    editCase('edge: an order, a removal and an addition apply in that order', existing(), { mediaOrder: [ID_THREE, ID_TWO, ID_ONE], removeMediaId: ID_THREE, addMedia: [descriptor(ADDED_ID)] }, LATER),
    editCase('edge: removing the last media item while adding one is allowed', existing({ media: [descriptor(ID_ONE)] }), { removeMediaId: ID_ONE, addMedia: [descriptor(ADDED_ID)] }, LATER),
    editCase('edge: an empty edit only stamps the edit time', existing(), {}, LATER),
    editCase('captions: sets and trims a caption', existing(), { captions: { [ID_ONE]: '  Sunset  ' } }, LATER),
    editCase('captions: clears a caption with empty text', captioned, { captions: { [ID_ONE]: '' } }, LATER),
    editCase('captions: ignores captions for media that are not in the entry', existing(), { captions: { unknown: 'x' } }, LATER),
    editCase('captions edge: blank text clears a caption', captioned, { captions: { [ID_ONE]: ' \t ' } }, LATER),
    editCase('captions edge: a caption applies to media added in the same edit', existing(), { addMedia: [descriptor(ADDED_ID)], captions: { [ADDED_ID]: 'new' } }, LATER),
    editCase('favorites: sets the flag through an edit', favoriteMetadata(), { favorite: true }, NOW),
    editCase('favorites: keeps the flag otherwise', marked, { note: 'x' }, NOW),
    editCase('favorites: clears the flag through an edit', marked, { favorite: false }, NOW),
    functionCase(
      'favorites: accepts metadata with and without the favorite flag',
      'applyEntryEdit',
      { current: toJson(favoriteMetadata({ favorite: true })), edit: {}, nowUtc: NOW },
      () => applyEntryEdit(favoriteMetadata({ favorite: true }), {}, NOW),
    ),
    ...favoriteCases(),
    functionCase('limits: keeps MIN_OBJECT_BYTES equal to the smallest STREAM object', 'MIN_OBJECT_BYTES', {}, () => ({
      MIN_OBJECT_BYTES,
      STREAM_MIN_OBJECT_BYTES,
    })),
  ];
}

export async function writeDomainGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATHS.entryTime, entryTimeCases());
  await writer.functionCases(PATHS.calendar, calendarCases());
  await writer.functionCases(PATHS.tags, tagCases());
  await writer.functionCases(PATHS.entryEdit, entryEditCases());
}
