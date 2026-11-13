export const MEDIA_PATH_PREFIX = '/__kdks_media/';
export const SW_MAX_RESPONSE_CHUNKS = 4;
export const SW_KEY_REQUEST_TIMEOUT_MS = 5_000;
export const HTTP_PARTIAL = 206;
export const HTTP_FORBIDDEN = 403;
export const HTTP_GONE = 410;
export const HTTP_RANGE_NOT_SATISFIABLE = 416;
export const HTTP_BAD_GATEWAY = 502;
export const RANGE_PATTERN = /^bytes=(\d*)-(\d*)$/;
export const MEDIA_MESSAGE = Object.freeze({
  register: 'register',
  refreshUrl: 'refreshUrl',
  forget: 'forget',
  forgetAll: 'forgetAll',
  ping: 'ping',
  pong: 'pong',
  needKey: 'needKey',
  needUrl: 'needUrl',
});
export const SW_CHUNK_CACHE_SIZE = 4;
