import { entryOutboxPayloadSchemas } from '@kodakiko/contract';
import { deleteFiles, removeEntryRows } from '../local-delete';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor } from './executor-types';
import { guarded } from './guard';

const run: Executor = async (item, deps) => {
  const { entryId } = entryOutboxPayloadSchemas.deleteEntry.parse(item.payload);
  await deps.http.request(SYNC_CALLS.deleteEntry(entryId));
  const objectIds = await deps.rowStore.transaction((tx) => removeEntryRows(tx, entryId));
  await deleteFiles(deps.fileStore, objectIds);
  deps.events.emit('entriesChanged', [entryId]);
  return 'done';
};

export const deleteEntryExecutor: Executor = guarded(run);
