import { localDateOf, type DaySection, type GallerySort, type IndexedEntry } from '@kodakiko/core';
import { GRID_MIN_COLUMNS, GRID_TILE_MIN_PX } from '../../src/constants/gallery-constants';
import { GALLERY_COLUMNS_AUTO, UI_SETTINGS_DEFAULTS } from '../../src/constants/ui-settings-constants';
import {
  arrangeSections,
  columnsFor,
  galleryAttributes,
  groupSections,
  MONTH_GROUP_PREFIX,
  TAG_GROUP_PREFIX,
  UNGROUPED_DATE,
  UNTAGGED_GROUP,
  YEAR_GROUP_PREFIX,
  type GroupBy,
} from '../../src/features/gallery/gallery-arrange';
import { buildGalleryItems } from '../../src/features/gallery/gallery-items';
import type { DeviceSettingsValue } from '../../src/services/settings/device-settings';
import { functionCase, toJson, type GoldenCase, type GoldenWriter, type Json } from './shared';

const PATH = 'gallery/arrange.json';
const HOME = 'home';
const MONTH_DAY_START = 5;
const SEED = 0;
const RANDOM_SEED = 7;

type Filters = DeviceSettingsValue['galleryFilters'];
type EntryPatch = Partial<Omit<IndexedEntry, 'id' | 'takenAtUtc'>>;

function entry(id: string, takenAtUtc: string, patch: EntryPatch = {}): IndexedEntry {
  const utcOffsetMinutes = patch.utcOffsetMinutes ?? 0;
  const localDate = localDateOf(takenAtUtc, utcOffsetMinutes);
  return {
    id,
    isOwn: true,
    collectionIds: [HOME],
    trashed: false,
    unreadable: false,
    takenAtUtc,
    utcOffsetMinutes,
    localDate,
    monthDay: localDate.slice(MONTH_DAY_START),
    tags: [],
    coverMediaId: null,
    mediaCount: 1,
    hasVideo: false,
    notePreview: '',
    pending: false,
    ...patch,
  };
}

const NEWEST: GallerySort = { key: 'takenAt', descending: true };
const OLDEST: GallerySort = { key: 'takenAt', descending: false };
const MOST_MEDIA: GallerySort = { key: 'mediaCount', descending: true };
const RANDOM: GallerySort = { key: 'random', descending: true };
const SHORTEST_NOTE: GallerySort = { key: 'noteLength', descending: false };

const ARRANGE_ENTRIES: readonly IndexedEntry[] = [
  entry('a', '2026-11-02T10:00:00.000Z', { mediaCount: 3 }),
  entry('b', '2026-11-02T08:00:00.000Z', { mediaCount: 1 }),
  entry('c', '2026-11-01T09:00:00.000Z', { mediaCount: 2 }),
];
const ARRANGE_SECTIONS: readonly DaySection[] = [
  { localDate: '2026-11-02', entryIds: ['a', 'b'] },
  { localDate: '2026-11-01', entryIds: ['c'] },
];

const GROUP_ENTRIES: readonly IndexedEntry[] = [
  entry('a', '2026-11-02T10:00:00.000Z', { tags: ['beach'] }),
  entry('b', '2026-10-02T08:00:00.000Z', { tags: ['beach', 'city'] }),
  entry('c', '2025-11-01T09:00:00.000Z', { tags: [] }),
];
const GROUP_SECTIONS: readonly DaySection[] = [
  { localDate: '2026-11-02', entryIds: ['a'] },
  { localDate: '2026-10-02', entryIds: ['b'] },
  { localDate: '2025-11-01', entryIds: ['c'] },
];

