import { toPixels, type CropRect } from '../../features/entries/crop-math';
import { blobForUri } from '../plaintext-sink/blob-registry.web';
import type { MediaInput } from '../media/media-toolkit';

const CONTEXT_2D = '2d';
const FROM_IMAGE = 'from-image';
const CROP_MIME = 'image/jpeg';
const CROP_QUALITY = 0.92;

export async function cropImage(uri: string, rect: CropRect, fileName: string | null): Promise<MediaInput> {
  const source = await blobForUri(uri);
  const bitmap = await createImageBitmap(source, { imageOrientation: FROM_IMAGE });
  try {
    const crop = toPixels(rect, bitmap.width, bitmap.height);
    const canvas = new OffscreenCanvas(crop.width, crop.height);
    const context = canvas.getContext(CONTEXT_2D);
    if (context === null) throw new Error(CONTEXT_2D);
    context.drawImage(bitmap, crop.originX, crop.originY, crop.width, crop.height, 0, 0, crop.width, crop.height);
    const blob = await canvas.convertToBlob({ type: CROP_MIME, quality: CROP_QUALITY });
    return { origin: 'picker', handle: { blob }, fileName, pickerExif: null };
  } finally {
    bitmap.close();
  }
}
