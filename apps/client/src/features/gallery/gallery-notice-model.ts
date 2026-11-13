export type GalleryNotice =
  | { readonly kind: 'gone' }
  | { readonly kind: 'trashed'; readonly entryId: string };
