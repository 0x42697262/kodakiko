import { blobForUri } from '../plaintext-sink/blob-registry.web';
import type { SharedFile, ShareResult } from './share-files';

const ABORT_ERROR = 'AbortError';

function probeFile(): File {
  return new File([new Uint8Array()], 'probe.jpg', { type: 'image/jpeg' });
}

export function canShareFiles(): boolean {
  return typeof navigator.canShare === 'function' && navigator.canShare({ files: [probeFile()] });
}

export async function shareFiles(files: readonly SharedFile[], text: string | null): Promise<ShareResult> {
  if (!canShareFiles()) return 'unsupported';
  const prepared = await Promise.all(
    files.map(async (file) => new File([await blobForUri(file.uri)], file.fileName, { type: file.mimeType })),
  );
  try {
    await navigator.share({ files: prepared, ...(text === null ? {} : { text }) });
    return 'shared';
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === ABORT_ERROR) return 'cancelled';
    throw caught;
  }
}

export async function saveToDevice(file: SharedFile): Promise<ShareResult> {
  const anchor = document.createElement('a');
  anchor.href = file.uri;
  anchor.download = file.fileName;
  anchor.rel = 'noopener';
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  return 'shared';
}
