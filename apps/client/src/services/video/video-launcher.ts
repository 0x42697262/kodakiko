export type VideoLauncher = {
  readonly canPlay: () => boolean;
  readonly open: (entryId: string, mediaId: string) => void;
};

export const placeholderVideoLauncher: VideoLauncher = Object.freeze({
  canPlay: () => false,
  open: () => undefined,
});
