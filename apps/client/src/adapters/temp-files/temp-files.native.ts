import { Directory, File, FileMode, Paths } from 'expo-file-system';
import { createRandomId } from '../ids';
import { TEMP_FILE_SEPARATOR, tempFileName, tmpDirectory } from '../../keys/paths';
import { EN } from '../../text/en';
import { hashAccountKey } from '../account-key';
import type { TempFile, TempFiles, TempFilesDeps } from './temp-files';

function listRecursive(directory: Directory): readonly string[] {
  const uris: string[] = [];
  for (const entry of directory.list()) {
    uris.push(entry.uri);
    if (entry instanceof Directory) uris.push(...listRecursive(entry));
  }
  return uris;
}

function deleteMatching(directory: Directory, prefix: string): void {
  for (const entry of directory.list()) {
    if (entry instanceof Directory) deleteMatching(entry, prefix);
    else if (entry.name.startsWith(prefix)) {
      try {
        entry.delete();
      } catch {
        continue;
      }
    }
  }
}

export function createTempFiles(deps: TempFilesDeps): TempFiles {
  const randomId = createRandomId(deps.sodium);

  function activeDirectory(): Directory | null {
    const accountKey = deps.activeAccountKey();
    if (accountKey === null) return null;
    return new Directory(Paths.cache, tmpDirectory(hashAccountKey(deps.sodium, accountKey)));
  }

  return {
    create: async (purpose, extension) => {
      const directory = activeDirectory();
      if (directory === null) throw new Error(EN['tempFiles.noAccount']);
      directory.create({ intermediates: true, idempotent: true });
      const file = new File(directory, tempFileName(purpose, randomId(), extension));
      file.create({ overwrite: true });
      const handle = file.open(FileMode.WriteOnly);
      let closed = false;
      const close = async (): Promise<void> => {
        if (closed) return;
        closed = true;
        handle.close();
      };
      const tempFile: TempFile = {
        uri: file.uri,
        write: async (bytes) => {
          await handle.writeBytes(bytes);
        },
        close,
        delete: async () => {
          await close();
          if (file.exists) file.delete();
        },
      };
      return tempFile;
    },
    wipe: async () => {
      for (const entry of new Directory(Paths.cache).list()) {
        try {
          entry.delete();
        } catch {
          continue;
        }
      }
    },
    wipePurpose: async (purpose) => {
      const prefix = `${purpose}${TEMP_FILE_SEPARATOR}`;
      deleteMatching(new Directory(Paths.cache), prefix);
    },
    list: async () => listRecursive(new Directory(Paths.cache)),
  };
}
