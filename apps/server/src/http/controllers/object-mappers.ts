import type { DownloadResponse, ObjectDto, PartsResponse, PresignResponse } from '@kodakiko/contract';
import type {
  DownloadView,
  ObjectView,
  PartsView,
  PresignView,
} from '../../services/objects-service.ts';

export function toPresignResponse(view: PresignView): PresignResponse {
  const expiresAt = view.expiresAt.toISOString();
  if (view.mode === 'single') return { mode: 'single', url: view.url, expiresAt };
  return {
    mode: 'multipart',
    partSizeBytes: view.partSizeBytes,
    partUrls: view.partUrls.map(({ partNumber, url }) => ({ partNumber, url })),
    expiresAt,
  };
}

export function toPartsResponse(view: PartsView): PartsResponse {
  return {
    partSizeBytes: view.partSizeBytes,
    uploadedParts: view.uploadedParts.map(({ partNumber, sizeBytes }) => ({ partNumber, sizeBytes })),
    partUrls: view.partUrls.map(({ partNumber, url }) => ({ partNumber, url })),
    expiresAt: view.expiresAt.toISOString(),
  };
}

export function toObjectDto(view: ObjectView): ObjectDto {
  return {
    id: view.id,
    mediaId: view.mediaId,
    variant: view.variant,
    sizeBytes: view.sizeBytes,
    state: view.state,
  } as ObjectDto;
}

export function toDownloadResponse(view: DownloadView): DownloadResponse {
  return { url: view.url, expiresAt: view.expiresAt.toISOString() };
}
