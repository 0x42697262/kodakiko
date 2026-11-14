import type { IndexedEntry } from '@kodakiko/core';

export type JournalStatistics = {
  readonly entries: number;
  readonly mediaItems: number;
  readonly entriesWithVideo: number;
  readonly entriesWithNote: number;
  readonly waitingToUpload: number;
  readonly photos: number;
  readonly videos: number;
  readonly videoMs: number;
  readonly originalBytes: number;
  readonly favorites: number;
  readonly tagCount: number;
  readonly topTags: readonly { readonly tag: string; readonly count: number }[];
  readonly firstDay: string | null;
  readonly lastDay: string | null;
  readonly perMonth: readonly { readonly month: string; readonly count: number }[];
  readonly perYear: readonly { readonly year: string; readonly count: number }[];
};

const TOP_TAGS = 10;
const MONTHS_SHOWN = 12;
const MONTHS_PER_YEAR = 12;
const YEAR_CHARS = 4;
const MONTH_CHARS = 7;
const MONTH_START = 5;
const PAD = 2;

function monthsEndingAt(today: string): string[] {
  let year = Number(today.slice(0, YEAR_CHARS));
  let month = Number(today.slice(MONTH_START, MONTH_CHARS));
  const months: string[] = [];
  for (let step = 0; step < MONTHS_SHOWN; step += 1) {
    months.unshift(`${year}-${String(month).padStart(PAD, '0')}`);
    month -= 1;
    if (month === 0) {
      month = MONTHS_PER_YEAR;
      year -= 1;
    }
  }
  return months;
}

export function journalStatistics(entries: readonly IndexedEntry[], todayLocalDate: string): JournalStatistics {
  const tags = new Map<string, number>();
  const months = new Map<string, number>();
  const years = new Map<string, number>();
  let first: string | null = null;
  let last: string | null = null;
  for (const entry of entries) {
    for (const tag of entry.tags) tags.set(tag, (tags.get(tag) ?? 0) + 1);
    const month = entry.localDate.slice(0, MONTH_CHARS);
    const year = entry.localDate.slice(0, YEAR_CHARS);
    months.set(month, (months.get(month) ?? 0) + 1);
    years.set(year, (years.get(year) ?? 0) + 1);
    if (first === null || entry.localDate < first) first = entry.localDate;
    if (last === null || entry.localDate > last) last = entry.localDate;
  }
  return {
    entries: entries.length,
    mediaItems: entries.reduce((sum, entry) => sum + entry.mediaCount, 0),
    entriesWithVideo: entries.filter((entry) => entry.hasVideo).length,
    entriesWithNote: entries.filter((entry) => entry.notePreview !== '').length,
    waitingToUpload: entries.filter((entry) => entry.pending).length,
    photos: entries.reduce((sum, entry) => sum + entry.mediaCount - (entry.videoCount ?? (entry.hasVideo ? 1 : 0)), 0),
    videos: entries.reduce((sum, entry) => sum + (entry.videoCount ?? (entry.hasVideo ? 1 : 0)), 0),
    videoMs: entries.reduce((sum, entry) => sum + (entry.videoMs ?? 0), 0),
    originalBytes: entries.reduce((sum, entry) => sum + (entry.originalBytes ?? 0), 0),
    favorites: entries.filter((entry) => entry.favorite === true).length,
    tagCount: tags.size,
    topTags: [...tags.entries()]
      .sort(([leftTag, left], [rightTag, right]) => right - left || leftTag.localeCompare(rightTag))
      .slice(0, TOP_TAGS)
      .map(([tag, count]) => ({ tag, count })),
    firstDay: first,
    lastDay: last,
    perMonth: monthsEndingAt(todayLocalDate).map((month) => ({ month, count: months.get(month) ?? 0 })),
    perYear: [...years.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([year, count]) => ({ year, count })),
  };
}
