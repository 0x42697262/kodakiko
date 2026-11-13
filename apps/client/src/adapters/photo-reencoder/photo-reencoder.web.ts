import { fitWithin } from '@kodakiko/core';
import { LINK_PHOTO_MAX_EDGE, LINK_PHOTO_MIME, LINK_PHOTO_QUALITY, type PhotoReencoder } from '../../services/links/link-stripper';
import type { TempFiles } from '../temp-files/temp-files';

const CONTEXT_2D = '2d';
const FROM_IMAGE = 'from-image';

export function createPhotoReencoder(_deps: { readonly tempFiles: TempFiles }): PhotoReencoder {
  return async (bytes, mimeType) => {
    const bitmap = await createImageBitmap(new Blob([bytes.slice()], { type: mimeType }), { imageOrientation: FROM_IMAGE });
    try {
      const size = fitWithin(bitmap.width, bitmap.height, LINK_PHOTO_MAX_EDGE);
      const canvas = new OffscreenCanvas(size.width, size.height);
      const context = canvas.getContext(CONTEXT_2D);
      if (context === null) throw new Error(CONTEXT_2D);
      context.drawImage(bitmap, 0, 0, size.width, size.height);
      const blob = await canvas.convertToBlob({ type: LINK_PHOTO_MIME, quality: LINK_PHOTO_QUALITY });
      return { bytes: new Uint8Array(await blob.arrayBuffer()), width: size.width, height: size.height };
    } finally {
      bitmap.close();
    }
  };
}
