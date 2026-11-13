import { describe, expect, it } from 'vitest';
import { EN } from '../src/text/en';

describe('capture refusal messages', () => {
  it('names an unsupported file', () => {
    expect(EN['capture.refusal.unsupported']('notes.pdf')).toBe(
      "notes.pdf isn't a supported photo or video.",
    );
  });

  it('sends a browser that cannot read a file to the Android app', () => {
    expect(EN['capture.refusal.undecodableWeb']('IMG_0001.heic')).toBe(
      "This browser can't read IMG_0001.heic. Add it from the Android app instead.",
    );
  });

  it('says a file could not be opened on Android', () => {
    expect(EN['capture.refusal.undecodableNative']('IMG_0001.heic')).toBe(
      "IMG_0001.heic couldn't be opened.",
    );
  });

  it('says a file is larger than the server allows', () => {
    expect(EN['capture.refusal.tooLarge']('clip.mp4')).toBe(
      'clip.mp4 is larger than this server allows.',
    );
  });

  it('says how many items an entry can hold', () => {
    expect(EN['capture.refusal.trayFull'](20)).toBe(
      'An entry can hold up to 20 photos and videos.',
    );
  });

  it('asks to try again after a read failure', () => {
    expect(EN['capture.refusal.readFailed']('clip.mp4')).toBe(
      "clip.mp4 couldn't be added. Try again.",
    );
  });

  it('names camera captures as this photo and this video', () => {
    expect(EN['capture.thisPhoto']).toBe('This photo');
    expect(EN['capture.thisVideo']).toBe('This video');
  });
});
