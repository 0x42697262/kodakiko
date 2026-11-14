export type CropRect = { readonly x: number; readonly y: number; readonly width: number; readonly height: number };
export type Corner = 'topLeft' | 'topRight' | 'bottomLeft' | 'bottomRight';
export type PixelCrop = { readonly originX: number; readonly originY: number; readonly width: number; readonly height: number };

export const CROP_MIN_FRACTION = 0.1;
export const FULL_CROP: CropRect = Object.freeze({ x: 0, y: 0, width: 1, height: 1 });

const clamp = (value: number, low: number, high: number): number => Math.min(high, Math.max(low, value));

export function toPixels(rect: CropRect, imageWidth: number, imageHeight: number): PixelCrop {
  const originX = Math.round(rect.x * imageWidth);
  const originY = Math.round(rect.y * imageHeight);
  return {
    originX,
    originY,
    width: Math.min(imageWidth - originX, Math.round(rect.width * imageWidth)),
    height: Math.min(imageHeight - originY, Math.round(rect.height * imageHeight)),
  };
}

export function dragCorner(rect: CropRect, corner: Corner, pointerX: number, pointerY: number): CropRect {
  let left = rect.x;
  let top = rect.y;
  let right = rect.x + rect.width;
  let bottom = rect.y + rect.height;
  if (corner === 'topLeft' || corner === 'bottomLeft') left = clamp(pointerX, 0, right - CROP_MIN_FRACTION);
  else right = clamp(pointerX, left + CROP_MIN_FRACTION, 1);
  if (corner === 'topLeft' || corner === 'topRight') top = clamp(pointerY, 0, bottom - CROP_MIN_FRACTION);
  else bottom = clamp(pointerY, top + CROP_MIN_FRACTION, 1);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

export function moveCrop(rect: CropRect, deltaX: number, deltaY: number): CropRect {
  return { ...rect, x: clamp(rect.x + deltaX, 0, 1 - rect.width), y: clamp(rect.y + deltaY, 0, 1 - rect.height) };
}
