export const CONTROL_HOST = '127.0.0.1';
export const CONTROL_PORT = 0;
export const ENV_ARGUMENT_INDEX = 2;
export const NO_ENV_ARGUMENT = '{}';

export const CONTROL_ROUTES = Object.freeze({
  entry: '/entry',
  quota: '/quota',
  rewind: '/rewind',
  pruneTombstones: '/prune-tombstones',
  openLink: '/open-link',
  photoWithGps: '/media/photo-with-gps',
  videoWithLocation: '/media/video-with-location',
  invite: '/invite',
  close: '/close',
});

export const HTTP_OK = 200;
export const HTTP_NO_CONTENT = 204;
export const HTTP_NOT_FOUND = 404;
export const HTTP_SERVER_ERROR = 500;
export const JSON_CONTENT_TYPE = 'application/json';
export const BINARY_CONTENT_TYPE = 'application/octet-stream';
export const CONTENT_TYPE_HEADER = 'content-type';
export const BYTES_ENCODING = 'base64url';
export const READY_EXIT_CODE = 0;
export const FAILURE_EXIT_CODE = 1;
export const TERMINATION_SIGNALS = ['SIGTERM', 'SIGINT'] as const;

export const PHOTO_CONTAINER = 'jpeg';
export const PHOTO_KIND = 'photo';
export const VIDEO_KIND = 'video';
export const MP4_EPOCH = 2_082_844_800;
export const VIDEO_PAYLOAD_BYTES = 4096;
export const VIDEO_PAYLOAD_SEED = 3;
export const VIDEO_CREATION_UTC = '2026-05-01T10:00:00.000Z';
export const VIDEO_LOCATION_TEXT = '+48.8584+002.2945/';
export const VIDEO_BRAND = 'isom';
export const VIDEO_COMPATIBLE_BRANDS = ['isom', 'mp42'];
export const MDAT_HEADER_BYTES = 8;
export const STCO_VERSION_AND_FLAGS = [0, 0, 0, 0];
export const STCO_ENTRY_COUNT = 1;
export const MVHD_VERSION = 0;
export const PHOTO_DATE_TIME_ORIGINAL = '2026:05:01 10:00:00';
export const PHOTO_OFFSET_TIME_ORIGINAL = '+02:00';
export const PHOTO_LATITUDE = 48.8584;
export const PHOTO_LONGITUDE = 2.2945;
export const INVITE_TOKEN_PREFIX = 'kdk_i_';
export const INVITE_TOKEN_BYTES = 32;
export const INVITE_ID_BYTES = 16;
export const INVITE_ROLE = 'member';
export const INVITE_EXPIRES_MS = 604_800_000;
export const INVITE_PATH = '/invite#';
