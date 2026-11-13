import { EN } from '../../text/en';
import type { TempFiles, TempFilesDeps } from './temp-files';
import { TempFilesUnsupportedError } from './temp-files-errors';

export function createTempFiles(_deps: TempFilesDeps): TempFiles {
  return {
    create: async () => {
      throw new TempFilesUnsupportedError(EN['tempFiles.unsupported']);
    },
    wipe: async () => undefined,
    list: async () => [],
  };
}
