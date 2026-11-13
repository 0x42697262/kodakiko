import { normalizeTag } from '../domain/tags.ts';
import { LOCAL_DATE_SEPARATOR, MONTH_DAY_START, SORT_BEFORE, YEAR_END } from './constants.ts';
import { tokenize } from './tokenize.ts';
import type { AttributeFilters, AttributeKey, DaySection, GalleryFilter, IndexedEntry } from './types.ts';

export type OnThisDayGroup = { readonly yearsAgo: number; readonly entryIds: readonly string[] };

export type GalleryIndex = {
  readonly upsert: (entry: IndexedEntry, noteText: string) => void;
  readonly remove: (entryId: string) => void;
  readonly clear: () => void;
  readonly get: (entryId: string) => IndexedEntry | null;
  readonly size: () => number;
  readonly query: (filter: GalleryFilter) => readonly DaySection[];
  readonly tagSuggestions: (prefix: string, limit: number) => readonly string[];
  readonly allTags: () => readonly { readonly tag: string; readonly count: number }[];
  readonly onThisDay: (
    todayLocalDate: string,
    scope: GalleryFilter['scope'],
  ) => readonly OnThisDayGroup[];
  readonly daysWithEntries: (
    yearMonth: string,
    scope: GalleryFilter['scope'],
  ) => ReadonlyMap<string, string>;
  readonly version: () => number;
};

type Stored = {
  readonly entry: IndexedEntry;
  readonly takenAtMs: number;
  readonly tokens: readonly string[];
  readonly indexedTags: readonly string[];
  readonly counted: boolean;
};

function addTo(map: Map<string, Set<string>>, key: string, id: string): void {
  const set = map.get(key) ?? new Set<string>();
  set.add(id);
  map.set(key, set);
}

function removeFrom(map: Map<string, Set<string>>, key: string, id: string): void {
  const set = map.get(key);
  if (set === undefined) return;
  set.delete(id);
  if (set.size === 0) map.delete(key);
}

function intersect(sets: readonly ReadonlySet<string>[]): ReadonlySet<string> {
  const [smallest, ...others] = [...sets].sort((left, right) => left.size - right.size);
  if (smallest === undefined) return new Set();
  const result = new Set<string>();
  for (const id of smallest) {
    if (others.every((set) => set.has(id))) result.add(id);
  }
  return result;
}

function lowerBound(sorted: readonly string[], target: string): number {
  let low = 0;
  let high = sorted.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if ((sorted[middle] ?? '') < target) low = middle + 1;
    else high = middle;
  }
  return low;
}

function normalizedPrefix(prefix: string): string | null {
  if (prefix.trim() === '') return '';
  return normalizeTag(prefix);
}

function yearOf(localDate: string): number {
  return Number(localDate.slice(0, YEAR_END));
}

function attributeOf(entry: IndexedEntry, key: AttributeKey, homeCollectionId: string): boolean {
  switch (key) {
    case 'video':
      return entry.hasVideo;
    case 'note':
      return entry.notePreview !== '';
    case 'multiple':
      return entry.mediaCount > 1;
    case 'shared':
      return !entry.isOwn || entry.collectionIds.some((id) => id !== homeCollectionId);
    case 'pending':
      return entry.pending;
    default:
      return false;
  }
}

const ATTRIBUTE_KEYS: readonly AttributeKey[] = ['video', 'note', 'multiple', 'shared', 'pending'];

export function attributesMatch(entry: IndexedEntry, filters: AttributeFilters, homeCollectionId: string): boolean {
  for (const key of ATTRIBUTE_KEYS) {
    const state = filters[key] ?? 'off';
    if (state === 'off') continue;
    if (attributeOf(entry, key, homeCollectionId) !== (state === 'include')) return false;
  }
  return true;
}

