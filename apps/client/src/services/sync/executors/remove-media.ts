import { entryOutboxPayloadSchemas } from '@kodakiko/contract';
import { ApiError } from '../../../adapters/http/http';
import { HTTP_STATUS } from '../../../constants/http-constants';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor } from './executor-types';
import { guarded } from './guard';

const run: Executor = async (item, deps) => {
  const { mediaId } = entryOutboxPayloadSchemas.removeMedia.parse(item.payload);
  try {
    await deps.http.request(SYNC_CALLS.removeMedia(mediaId));
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== HTTP_STATUS.notFound) throw error;
  }
  return 'done';
};

export const removeMediaExecutor: Executor = guarded(run);
