import type {
  UploadEvent,
  UploadJob,
  UploadStatus,
  Uploader,
} from '../../src/adapters/uploader/uploader';

export type FakeUploader = Uploader & {
  readonly jobs: UploadJob[];
  readonly emit: (event: UploadEvent) => void;
  readonly setStatus: (objectId: string, status: UploadStatus | null) => void;
  readonly cancelled: () => number;
};

export function createFakeUploader(): FakeUploader {
  const jobs: UploadJob[] = [];
  const listeners = new Set<(event: UploadEvent) => void>();
  const known = new Map<string, UploadStatus>();
  let cancelCount = 0;
  return {
    jobs,
    emit: (event) => {
      for (const listener of [...listeners]) listener(event);
    },
    setStatus: (objectId, status) => {
      if (status === null) known.delete(objectId);
      else known.set(objectId, status);
    },
    cancelled: () => cancelCount,
    enqueue: async (batch) => {
      for (const job of batch) {
        jobs.push(job);
        known.set(job.objectId, 'uploading');
      }
    },
    statuses: async () => new Map(known),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    cancelAll: async () => {
      cancelCount += 1;
      known.clear();
    },
  };
}