export function createGalleryIndex(options: { readonly homeCollectionId: string }): GalleryIndex {
  const entries = new Map<string, Stored>();
  const byTag = new Map<string, Set<string>>();
  const tagCounts = new Map<string, number>();
  const words = new Map<string, Set<string>>();
  const byMonthDay = new Map<string, Set<string>>();
  let orderedIds: string[] = [];
  let sortedWords: string[] | null = null;
  let dirty = false;
  let currentVersion = 0;

  function compare(leftId: string, rightId: string): number {
    const left = entries.get(leftId);
    const right = entries.get(rightId);
    const byTime = (right?.takenAtMs ?? 0) - (left?.takenAtMs ?? 0);
    if (byTime !== 0) return byTime;
    if (leftId === rightId) return 0;
    return leftId < rightId ? SORT_BEFORE : 1;
  }

  function ordered(): readonly string[] {
    if (dirty) {
      orderedIds = [...entries.keys()].sort(compare);
      dirty = false;
    }
    return orderedIds;
  }

  function wordKeys(): readonly string[] {
    sortedWords ??= [...words.keys()].sort();
    return sortedWords;
  }

  function inScope(entry: IndexedEntry, scope: GalleryFilter['scope']): boolean {
    const collectionId = scope.kind === 'home' ? options.homeCollectionId : scope.collectionId;
    return entry.collectionIds.includes(collectionId);
  }

  function detach(entryId: string): boolean {
    const stored = entries.get(entryId);
    if (stored === undefined) return false;
    for (const tag of stored.indexedTags) {
      removeFrom(byTag, tag, entryId);
      if (stored.counted) {
        const count = (tagCounts.get(tag) ?? 0) - 1;
        if (count > 0) tagCounts.set(tag, count);
        else tagCounts.delete(tag);
      }
    }
    for (const token of stored.tokens) {
      const before = words.size;
      removeFrom(words, token, entryId);
      if (words.size !== before) sortedWords = null;
    }
    removeFrom(byMonthDay, stored.entry.monthDay, entryId);
    entries.delete(entryId);
    dirty = true;
    return true;
  }

  function upsert(entry: IndexedEntry, noteText: string): void {
    detach(entry.id);
    const indexedTags = entry.unreadable ? [] : [...new Set(entry.tags)];
    const tokens = entry.unreadable ? [] : tokenize(noteText);
    const counted = entry.isOwn && !entry.trashed && !entry.unreadable;
    entries.set(entry.id, {
      entry,
      takenAtMs: Date.parse(entry.takenAtUtc),
      tokens,
      indexedTags,
      counted,
    });
    for (const tag of indexedTags) {
      addTo(byTag, tag, entry.id);
      if (counted) tagCounts.set(tag, (tagCounts.get(tag) ?? 0) + 1);
    }
    for (const token of tokens) {
      if (!words.has(token)) sortedWords = null;
      addTo(words, token, entry.id);
    }
    addTo(byMonthDay, entry.monthDay, entry.id);
    dirty = true;
    currentVersion += 1;
  }

  function remove(entryId: string): void {
    if (detach(entryId)) currentVersion += 1;
  }

  function clear(): void {
    entries.clear();
    byTag.clear();
    tagCounts.clear();
    words.clear();
    byMonthDay.clear();
    orderedIds = [];
    sortedWords = null;
    dirty = false;
    currentVersion += 1;
  }

  function idsWithPrefix(token: string): ReadonlySet<string> {
    const keys = wordKeys();
    const matches = new Set<string>();
    for (let position = lowerBound(keys, token); position < keys.length; position += 1) {
      const key = keys[position] ?? '';
      if (!key.startsWith(token)) break;
      for (const id of words.get(key) ?? []) matches.add(id);
    }
    return matches;
  }

  function candidates(filter: GalleryFilter): ReadonlySet<string> | null {
    const sets: ReadonlySet<string>[] = filter.tags.map((tag) => byTag.get(tag) ?? new Set());
    for (const token of tokenize(filter.text)) sets.push(idsWithPrefix(token));
    return sets.length === 0 ? null : intersect(sets);
  }

  function matches(entry: IndexedEntry, filter: GalleryFilter): boolean {
    if (!inScope(entry, filter.scope)) return false;
    if (filter.attributes !== undefined && !attributesMatch(entry, filter.attributes, options.homeCollectionId)) return false;
    if (entry.trashed !== (filter.view === 'trash')) return false;
    if (filter.fromDate !== null && entry.localDate < filter.fromDate) return false;
    return filter.toDate === null || entry.localDate <= filter.toDate;
  }

  function query(filter: GalleryFilter): readonly DaySection[] {
    const allowed = candidates(filter);
    const sections: { localDate: string; entryIds: string[] }[] = [];
    for (const id of ordered()) {
      if (allowed !== null && !allowed.has(id)) continue;
      const entry = entries.get(id)?.entry;
      if (entry === undefined || !matches(entry, filter)) continue;
      const last = sections[sections.length - 1];
      if (last !== undefined && last.localDate === entry.localDate) last.entryIds.push(id);
      else sections.push({ localDate: entry.localDate, entryIds: [id] });
    }
    return sections;
  }

  function allTags(): readonly { readonly tag: string; readonly count: number }[] {
    return [...tagCounts.entries()]
      .filter(([, count]) => count > 0)
      .sort(([left], [right]) => (left < right ? SORT_BEFORE : 1))
      .map(([tag, count]) => ({ tag, count }));
  }

  function tagSuggestions(prefix: string, limit: number): readonly string[] {
    const normalized = normalizedPrefix(prefix);
    if (normalized === null) return [];
    return [...tagCounts.entries()]
      .filter(([tag]) => tag.startsWith(normalized))
      .sort(([leftTag, leftCount], [rightTag, rightCount]) =>
        leftCount !== rightCount ? rightCount - leftCount : leftTag < rightTag ? SORT_BEFORE : 1,
      )
      .slice(0, limit)
      .map(([tag]) => tag);
  }

  function onThisDay(
    todayLocalDate: string,
    scope: GalleryFilter['scope'],
  ): readonly OnThisDayGroup[] {
    const todayYear = yearOf(todayLocalDate);
    const groups = new Map<number, string[]>();
    const ids = [...(byMonthDay.get(todayLocalDate.slice(MONTH_DAY_START)) ?? [])].sort(compare);
    for (const id of ids) {
      const entry = entries.get(id)?.entry;
      if (entry === undefined || entry.trashed || !inScope(entry, scope)) continue;
      const yearsAgo = todayYear - yearOf(entry.localDate);
      if (yearsAgo < 1) continue;
      const group = groups.get(yearsAgo) ?? [];
      group.push(id);
      groups.set(yearsAgo, group);
    }
    return [...groups.entries()]
      .sort(([left], [right]) => left - right)
      .map(([yearsAgo, entryIds]) => ({ yearsAgo, entryIds }));
  }

  function daysWithEntries(
    yearMonth: string,
    scope: GalleryFilter['scope'],
  ): ReadonlyMap<string, string> {
    const prefix = `${yearMonth}${LOCAL_DATE_SEPARATOR}`;
    const covers = new Map<string, string>();
    for (const id of ordered()) {
      const entry = entries.get(id)?.entry;
      if (entry === undefined || entry.trashed || !entry.localDate.startsWith(prefix)) continue;
      if (!inScope(entry, scope) || covers.has(entry.localDate)) continue;
      covers.set(entry.localDate, id);
    }
    return covers;
  }

  return {
    upsert,
    remove,
    clear,
    get: (entryId) => entries.get(entryId)?.entry ?? null,
    size: () => entries.size,
    query,
    allTags,
    tagSuggestions,
    onThisDay,
    daysWithEntries,
    version: () => currentVersion,
  };
}
