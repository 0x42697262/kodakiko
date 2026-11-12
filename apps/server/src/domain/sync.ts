export type SeqRow = { readonly seq: number };

export type DiffRowsPage<Row extends SeqRow> = {
  readonly rows: readonly Row[];
  readonly hasMore: boolean;
};

export function sortedForLocking(collectionIds: readonly string[]): readonly string[] {
  return [...new Set(collectionIds)].sort(
    (left, right) => Number(left > right) - Number(left < right),
  );
}

export function mergeDiffRows<Row extends SeqRow>(
  memberships: readonly Row[],
  tombstones: readonly Row[],
  limit: number,
): DiffRowsPage<Row> {
  const merged = [...memberships, ...tombstones].sort((left, right) => left.seq - right.seq);
  return { rows: merged.slice(0, limit), hasMore: merged.length > limit };
}

export function nextCursor(
  page: DiffRowsPage<SeqRow>,
  _since: number,
  collectionLastSeq: number,
): number {
  const last = page.rows[page.rows.length - 1];
  if (page.hasMore && last !== undefined) return last.seq;
  return collectionLastSeq;
}

export function needsResync(since: number, prunedThroughSeq: number): boolean {
  return since > 0 && since < prunedThroughSeq;
}
