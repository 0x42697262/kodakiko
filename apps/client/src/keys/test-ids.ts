import type { TextKey } from '../text/en';

export function testId(key: TextKey): string {
  return key;
}

export function listTestId(base: string, position: number): string {
  return `${base}.${position}`;
}

export const TEST_IDS = Object.freeze({
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
} as const);
