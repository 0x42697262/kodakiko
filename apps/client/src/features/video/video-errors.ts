import { DecryptionError } from '@kodakiko/core';
import { StreamingUnavailableError } from '../../adapters/video-source/video-source-shared';
import { NotEnoughSpaceError } from '../../services/video/decrypting-video-source';
import { EN } from '../../text/en';

export type VideoProblem = { readonly message: string; readonly retry: boolean };

export function prepareProblem(error: unknown): VideoProblem {
  if (error instanceof NotEnoughSpaceError) return { message: EN['video.notEnoughSpace'], retry: false };
  if (error instanceof StreamingUnavailableError) return { message: EN['video.streamingUnavailable'], retry: false };
  if (error instanceof DecryptionError) return { message: EN['video.damaged'], retry: false };
  return { message: EN['video.downloadFailed'], retry: true };
}
