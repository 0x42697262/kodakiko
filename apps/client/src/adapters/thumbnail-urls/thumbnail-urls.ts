export type ThumbnailUrls = {
  readonly create: (bytes: Uint8Array) => string;
  readonly revoke: (uri: string) => void;
};

export declare const thumbnailUrls: ThumbnailUrls;
