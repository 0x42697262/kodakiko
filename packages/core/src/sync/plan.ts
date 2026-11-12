import type { OutboxKind } from '@kodakiko/contract';

export type OutboxItemView = {
  readonly id: number;
  readonly kind: OutboxKind;
  readonly entryId: string | null;
  readonly state: 'queued' | 'dead';
  readonly nextAttemptAt: number;
};

export function planPushBatch(
  items: readonly OutboxItemView[],
  inFlightEntryIds: ReadonlySet<string>,
  now: number,
  capacity: number,
): readonly number[] {
  const ordered = [...items].sort((left, right) => left.id - right.id);
  const seenEntryIds = new Set<string>();
  const selected: number[] = [];
  for (const item of ordered) {
    const blocked =
      item.entryId !== null && (seenEntryIds.has(item.entryId) || inFlightEntryIds.has(item.entryId));
    const due = item.state === 'queued' && item.nextAttemptAt <= now;
    if (!blocked && due && selected.length < capacity) selected.push(item.id);
    if (item.entryId !== null) seenEntryIds.add(item.entryId);
  }
  return selected;
}
