export interface ArchiveFile {
  readonly push: (bytes: Uint8Array, final: boolean) => Promise<void>;
}

export interface ArchiveWriter {
  readonly addFile: (name: string, options: { readonly compress: boolean; readonly mtime: Date }) => ArchiveFile;
  readonly finish: () => Promise<void>;
}

export type PartSink = {
  readonly write: (bytes: Uint8Array) => Promise<void>;
  readonly close: () => Promise<void>;
  readonly abort: () => Promise<void>;
};
