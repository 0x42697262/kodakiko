export type IndexedEntry = {
  readonly id: string;
  readonly isOwn: boolean;
  readonly collectionIds: readonly string[];
  readonly trashed: boolean;
  readonly unreadable: boolean;
  readonly takenAtUtc: string;
  readonly utcOffsetMinutes: number;
  readonly localDate: string;
  readonly monthDay: string;
  readonly tags: readonly string[];
  readonly coverMediaId: string | null;
  readonly mediaCount: number;
  readonly hasVideo: boolean;
  readonly notePreview: string;
  readonly pending: boolean;
};

export type GalleryFilter = {
  readonly scope: { readonly kind: 'home' } | { readonly kind: 'collection'; readonly collectionId: string };
  readonly view: 'journal' | 'trash';
  readonly tags: readonly string[];
  readonly fromDate: string | null;
  readonly toDate: string | null;
  readonly text: string;
};

export type DaySection = { readonly localDate: string; readonly entryIds: readonly string[] };