const RICH_ENTRIES: readonly IndexedEntry[] = [
  entry('e1', '2026-12-31T23:30:00.000Z', { mediaCount: 2, notePreview: 'new year', tags: ['Zebra', 'apple'] }),
  entry('e2', '2026-12-31T22:00:00.000Z', { utcOffsetMinutes: 120, mediaCount: 5, notePreview: '', tags: ['éclair', '10'] }),
  entry('e3', '2026-06-15T12:00:00.000Z', { mediaCount: 1, notePreview: 'a much longer note', tags: ['Apfel', 'eclair'] }),
  entry('e4', '2025-12-31T12:00:00.000Z', { mediaCount: 5, notePreview: 'mid', tags: ['9', 'b c'] }),
  entry('e5', '2025-01-01T00:00:00.000Z', { mediaCount: 3, notePreview: '🌸', tags: ['b-c', 'beach'] }),
  entry('e6', '2024-02-29T08:00:00.000Z', { mediaCount: 1, notePreview: 'x', tags: [] }),
];
const RICH_SECTIONS: readonly DaySection[] = [
  { localDate: '2026-12-31', entryIds: ['e1'] },
  { localDate: '2027-01-01', entryIds: ['e2'] },
  { localDate: '2026-06-15', entryIds: ['e3'] },
  { localDate: '2025-12-31', entryIds: ['e4'] },
  { localDate: '2025-01-01', entryIds: ['e5'] },
  { localDate: '2024-02-29', entryIds: ['e6'] },
];
const MISSING_SECTIONS: readonly DaySection[] = [
  { localDate: '2026-11-02', entryIds: ['a', 'ghost'] },
  { localDate: '2026-11-01', entryIds: ['c'] },
];

function lookup(entries: readonly IndexedEntry[]): (id: string) => IndexedEntry | null {
  const byId = new Map(entries.map((item) => [item.id, item]));
  return (id) => byId.get(id) ?? null;
}

function sortJson(sort: GallerySort): Json {
  return { key: sort.key, descending: sort.descending };
}

type ArrangeSpec = {
  readonly name: string;
  readonly sections: readonly DaySection[];
  readonly entries: readonly IndexedEntry[];
  readonly sort: GallerySort;
  readonly groupByDay: boolean;
  readonly seed: number;
};

const ARRANGE_SPECS: readonly ArrangeSpec[] = [
  { name: 'keeps day sections for newest-first date order', sections: ARRANGE_SECTIONS, entries: ARRANGE_ENTRIES, sort: NEWEST, groupByDay: true, seed: SEED },
  { name: 'reverses days and entries for oldest-first date order', sections: ARRANGE_SECTIONS, entries: ARRANGE_ENTRIES, sort: OLDEST, groupByDay: true, seed: SEED },
  {
    name: 'returns one ungrouped section for other sorts or when grouping is off (media count)',
    sections: ARRANGE_SECTIONS,
    entries: ARRANGE_ENTRIES,
    sort: MOST_MEDIA,
    groupByDay: true,
    seed: SEED,
  },
  {
    name: 'returns one ungrouped section for other sorts or when grouping is off (grouping off)',
    sections: ARRANGE_SECTIONS,
    entries: ARRANGE_ENTRIES,
    sort: NEWEST,
    groupByDay: false,
    seed: SEED,
  },
  { name: 'edge: no sections and grouping off', sections: [], entries: [], sort: NEWEST, groupByDay: false, seed: SEED },
  { name: 'edge: no sections, day grouping, newest first', sections: [], entries: [], sort: NEWEST, groupByDay: true, seed: SEED },
  {
    name: 'edge: empty day sections are kept newest first and dropped otherwise (newest)',
    sections: [{ localDate: '2026-11-02', entryIds: [] }, ...ARRANGE_SECTIONS],
    entries: ARRANGE_ENTRIES,
    sort: NEWEST,
    groupByDay: true,
    seed: SEED,
  },
  {
    name: 'edge: empty day sections are kept newest first and dropped otherwise (oldest)',
    sections: [{ localDate: '2026-11-03', entryIds: [] }, ...ARRANGE_SECTIONS],
    entries: ARRANGE_ENTRIES,
    sort: OLDEST,
    groupByDay: true,
    seed: SEED,
  },
  { name: 'edge: oldest first without day grouping', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: OLDEST, groupByDay: false, seed: SEED },
  { name: 'edge: shortest note first', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: SHORTEST_NOTE, groupByDay: true, seed: SEED },
  { name: 'edge: random order with seed 7', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: RANDOM, groupByDay: true, seed: RANDOM_SEED },
  { name: 'edge: an id without an entry sorts last', sections: MISSING_SECTIONS, entries: ARRANGE_ENTRIES, sort: MOST_MEDIA, groupByDay: true, seed: SEED },
];

