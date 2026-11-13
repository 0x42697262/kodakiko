import { MIME_EXTENSIONS, VIEW_FALLBACK_EXTENSION } from '../../keys/mime-extensions';
import type { PlaintextSink, PlaintextSinkDeps } from './plaintext-sink';

export function createPlaintextSink(deps: PlaintextSinkDeps): PlaintextSink {
  return {
    begin: async (mimeType) => {
      const file = await deps.tempFiles.create('view', MIME_EXTENSIONS[mimeType] ?? VIEW_FALLBACK_EXTENSION);
      return {
        write: (bytes) => file.write(bytes),
        finish: async () => {
          await file.close();
          return { uri: file.uri, release: () => file.delete() };
        },
        abort: async () => {
          await file.close();
          await file.delete();
        },
      };
    },
  };
}
