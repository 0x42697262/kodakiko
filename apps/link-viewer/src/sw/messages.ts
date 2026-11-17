export const MEDIA_MESSAGE = Object.freeze({
  register: 'register',
  refreshUrl: 'refreshUrl',
  forget: 'forget',
  forgetAll: 'forgetAll',
  ping: 'ping',
  pong: 'pong',
  needKey: 'needKey',
  needUrl: 'needUrl',
} as const);

export type RegisterMessage = {
  readonly type: typeof MEDIA_MESSAGE.register;
  readonly playbackId: string;
  readonly key: ArrayBuffer;
  readonly objectId: string;
  readonly downloadUrl: string;
  readonly extraHeaders: Readonly<Record<string, string | null>>;
  readonly plaintextSize: number;
  readonly ciphertextSize: number;
  readonly mimeType: string;
};

export type PageToWorkerMessage =
  | RegisterMessage
  | {
      readonly type: typeof MEDIA_MESSAGE.refreshUrl;
      readonly playbackId: string;
      readonly downloadUrl: string;
    }
  | { readonly type: typeof MEDIA_MESSAGE.forget; readonly playbackId: string }
  | { readonly type: typeof MEDIA_MESSAGE.forgetAll }
  | { readonly type: typeof MEDIA_MESSAGE.ping; readonly nonce: string };

export type WorkerToPageMessage =
  | { readonly type: typeof MEDIA_MESSAGE.pong; readonly nonce: string }
  | { readonly type: typeof MEDIA_MESSAGE.needKey; readonly playbackId: string }
  | { readonly type: typeof MEDIA_MESSAGE.needUrl; readonly playbackId: string };