type GroupSpec = {
  readonly name: string;
  readonly sections: readonly DaySection[];
  readonly entries: readonly IndexedEntry[];
  readonly sort: GallerySort;
  readonly groupBy: GroupBy;
  readonly seed: number;
};

const GROUP_SPECS: readonly GroupSpec[] = [
  { name: 'groups by month and by year, newest first (month)', sections: GROUP_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'month', seed: SEED },
  { name: 'groups by month and by year, newest first (year)', sections: GROUP_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'year', seed: SEED },
  {
    name: 'groups by tag alphabetically, lists an entry under each of its tags, and puts untagged entries last',
    sections: GROUP_SECTIONS,
    entries: GROUP_ENTRIES,
    sort: NEWEST,
    groupBy: 'tag',
    seed: SEED,
  },
  { name: 'keeps day sections for day grouping and one section for none (day)', sections: GROUP_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'day', seed: SEED },
  { name: 'keeps day sections for day grouping and one section for none (none)', sections: GROUP_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'none', seed: SEED },
  { name: 'edge: month groups oldest first', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: OLDEST, groupBy: 'month', seed: SEED },
  { name: 'edge: year groups oldest first', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: OLDEST, groupBy: 'year', seed: SEED },
  { name: 'edge: month groups use the local date, not the UTC date', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: NEWEST, groupBy: 'month', seed: SEED },
  { name: 'edge: year groups follow first appearance for media count order', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: MOST_MEDIA, groupBy: 'year', seed: SEED },
  { name: 'edge: month groups follow first appearance for random order', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: RANDOM, groupBy: 'month', seed: RANDOM_SEED },
  { name: 'edge: tag groups use locale collation', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: NEWEST, groupBy: 'tag', seed: SEED },
  { name: 'edge: tag groups keep the oldest-first entry order', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: OLDEST, groupBy: 'tag', seed: SEED },
  { name: 'edge: day grouping with a non-date sort is one ungrouped section', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: SHORTEST_NOTE, groupBy: 'day', seed: SEED },
  { name: 'edge: day grouping oldest first', sections: RICH_SECTIONS, entries: RICH_ENTRIES, sort: OLDEST, groupBy: 'day', seed: SEED },
  { name: 'edge: only untagged entries', sections: ARRANGE_SECTIONS, entries: ARRANGE_ENTRIES, sort: NEWEST, groupBy: 'tag', seed: SEED },
  { name: 'edge: an id without an entry is untagged', sections: MISSING_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'tag', seed: SEED },
  { name: 'edge: an id without an entry groups under the bare month prefix', sections: MISSING_SECTIONS, entries: GROUP_ENTRIES, sort: NEWEST, groupBy: 'month', seed: SEED },
  { name: 'edge: no sections grouped by tag', sections: [], entries: [], sort: NEWEST, groupBy: 'tag', seed: SEED },
  { name: 'edge: no sections grouped by none', sections: [], entries: [], sort: NEWEST, groupBy: 'none', seed: SEED },
];

function arrangeCases(): GoldenCase[] {
  return ARRANGE_SPECS.map((spec) =>
    functionCase(
      `arrangeSections: ${spec.name}`,
      'arrangeSections',
      { sections: toJson(spec.sections), entries: toJson(spec.entries), sort: sortJson(spec.sort), groupByDay: spec.groupByDay, seed: spec.seed },
      () => arrangeSections(spec.sections, lookup(spec.entries), spec.sort, spec.groupByDay, spec.seed),
    ),
  );
}

function groupCases(): GoldenCase[] {
  return GROUP_SPECS.map((spec) =>
    functionCase(
      `groupSections: ${spec.name}`,
      'groupSections',
      { sections: toJson(spec.sections), entries: toJson(spec.entries), sort: sortJson(spec.sort), groupBy: spec.groupBy, seed: spec.seed },
      () => groupSections(spec.sections, lookup(spec.entries), spec.sort, spec.groupBy, spec.seed),
    ),
  );
}

const DEFAULT_FILTERS: Filters = { ...UI_SETTINGS_DEFAULTS.galleryFilters };

