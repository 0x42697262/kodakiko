import { getImageAsync, hasImageAsync } from 'expo-clipboard';
import type { MediaInput } from '../media/media-toolkit';
import type { TempFiles } from '../temp-files/temp-files';

const DATA_PREFIX_END = ',';
const PASTE_EXTENSION = 'jpg';

export const clipboardButtonSupported = true;

export function hasClipboardImage(): Promise<boolean> {
  return hasImageAsync();
}

function decodeBase64(text: string): Uint8Array {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export async function readClipboardImage(tempFiles: TempFiles): Promise<MediaInput | null> {
  const image = await getImageAsync({ format: 'jpeg' });
  if (image === null) return null;
  const comma = image.data.indexOf(DATA_PREFIX_END);
  const bytes = decodeBase64(comma >= 0 ? image.data.slice(comma + 1) : image.data);
  const file = await tempFiles.create('import', PASTE_EXTENSION);
  await file.write(bytes);
  await file.close();
  return { origin: 'paste', handle: { uri: file.uri }, fileName: null, pickerExif: null };
}

export function subscribePastedFiles(_onInputs: (inputs: readonly MediaInput[]) => void): () => void {
  return () => undefined;
}
