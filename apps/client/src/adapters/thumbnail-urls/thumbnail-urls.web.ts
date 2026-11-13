import { THUMBNAIL_MIME_TYPE } from '../../constants/gallery-constants';
import type { ThumbnailUrls } from './thumbnail-urls';

export const thumbnailUrls: ThumbnailUrls = Object.freeze({
  create: (bytes: Uint8Array) =>
    URL.createObjectURL(new Blob([bytes.slice()], { type: THUMBNAIL_MIME_TYPE })),
  revoke: (uri: string) => URL.revokeObjectURL(uri),
});
