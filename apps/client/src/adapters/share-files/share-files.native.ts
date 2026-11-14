import Share from 'react-native-share';
import type { SharedFile, ShareResult } from './share-files';

export function canShareFiles(): boolean {
  return true;
}

export async function shareFiles(files: readonly SharedFile[], text: string | null): Promise<ShareResult> {
  const result = await Share.open({
    urls: files.map((file) => file.uri),
    filenames: files.map((file) => file.fileName),
    ...(files.length === 1 && files[0] !== undefined ? { type: files[0].mimeType } : {}),
    ...(text === null ? {} : { message: text }),
    failOnCancel: false,
  });
  return result.success ? 'shared' : 'cancelled';
}

export function saveToDevice(file: SharedFile): Promise<ShareResult> {
  return shareFiles([file], null);
}
