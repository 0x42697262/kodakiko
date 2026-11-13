export type PreparedVideo = { readonly uri: string; readonly release: () => Promise<void> };

export type VideoPrepareInput = {
  readonly entryId: string;
  readonly mediaId: string;
  readonly onProgress: (fraction: number) => void;
  readonly signal: AbortSignal;
};

export interface VideoSource {
  readonly prepare: (input: VideoPrepareInput) => Promise<PreparedVideo>;
  readonly releaseAll: () => Promise<void>;
}
