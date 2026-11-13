import type { MediaInput } from '../../adapters/media/media-toolkit';
import { UNDECODABLE_REFUSAL_KEY } from '../../constants/capture-platform';
import { VIDEO_FILE_EXTENSIONS, VIDEO_MIME_PREFIX } from '../../constants/capture-constants';
import type { ImportRefusal } from '../../services/media/import-service';
import { EN } from '../../text/en';

function isVideoInput(input: MediaInput): boolean {
  if ('blob' in input.handle) return input.handle.blob.type.startsWith(VIDEO_MIME_PREFIX);
  const uri = input.handle.uri.toLowerCase();
  return VIDEO_FILE_EXTENSIONS.some((extension) => uri.endsWith(extension));
}

export function inputLabel(input: MediaInput): string {
  if (input.fileName !== null) return input.fileName;
  return isVideoInput(input) ? EN['capture.thisVideo'] : EN['capture.thisPhoto'];
}

export function refusalMessage(
  reason: ImportRefusal,
  label: string,
  maxMediaPerEntry: number,
): string {
  switch (reason) {
    case 'unsupported':
      return EN['capture.refusal.unsupported'](label);
    case 'undecodable':
      return EN[UNDECODABLE_REFUSAL_KEY](label);
    case 'tooLarge':
      return EN['capture.refusal.tooLarge'](label);
    case 'trayFull':
      return EN['capture.refusal.trayFull'](maxMediaPerEntry);
    case 'readFailed':
      return EN['capture.refusal.readFailed'](label);
  }
}
