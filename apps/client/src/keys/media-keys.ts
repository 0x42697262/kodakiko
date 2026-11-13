export const VIDEO_EVENTS = Object.freeze({
  loadedMetadata: 'loadedmetadata',
  loadedData: 'loadeddata',
  seeked: 'seeked',
  error: 'error',
} as const);

export const VIDEO_PLAYER_EVENTS = Object.freeze({
  sourceLoad: 'sourceLoad',
  statusChange: 'statusChange',
} as const);
export const VIDEO_PLAYER_ERROR_STATUS = 'error';

export const IMAGE_ORIENTATION = Object.freeze({ fromImage: 'from-image' } as const);

export const MEDIA_LOG_EVENTS = Object.freeze({
  invalidDeviceSettings: 'settings.invalid_device_fields',
} as const);

export const CANVAS_CONTEXT_2D = '2d';
export const CANVAS_ELEMENT = 'canvas';
export const VIDEO_ELEMENT = 'video';
export const VIDEO_PRELOAD_AUTO = 'auto';
export const VIDEO_SRC_ATTRIBUTE = 'src';
