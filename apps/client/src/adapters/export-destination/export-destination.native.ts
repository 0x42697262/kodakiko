import { EXPORT_PART_MAX_BYTES } from '@kodakiko/core';
import { Directory, FileMode } from 'expo-file-system';
import type { DestinationChoice } from './export-destination';

const ZIP_MIME = 'application/zip';

export async function pickExportDestination(): Promise<DestinationChoice> {
  let directory: Directory;
  try {
    directory = await Directory.pickDirectoryAsync();
  } catch {
    return { kind: 'cancelled' };
  }
  return {
    kind: 'chosen',
    downloads: false,
    destination: {
      partMaxBytes: EXPORT_PART_MAX_BYTES,
      createPart: async (name) => {
        const file = directory.createFile(name, ZIP_MIME);
        const handle = file.open(FileMode.WriteOnly);
        return {
          write: (bytes) => handle.writeBytes(bytes),
          close: async () => handle.close(),
          abort: async () => {
            handle.close();
            if (file.exists) file.delete();
          },
        };
      },
    },
  };
}
