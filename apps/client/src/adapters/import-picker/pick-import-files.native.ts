import { File } from 'expo-file-system';
import type { ImportFile } from '../../services/import/journal-import';

export async function pickImportFiles(): Promise<readonly ImportFile[] | null> {
  const picked = await File.pickFileAsync({ multipleFiles: true });
  if (picked.result === null) return null;
  return Promise.all(picked.result.map(async (file) => ({ name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) })));
}
