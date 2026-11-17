import { describe, expect, it } from 'vitest';
import { DOWNLOAD_MAX_BYTES, MEMORY_PLAYBACK_MAX_BYTES } from '../src/constants.ts';
import { canDownload, downloadName, playbackMode } from '../src/download.ts';

type Kind = 'photo' | 'video';

function descriptor(kind: Kind, mimeType: string, originalPlaintextBytes = 1000) {
  return { kind, mimeType, originalPlaintextBytes };
}

describe('downloadName', () => {
  it('names files kodakiko-<position in the viewer, from 1>.<extension>', () => {
    expect(downloadName(0, descriptor('photo', 'image/jpeg'))).toBe('kodakiko-1.jpg');
    expect(downloadName(9, descriptor('photo', 'image/jpeg'))).toBe('kodakiko-10.jpg');
  });

  it('saves every linked photo as .jpg (link copies are re-encoded to JPEG)', () => {
    expect(downloadName(1, descriptor('photo', 'image/png'))).toBe('kodakiko-2.jpg');
  });

  it('uses the usual extension of the video type', () => {
    expect(downloadName(1, descriptor('video', 'video/mp4'))).toBe('kodakiko-2.mp4');
    expect(downloadName(2, descriptor('video', 'video/quicktime'))).toBe('kodakiko-3.mov');
    expect(downloadName(3, descriptor('video', 'video/webm'))).toBe('kodakiko-4.webm');
  });

  it('falls back to .bin for an unknown or malformed video type', () => {
    expect(downloadName(0, descriptor('video', 'video/x-unknown'))).toBe('kodakiko-1.bin');
    expect(downloadName(0, descriptor('video', 'nonsense'))).toBe('kodakiko-1.bin');
    expect(downloadName(0, descriptor('video', 'video/../../etc'))).toBe('kodakiko-1.bin');
  });
});

describe('canDownload', () => {
  it('allows photos and videos up to 200 MiB', () => {
    expect(DOWNLOAD_MAX_BYTES).toBe(209_715_200);
    expect(canDownload(descriptor('photo', 'image/jpeg', 5_000_000))).toBe(true);
    expect(canDownload(descriptor('video', 'video/mp4', DOWNLOAD_MAX_BYTES))).toBe(true);
  });

  it('refuses a video above the limit (it would have to be decrypted in memory)', () => {
    expect(canDownload(descriptor('video', 'video/mp4', DOWNLOAD_MAX_BYTES + 1))).toBe(false);
  });
});

describe('playbackMode (twin of the v0.3 web viewer video pane)', () => {
  it('streams through the media service worker when it is available, whatever the size', () => {
    expect(playbackMode(true, 10)).toBe('stream');
    expect(playbackMode(true, MEMORY_PLAYBACK_MAX_BYTES * 4)).toBe('stream');
  });

  it('falls back to decrypting in memory up to 200 MiB without the service worker', () => {
    expect(MEMORY_PLAYBACK_MAX_BYTES).toBe(209_715_200);
    expect(playbackMode(false, MEMORY_PLAYBACK_MAX_BYTES)).toBe('memory');
  });

  it('reports a larger video as unsupported without the service worker', () => {
    expect(playbackMode(false, MEMORY_PLAYBACK_MAX_BYTES + 1)).toBe('unsupported');
  });
});
