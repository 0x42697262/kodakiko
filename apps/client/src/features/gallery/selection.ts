import { NOT_FOUND_INDEX } from '../../constants/gallery-constants';

export function toggleSelected(selected: ReadonlySet<string>, entryId: string): ReadonlySet<string> {
  const next = new Set(selected);
  if (next.has(entryId)) next.delete(entryId);
  else next.add(entryId);
  return next;
}

export function selectRange(
  selected: ReadonlySet<string>,
  order: readonly string[],
  anchorId: string | null,
  targetId: string,
): ReadonlySet<string> {
  const next = new Set(selected);
  const anchor = anchorId === null ? NOT_FOUND_INDEX : order.indexOf(anchorId);
  const target = order.indexOf(targetId);
  if (anchor === NOT_FOUND_INDEX || target === NOT_FOUND_INDEX) {
    next.add(targetId);
    return next;
  }
  const [from, to] = anchor <= target ? [anchor, target] : [target, anchor];
  for (const entryId of order.slice(from, to + 1)) next.add(entryId);
  return next;
}
