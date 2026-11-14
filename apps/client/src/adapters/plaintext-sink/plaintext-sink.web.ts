import { forgetBlob, registerBlob } from './blob-registry.web';
import type { PlaintextSink, PlaintextSinkDeps } from './plaintext-sink';

export function createPlaintextSink(_deps: PlaintextSinkDeps): PlaintextSink {
  return {
    begin: async (mimeType) => {
      const parts: Uint8Array[] = [];
      return {
        write: async (bytes) => {
          parts.push(bytes);
        },
        finish: async () => {
          const blob = new Blob(parts as BlobPart[], { type: mimeType });
          const uri = URL.createObjectURL(blob);
          registerBlob(uri, blob);
          parts.length = 0;
          return {
            uri,
            release: async () => {
              forgetBlob(uri);
              URL.revokeObjectURL(uri);
            },
          };
        },
        abort: async () => {
          parts.length = 0;
        },
      };
    },
  };
}
