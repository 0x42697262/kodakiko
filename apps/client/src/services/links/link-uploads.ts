import type { FileStore } from '../../adapters/filestore/filestore';
import type { HttpClient } from '../../adapters/http/http';
import type { Uploader } from '../../adapters/uploader/uploader';
import { SYNC_CALLS } from '../sync/sync-calls';
import { partsFromPresign } from '../sync/uploads';

export type LinkObject = {
  readonly id: string;
  readonly mediaId: string;
  readonly variant: 'original' | 'thumbnail';
  readonly sizeBytes: number;
};

export class LinkUploadFailedError extends Error {}

export async function uploadAll(
  deps: { readonly http: HttpClient; readonly uploader: Uploader; readonly fileStore: FileStore },
  objects: readonly LinkObject[],
  onBytes: (sent: number) => void,
  signal: AbortSignal,
): Promise<void> {
  const pending = new Set(objects.map((object) => object.id));
  const sentByObject = new Map<string, number>();
  const finished = new Promise<void>((resolve, reject) => {
    const stop = deps.uploader.subscribe((event) => {
      if (event.type === 'progress' && pending.has(event.objectId)) {
        sentByObject.set(event.objectId, event.sentBytes);
        onBytes([...sentByObject.values()].reduce((sum, value) => sum + value, 0));
      } else if (event.type === 'completed' && pending.has(event.objectId)) {
        pending.delete(event.objectId);
        if (pending.size === 0) {
          stop();
          resolve();
        }
      } else if (event.type === 'failed' && pending.has(event.objectId)) {
        stop();
        reject(new LinkUploadFailedError(event.reason));
      }
    });
    signal.addEventListener('abort', () => {
      stop();
      reject(signal.reason instanceof Error ? signal.reason : new LinkUploadFailedError('aborted'));
    });
  });
  const jobs = [];
  for (const object of objects) {
    const presigned = await deps.http.request(
      SYNC_CALLS.presign({ id: object.id, mediaId: object.mediaId, variant: object.variant, sizeBytes: object.sizeBytes } as Parameters<
        typeof SYNC_CALLS.presign
      >[0]),
    );
    jobs.push({ objectId: object.id, parts: partsFromPresign(presigned, object.sizeBytes) });
  }
  await deps.uploader.enqueue(jobs);
  await finished;
  for (const object of objects) {
    await deps.http.request(SYNC_CALLS.confirm(object.id));
    sentByObject.set(object.id, object.sizeBytes);
  }
  onBytes([...sentByObject.values()].reduce((sum, value) => sum + value, 0));
  for (const object of objects) await deps.fileStore.delete(object.id);
}
