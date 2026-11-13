export type PlaintextRef = { readonly uri: string; readonly release: () => Promise<void> };

export type PlaintextWriter = {
  readonly write: (bytes: Uint8Array) => Promise<void>;
  readonly finish: () => Promise<PlaintextRef>;
  readonly abort: () => Promise<void>;
};

export type PlaintextSink = { readonly begin: (mimeType: string) => Promise<PlaintextWriter> };

export type PlaintextSinkDeps = { readonly tempFiles: import('../temp-files/temp-files').TempFiles };

export declare function createPlaintextSink(deps: PlaintextSinkDeps): PlaintextSink;
