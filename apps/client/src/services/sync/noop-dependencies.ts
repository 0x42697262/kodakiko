import type { ThumbnailCache, UploadPolicy } from './uploads';

export const alwaysAllowedUploadPolicy: UploadPolicy = { allowed: () => true };

export const noopThumbnailCache: ThumbnailCache = { afterUpload: () => undefined };
