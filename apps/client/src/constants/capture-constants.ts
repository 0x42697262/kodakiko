export const HOLD_TO_RECORD_MS = 300;
export const CAMERA_PHOTO_QUALITY = 0.92;
export const WEB_PHOTO_QUALITY = 0.92;
export const RECORDING_TIMER_INTERVAL_MS = 250;
export const LOCATION_TIMEOUT_MS = 5000;
export const TIMER_SECONDS_PER_MINUTE = 60;
export const TIMER_DIGITS = 2;
export const TIMER_PAD = '0';
export const MILLISECONDS_PER_TIMER_SECOND = 1000;
export const TRAY_TILE_SIZE = 72;
export const SHUTTER_SIZE = 72;
export const SHUTTER_RING_WIDTH = 4;
export const SHUTTER_INNER_SIZE = 48;
export const REMOVE_BUTTON_SIZE = 24;
export const REMOVE_BUTTON_RADIUS = 12;
export const VIDEO_FILE_EXTENSIONS: readonly string[] = ['.mp4', '.mov', '.webm'];
export const VIDEO_MIME_PREFIX = 'video/';
export const THUMBNAIL_DATA_URI_PREFIX = 'data:image/webp;base64,';
export const BASE64_CHUNK_BYTES = 8192;
export const FILE_URI_PREFIX = 'file://';
export const WEB_ACCEPT_TYPES =
  'image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime,video/webm';
export const WEB_RECORDER_MIME_TYPES: readonly string[] = [
  'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
  'video/webm;codecs=vp9,opus',
  'video/webm',
];
export const WEB_PHOTO_MIME_TYPE = 'image/jpeg';
export const WEB_CAMERA_FACING = Object.freeze({ back: 'environment', front: 'user' } as const);
export const CAMERA_POSITIONS = Object.freeze({ back: 'back', front: 'front' } as const);
export const PICKED_MEDIA_TYPES = ['images', 'videos'] as const;
export const PICKER_QUALITY = 1;
export const DROP_HIGHLIGHT_OPACITY = 0.6;
export const DROP_OUTLINE_WIDTH = 2;
