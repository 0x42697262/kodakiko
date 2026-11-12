import { describe, expect, it } from 'vitest';
import { decidePulledEntry } from '../src/index.ts';

describe('decidePulledEntry', () => {
  it('inserts an entry that is absent locally', () => {
    expect(decidePulledEntry(null, 1)).toBe('insert');
  });

  it('overwrites a synced entry when pulled >= base', () => {
    expect(decidePulledEntry({ localState: 'synced', baseRevision: 3 }, 3)).toBe('overwrite');
    expect(decidePulledEntry({ localState: 'synced', baseRevision: 3 }, 4)).toBe('overwrite');
  });

  it('keeps the pending change when pulled >= base', () => {
    expect(decidePulledEntry({ localState: 'pending_update', baseRevision: 3 }, 4)).toBe('updateTrashOnly');
    expect(decidePulledEntry({ localState: 'pending_update', baseRevision: 3 }, 3)).toBe('updateTrashOnly');
  });

  it('keeps a pending create when any revision is pulled', () => {
    expect(decidePulledEntry({ localState: 'pending_create', baseRevision: 0 }, 1)).toBe('updateTrashOnly');
  });

  it('rejects a rollback for every local state', () => {
    for (const localState of ['synced', 'pending_update', 'pending_create'] as const) {
      expect(decidePulledEntry({ localState, baseRevision: 5 }, 4)).toBe('rejectRollback');
    }
  });
});
