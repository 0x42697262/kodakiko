export type EntryLocalState = 'synced' | 'pending_create' | 'pending_update';

export type LocalEntryState = {
  readonly localState: EntryLocalState;
  readonly baseRevision: number;
} | null;

export type PulledEntryDecision = 'insert' | 'overwrite' | 'updateTrashOnly' | 'rejectRollback';

export function decidePulledEntry(
  local: LocalEntryState,
  pulledRevision: number,
): PulledEntryDecision {
  if (local === null) return 'insert';
  if (pulledRevision < local.baseRevision) return 'rejectRollback';
  return local.localState === 'synced' ? 'overwrite' : 'updateTrashOnly';
}
