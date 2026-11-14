export function searchTextOf(metadata: { readonly note: string; readonly media: readonly { readonly caption?: string | undefined }[] }): string {
  const captions = metadata.media.flatMap((item) => (item.caption === undefined ? [] : [item.caption]));
  return [metadata.note, ...captions].join('\n');
}
