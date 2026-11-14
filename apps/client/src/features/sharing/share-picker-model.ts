export const SHARE_WARN_BYTES = 524_288_000;

export type ShareTextOption = 'none' | 'captions' | 'captionsAndNote';
export type ShareItem = { readonly mediaId: string; readonly bytes: number; readonly caption?: string | undefined };

export function shareSelection(items: readonly ShareItem[], chosen: ReadonlySet<string>) {
  const picked = items.filter((item) => chosen.has(item.mediaId));
  const bytes = picked.reduce((sum, item) => sum + item.bytes, 0);
  return { count: picked.length, bytes, warn: bytes > SHARE_WARN_BYTES };
}

export function shareText(option: ShareTextOption, items: readonly ShareItem[], chosen: ReadonlySet<string>, note: string): string | null {
  if (option === 'none') return null;
  const captions = items.filter((item) => chosen.has(item.mediaId) && item.caption !== undefined).map((item) => item.caption);
  const parts = [captions.join('\n'), option === 'captionsAndNote' ? note.trim() : ''].filter((part) => part !== '');
  return parts.length === 0 ? null : parts.join('\n\n');
}
