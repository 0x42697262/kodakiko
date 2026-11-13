export type RegisterMessage = {
  readonly type: 'register';
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
  | { readonly type: 'refreshUrl'; readonly playbackId: string; readonly downloadUrl: string }
  | { readonly type: 'forget'; readonly playbackId: string }
  | { readonly type: 'forgetAll' }
  | { readonly type: 'ping'; readonly nonce: string };

export type WorkerToPageMessage =
  | { readonly type: 'pong'; readonly nonce: string }
  | { readonly type: 'needKey'; readonly playbackId: string }
  | { readonly type: 'needUrl'; readonly playbackId: string };
