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
          const uri = URL.createObjectURL(new Blob(parts as BlobPart[], { type: mimeType }));
          parts.length = 0;
          return {
            uri,
            release: async () => URL.revokeObjectURL(uri),
          };
        },
        abort: async () => {
          parts.length = 0;
        },
      };
    },
  };
}
