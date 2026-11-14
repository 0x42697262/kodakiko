export const APP_THEMES = ['system', 'light', 'dark'] as const;
export const DATE_FORMATS = ['system', 'iso', 'dmy', 'mdy'] as const;
export const START_SCREENS = ['gallery', 'calendar'] as const;
export const GALLERY_DISPLAY_MODES = ['comfortableGrid', 'compactGrid', 'photoGrid', 'list'] as const;
export const GALLERY_GROUPS = ['day', 'month', 'year', 'tag', 'none'] as const;
export const GALLERY_SORT_KEYS = ['takenAt', 'noteLength', 'mediaCount', 'random'] as const;
export const TRI_STATES = ['off', 'include', 'exclude'] as const;
export const CAMERA_SIDES = ['back', 'front'] as const;
export const CAMERA_APPS = ['kodakiko', 'system'] as const;
export const HOLD_TO_RECORD_SECONDS = [5, 10, 15, 30, 60, 120] as const;
export const GALLERY_COLUMNS_MAX = 10;
export const GALLERY_COLUMNS_AUTO = 0;

export const UI_SETTINGS_DEFAULTS = Object.freeze({
  appTheme: 'system',
  dateFormat: 'system',
  relativeDates: true,
  startScreen: 'gallery',
  galleryDisplayMode: 'comfortableGrid',
  galleryColumnsPortrait: GALLERY_COLUMNS_AUTO,
  galleryColumnsLandscape: GALLERY_COLUMNS_AUTO,
  galleryGroupByDay: true,
  galleryGroupBy: 'day',
  galleryBadges: Object.freeze({ video: true, photoCount: true, note: true, pending: true, keptOffline: false, favorite: true }),
  galleryShowTagTabs: true,
  galleryShowCounts: false,
  gallerySort: Object.freeze({ key: 'takenAt', descending: true }),
  galleryFilters: Object.freeze({
    video: 'off',
    note: 'off',
    multiple: 'off',
    shared: 'off',
    pending: 'off',
    keptOffline: 'off',
    favorite: 'off',
  }),
  hideShared: false,
  onThisDay: true,
  confirmTrash: true,
  captureStartCamera: 'back',
  captureKeepScreenOn: true,
  hideNotePreviews: false,
  secureScreen: true,
  fullScreen: true,
  hideBarsOnScroll: true,
  showTechnicalDetails: false,
  holdToRecord: false,
  holdToRecordMaxSeconds: 15,
  cameraApp: 'kodakiko',
} as const);