const ATTRIBUTE_SPECS: readonly (readonly [string, Filters, boolean])[] = [
  ['maps the tri-state settings and hides shared journals on request (video include)', { ...DEFAULT_FILTERS, video: 'include' }, false],
  ['maps the tri-state settings and hides shared journals on request (hide shared)', DEFAULT_FILTERS, true],
  ['edge: hide shared overrides an included shared filter', { ...DEFAULT_FILTERS, shared: 'include' }, true],
  [
    'edge: every attribute set, kept offline is dropped',
    { video: 'exclude', note: 'include', multiple: 'include', shared: 'exclude', pending: 'include', keptOffline: 'include', favorite: 'exclude' },
    false,
  ],
];

function attributeCases(): GoldenCase[] {
  return ATTRIBUTE_SPECS.map(([name, filters, hideShared]) =>
    functionCase(`galleryAttributes: ${name}`, 'galleryAttributes', { filters: toJson(filters), hideShared }, () => galleryAttributes(filters, hideShared)),
  );
}

const COLUMN_SPECS: readonly (readonly [string, number, number, number, number])[] = [
  ['uses the portrait or landscape setting and falls back to the automatic count (portrait)', 400, 800, 3, 6],
  ['uses the portrait or landscape setting and falls back to the automatic count (landscape)', 800, 400, 3, 6],
  ['uses the portrait or landscape setting and falls back to the automatic count (automatic)', 400, 800, 0, 6],
  ['edge: a square screen counts as portrait', 500, 500, 2, 7],
  ['edge: automatic landscape count', 1280, 720, 4, 0],
  ['edge: automatic count never goes below the minimum', 100, 800, 0, 0],
  ['edge: automatic count with a zero width', 0, 0, 0, 0],
  ['edge: automatic count floors fractional widths', 439.9, 900, 0, 0],
  ['edge: automatic count at an exact tile multiple', 440, 900, 0, 0],
  ['edge: automatic count on a wide tablet', 2560, 1600, 0, 0],
  ['edge: explicit maximum', 2560, 1600, 0, 10],
];

function columnCases(): GoldenCase[] {
  return COLUMN_SPECS.map(([name, width, height, portrait, landscape]) =>
    functionCase(`columnsFor: ${name}`, 'columnsFor', { width, height, portrait, landscape }, () => columnsFor(width, height, portrait, landscape)),
  );
}

const ITEM_SPECS: readonly (readonly [string, readonly DaySection[], number, boolean])[] = [
  ['builds no day header for the ungrouped section', [{ localDate: UNGROUPED_DATE, entryIds: ['a', 'b'] }], 2, false],
  [
    'edge: day headers, rows of 3 and the on-this-day item',
    [
      { localDate: '2026-11-02', entryIds: ['a', 'b', 'c', 'd', 'e', 'f', 'g'] },
      { localDate: '2026-11-01', entryIds: ['h'] },
    ],
    3,
    true,
  ],
  ['edge: group headers and an empty section', [{ localDate: 't:beach', entryIds: [] }, { localDate: UNTAGGED_GROUP, entryIds: ['a', 'b', 'c'] }], 3, false],
  ['edge: nothing to show', [], 4, true],
];

function itemCases(): GoldenCase[] {
  return ITEM_SPECS.map(([name, sections, columns, showOnThisDay]) =>
    functionCase(`buildGalleryItems: ${name}`, 'buildGalleryItems', { sections: toJson(sections), columns, showOnThisDay }, () =>
      buildGalleryItems(sections, columns, showOnThisDay),
    ),
  );
}

export async function writeGalleryGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATH, [
    ...groupCases(),
    ...arrangeCases(),
    ...attributeCases(),
    ...columnCases(),
    ...itemCases(),
    {
      name: 'constants',
      fn: 'constants',
      args: {},
      result: {
        UNGROUPED_DATE,
        MONTH_GROUP_PREFIX,
        YEAR_GROUP_PREFIX,
        TAG_GROUP_PREFIX,
        UNTAGGED_GROUP,
        GALLERY_COLUMNS_AUTO,
        GRID_MIN_COLUMNS,
        GRID_TILE_MIN_PX,
      },
    },
  ]);
}
