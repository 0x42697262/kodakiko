import { EXPORT_PART_MAX_BYTES, EXPORT_WEB_MEMORY_PART_MAX_BYTES } from '@kodakiko/core';
import type { DestinationChoice } from './export-destination';

type DirectoryPicker = (options: { readonly mode: 'readwrite' }) => Promise<FileSystemDirectoryHandle>;
const ZIP_MIME = 'application/zip';
const ABORT_ERROR = 'AbortError';

function pickerOf(): DirectoryPicker | null {
  const picker = (window as unknown as { showDirectoryPicker?: DirectoryPicker }).showDirectoryPicker;
  return typeof picker === 'function' ? picker.bind(window) : null;
}

function downloadFallback(): DestinationChoice {
  return {
    kind: 'chosen',
    downloads: true,
    destination: {
      partMaxBytes: EXPORT_WEB_MEMORY_PART_MAX_BYTES,
      createPart: async (name) => {
        const parts: Uint8Array<ArrayBuffer>[] = [];
        return {
          write: async (bytes) => {
            parts.push(bytes.slice());
          },
          close: async () => {
            const url = URL.createObjectURL(new Blob(parts, { type: ZIP_MIME }));
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = name;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            setTimeout(() => URL.revokeObjectURL(url), 0);
            parts.length = 0;
          },
          abort: async () => {
            parts.length = 0;
          },
        };
      },
    },
  };
}

export async function pickExportDestination(): Promise<DestinationChoice> {
  const picker = pickerOf();
  if (picker === null) return downloadFallback();
  let directory: FileSystemDirectoryHandle;
  try {
    directory = await picker({ mode: 'readwrite' });
  } catch (error) {
    if (error instanceof DOMException && error.name === ABORT_ERROR) return { kind: 'cancelled' };
    return downloadFallback();
  }
  return {
    kind: 'chosen',
    downloads: false,
    destination: {
      partMaxBytes: EXPORT_PART_MAX_BYTES,
      createPart: async (name) => {
        const handle = await directory.getFileHandle(name, { create: true });
        const writable = await handle.createWritable();
        return {
          write: async (bytes) => {
            await writable.write(bytes.slice());
          },
          close: () => writable.close(),
          abort: async () => {
            await writable.abort();
            await directory.removeEntry(name).catch(() => undefined);
          },
        };
      },
    },
  };
}
