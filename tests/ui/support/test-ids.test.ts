import { describe, expect, it } from 'vitest';
import { TEST_IDS, listTestId, testId } from '../../../apps/client/src/keys/test-ids.ts';

describe('test ids', () => {
  it('appends the zero-based position to a base', () => {
    expect(listTestId('gallery.tile', 0)).toBe('gallery.tile.0');
    expect(listTestId('recoveryKey.word', 24)).toBe('recoveryKey.word.24');
  });

  it('returns a text key unchanged', () => {
    expect(testId('welcome.title')).toBe('welcome.title');
  });

  it('freezes the table of ids for elements without text', () => {
    expect(Object.isFrozen(TEST_IDS)).toBe(true);
    expect(TEST_IDS).toEqual({
      galleryTile: 'gallery.tile',
      galleryTilePending: 'gallery.tilePending',
      galleryFab: 'gallery.capture',
      captureShutter: 'capture.shutter',
      captureTrayItem: 'capture.trayItem',
      captureFileInput: 'capture.fileInput',
      captureDropZone: 'capture.dropZone',
      viewerPage: 'viewer.page',
      viewerPageIndicator: 'viewer.pageIndicator',
      viewerZoomable: 'viewer.zoomable',
      videoPlayer: 'video.player',
      calendarDay: 'calendar.day',
      recoveryKeyWord: 'recoveryKey.word',
      recoveryKeyConfirmLabel: 'recoveryKey.confirmLabel',
      recoveryKeyConfirmField: 'recoveryKey.confirmField',
      placeholderSignedIn: 'home.signedInAs',
      linkViewerItem: 'linkViewer.item',
    });
  });
});
