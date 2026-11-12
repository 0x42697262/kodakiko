import type { ObjectRow } from '../../../adapters/rowstore/rowstore';
import type { ExecutorDeps } from './executor-types';

const NEEDS_ENQUEUE: readonly ObjectRow['uploadState'][] = ['local', 'queued'];

async function objectsOf(deps: ExecutorDeps, mediaIds: readonly string[]): Promise<ObjectRow[]> {
  return deps.rowStore.transaction(async (tx) => {
    const objects: ObjectRow[] = [];
    for (const mediaId of mediaIds) objects.push(...(await tx.objects.byMedia(mediaId)));
    return objects;
  });
}

export async function mediaIsPosted(deps: ExecutorDeps, mediaId: string): Promise<boolean> {
  const objects = await objectsOf(deps, [mediaId]);
  return objects.some((object) => object.uploadState !== 'local');
}

export async function advanceUploads(
  deps: ExecutorDeps,
  mediaIds: readonly string[],
): Promise<boolean> {
  const before = await objectsOf(deps, mediaIds);
  const pending = before.filter((object) => NEEDS_ENQUEUE.includes(object.uploadState));
  if (pending.length > 0) {
    await deps.uploads.enqueueObjectUploads(pending.map((object) => object.id));
  }
  const after = await objectsOf(deps, mediaIds);
  return after.every((object) => object.uploadState === 'uploaded');
}
