import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { toPixels, type CropRect } from '../../features/entries/crop-math';
import type { MediaInput } from '../media/media-toolkit';

const CROP_QUALITY = 0.92;

export async function cropImage(uri: string, rect: CropRect, fileName: string | null): Promise<MediaInput> {
  const probe = await ImageManipulator.manipulate(uri).renderAsync();
  const crop = toPixels(rect, probe.width, probe.height);
  const rendered = await ImageManipulator.manipulate(uri).crop(crop).renderAsync();
  const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: CROP_QUALITY });
  return { origin: 'picker', handle: { uri: saved.uri }, fileName, pickerExif: null };
}
