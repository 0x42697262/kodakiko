import * as NativeUpload from '@kodakiko/native-upload';
import type { UploadEvent, UploadJob, UploadStatus, Uploader, UploaderDeps } from './uploader';

export function createUploader(deps: UploaderDeps): Uploader {
  const listeners = new Set<(event: UploadEvent) => void>();
  let nativeSubscription: { remove(): void } | null = null;

  function emit(event: UploadEvent): void {
    for (const listener of [...listeners]) listener(event);
  }

  function ensureNativeSubscription(): void {
    nativeSubscription ??= NativeUpload.addListener(emit);
  }

  return {
    enqueue: async (jobs: readonly UploadJob[]) => {
      ensureNativeSubscription();
      const nativeJobs: NativeUpload.NativeUploadJob[] = [];
      for (const job of jobs) {
        const filePath = deps.fileStore.nativeUri(job.objectId);
        if (filePath === null) {
          emit({ type: 'failed', objectId: job.objectId, reason: 'missingFile' });
        } else {
          nativeJobs.push({ objectId: job.objectId, filePath, parts: job.parts.map((part) => ({ ...part })) });
        }
      }
      if (nativeJobs.length > 0) await NativeUpload.enqueue(nativeJobs);
    },
    statuses: async () =>
      new Map<string, UploadStatus>(Object.entries(await NativeUpload.statuses())),
    subscribe: (listener) => {
      ensureNativeSubscription();
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    cancelAll: () => NativeUpload.cancelAll(),
  };
}
