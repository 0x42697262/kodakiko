import { describe, expect, it } from 'vitest';
import { ANDROID_RELEASES_URL, LINK_VIEWER_IDS } from '../src/keys.ts';
import { TEXT } from '../src/text.ts';

describe('TEXT', () => {
  it('keeps the strings of the v0.3 web viewer', () => {
    expect(TEXT.footer).toBe('Shared with Kodakiko. End-to-end encrypted: the server never saw these photos.');
    expect(TEXT.loading).toBe('Opening\u2026');
    expect(TEXT.download).toBe('Download');
    expect(TEXT.back).toBe('Back');
    expect(TEXT.next).toBe('Next');
    expect(TEXT.previous).toBe('Previous');
    expect(TEXT.play).toBe('Play');
    expect(TEXT.videoUnsupported).toBe("This browser can't play this video. Try Chrome.");
  });

  it('labels the position in the full-screen viewer', () => {
    expect(TEXT.counter(1, 3)).toBe('1 of 3');
    expect(TEXT.counter(12, 40)).toBe('12 of 40');
  });

  it('explains why Download is unavailable for a video above the limit', () => {
    expect(TEXT.downloadTooLarge).toBe('This video is too large to download here.');
  });

  it('has the Get the Android app page', () => {
    expect(TEXT.getAppTitle).toBe('Kodakiko');
    expect(TEXT.getAppHeading).toBe('Get the Android app');
    expect(TEXT.getAppBody).toBe(
      'Kodakiko is an end-to-end encrypted photo and video journal. Install the Android app to start your own.',
    );
    expect(TEXT.getAppDownload).toBe('Download for Android');
    expect(ANDROID_RELEASES_URL).toBe('https://github.com/0x42697262/kodakiko/releases');
  });

  it('is frozen and has no empty or padded strings', () => {
    expect(Object.isFrozen(TEXT)).toBe(true);
    const all = (Object.values(TEXT) as unknown[]).filter((value): value is string => typeof value === 'string');
    for (const value of all) {
      expect(value.trim()).toBe(value);
      expect(value.length).toBeGreaterThan(0);
    }
  });
});

describe('LINK_VIEWER_IDS', () => {
  it('keeps the test ids of the v0.3 web viewer so the U-18 flows still find them', () => {
    expect(LINK_VIEWER_IDS.screen).toBe('linkViewer.screen');
    expect(LINK_VIEWER_IDS.title).toBe('linkViewer.title');
    expect(LINK_VIEWER_IDS.message).toBe('linkViewer.message');
    expect(LINK_VIEWER_IDS.detail).toBe('linkViewer.detail');
    expect(LINK_VIEWER_IDS.download).toBe('linkViewer.download');
    expect(LINK_VIEWER_IDS.next).toBe('linkViewer.next');
    expect(LINK_VIEWER_IDS.previous).toBe('linkViewer.previous');
    expect(LINK_VIEWER_IDS.back).toBe('linkViewer.back');
    expect(LINK_VIEWER_IDS.play).toBe('linkViewer.play');
    expect(LINK_VIEWER_IDS.note).toBe('linkViewer.note');
    expect(LINK_VIEWER_IDS.footer).toBe('linkViewer.footer');
    expect(LINK_VIEWER_IDS.spinner).toBe('linkViewer.spinner');
    expect(LINK_VIEWER_IDS.videoPlayer).toBe('video.player');
    expect(LINK_VIEWER_IDS.item(0)).toBe('linkViewer.item.0');
    expect(LINK_VIEWER_IDS.item(12)).toBe('linkViewer.item.12');
  });

  it('adds ids for the day headers, the counter, the date, the media area and the Get the app page', () => {
    expect(LINK_VIEWER_IDS.day(0)).toBe('linkViewer.day.0');
    expect(LINK_VIEWER_IDS.counter).toBe('linkViewer.counter');
    expect(LINK_VIEWER_IDS.date).toBe('linkViewer.date');
    expect(LINK_VIEWER_IDS.media).toBe('linkViewer.media');
    expect(LINK_VIEWER_IDS.getApp).toBe('getApp.screen');
    expect(LINK_VIEWER_IDS.getAppDownload).toBe('getApp.download');
  });

  it('is frozen', () => {
    expect(Object.isFrozen(LINK_VIEWER_IDS)).toBe(true);
  });
});
