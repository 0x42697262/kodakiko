import type { OutboxKind } from '@kodakiko/contract';
import { addMediaExecutor } from './add-media';
import { createEntryExecutor } from './create-entry';
import { deleteEntryExecutor } from './delete-entry';
import type { Executor } from './executor-types';
import { removeMediaExecutor } from './remove-media';
import { restoreEntryExecutor } from './restore-entry';
import { trashEntryExecutor } from './trash-entry';
import { updateEntryMetaExecutor } from './update-entry-meta';

export const EXECUTORS: Readonly<Record<OutboxKind, Executor>> = Object.freeze({
  createEntry: createEntryExecutor,
  updateEntryMeta: updateEntryMetaExecutor,
  addMedia: addMediaExecutor,
  removeMedia: removeMediaExecutor,
  trashEntry: trashEntryExecutor,
  restoreEntry: restoreEntryExecutor,
  deleteEntry: deleteEntryExecutor,
});
