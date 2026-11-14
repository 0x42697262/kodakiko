export type CountedTag = { readonly tag: string; readonly count: number };

export function orderTags(counted: readonly CountedTag[], order: readonly string[]): CountedTag[] {
  const counts = new Map(counted.map((item) => [item.tag, item.count]));
  const ordered = order.map((tag) => ({ tag, count: counts.get(tag) ?? 0 }));
  const listed = new Set(order);
  const rest = counted.filter((item) => !listed.has(item.tag)).sort((left, right) => left.tag.localeCompare(right.tag));
  return [...ordered, ...rest];
}

export function moveTag(order: readonly string[], tag: string, delta: number): string[] {
  const from = order.indexOf(tag);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= order.length) return [...order];
  const next = [...order];
  next.splice(from, 1);
  next.splice(to, 0, tag);
  return next;
}
