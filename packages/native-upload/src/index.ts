import { requireNativeModule } from 'expo';

export type NativeUploadPart = {
  partNumber: number;
  url: string;
  offset: number;
  length: number;
};

export type NativeUploadJob = {
  objectId: string;
  filePath: string;
  parts: NativeUploadPart[];
};

export type NativeUploadStatus = 'queued' | 'uploading' | 'completed' | 'failed';

export type NativeUploadEvent =
  | { type: 'progress'; objectId: string; sentBytes: number }
  | { type: 'completed'; objectId: string }
  | { type: 'failed'; objectId: string; reason: 'expired' | 'network' | 'http' | 'missingFile' }
  | { type: 'stopped'; reason: 'timeout' | 'cancelled' };

type NativeUploadModule = {
  enqueue: (jobs: readonly NativeUploadJob[]) => Promise<void>;
  statuses: () => Promise<Record<string, NativeUploadStatus>>;
  cancelAll: () => Promise<void>;
  requestNotificationPermission: () => Promise<boolean>;
  addListener: (
    eventName: typeof UPLOAD_EVENT_NAME,
    listener: (event: NativeUploadEvent) => void,
  ) => { remove: () => void };
};

const MODULE_NAME = 'KodakikoNativeUpload';
const UPLOAD_EVENT_NAME = 'onUploadEvent';

const nativeModule = requireNativeModule<NativeUploadModule>(MODULE_NAME);

export function enqueue(jobs: readonly NativeUploadJob[]): Promise<void> {
  return nativeModule.enqueue(jobs);
}

export function statuses(): Promise<Record<string, NativeUploadStatus>> {
  return nativeModule.statuses();
}

export function cancelAll(): Promise<void> {
  return nativeModule.cancelAll();
}

export function addListener(listener: (event: NativeUploadEvent) => void): { remove(): void } {
  const subscription = nativeModule.addListener(UPLOAD_EVENT_NAME, listener);
  return { remove: () => subscription.remove() };
}

export function requestNotificationPermission(): Promise<boolean> {
  return nativeModule.requestNotificationPermission();
}
