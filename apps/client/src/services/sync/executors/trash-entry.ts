import { entryOutboxPayloadSchemas } from '@kodakiko/contract';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor } from './executor-types';
import { guarded } from './guard';

const run: Executor = async (item, deps) => {
  const { entryId } = entryOutboxPayloadSchemas.trashEntry.parse(item.payload);
  await deps.http.request(SYNC_CALLS.trashEntry(entryId));
  return 'done';
};

export const trashEntryExecutor: Executor = guarded(run);
