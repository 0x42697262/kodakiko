import { thumbnailDataUri } from '../../features/capture/thumbnail-uri';
import type { ThumbnailUrls } from './thumbnail-urls';

export const thumbnailUrls: ThumbnailUrls = Object.freeze({
  create: (bytes: Uint8Array) => thumbnailDataUri(bytes),
  revoke: () => undefined,
});
