import { encodeBytes, entryOutboxPayloadSchemas, mediaCreateRequest } from '@kodakiko/contract';
import { SYNC_CALLS } from '../sync-calls';
import type { Executor } from './executor-types';
import { guarded } from './guard';
import { advanceUploads, mediaIsPosted } from './upload-step';

const run: Executor = async (item, deps) => {
  const { entryId, mediaId } = entryOutboxPayloadSchemas.addMedia.parse(item.payload);
  const media = await deps.rowStore.transaction((tx) => tx.media.get(mediaId));
  if (media === null) return 'done';
  if (!(await mediaIsPosted(deps, mediaId))) {
    await deps.http.request(
      SYNC_CALLS.addMedia(
        entryId,
        mediaCreateRequest.parse({ mediaId, mediaKeyByEntry: encodeBytes(media.mediaKeyWrap) }),
      ),
    );
  }
  const uploaded = await advanceUploads(deps, [mediaId]);
  if (uploaded) deps.events.emit('entriesChanged', [entryId]);
  return uploaded ? 'done' : 'waiting';
};

export const addMediaExecutor: Executor = guarded(run);
