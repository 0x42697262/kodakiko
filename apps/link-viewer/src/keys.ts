export const LINK_VIEWER_IDS = Object.freeze({
  screen: 'linkViewer.screen',
  title: 'linkViewer.title',
  message: 'linkViewer.message',
  detail: 'linkViewer.detail',
  download: 'linkViewer.download',
  next: 'linkViewer.next',
  previous: 'linkViewer.previous',
  back: 'linkViewer.back',
  play: 'linkViewer.play',
  note: 'linkViewer.note',
  footer: 'linkViewer.footer',
  spinner: 'linkViewer.spinner',
  videoPlayer: 'video.player',
  counter: 'linkViewer.counter',
  date: 'linkViewer.date',
  media: 'linkViewer.media',
  getApp: 'getApp.screen',
  getAppDownload: 'getApp.download',
  item: (index: number) => `linkViewer.item.${index}`,
  day: (index: number) => `linkViewer.day.${index}`,
});

export const ANDROID_RELEASES_URL = 'https://github.com/0x42697262/kodakiko/releases';

export const HTTP_HEADERS = Object.freeze({
  contentType: 'Content-Type',
  requestId: 'X-Request-Id',
  retryAfter: 'Retry-After',
  range: 'Range',
  contentRange: 'Content-Range',
  contentLength: 'Content-Length',
  acceptRanges: 'Accept-Ranges',
  cacheControl: 'Cache-Control',
});

export const JSON_CONTENT_TYPE = 'application/json';
export const ACCEPT_RANGES_BYTES = 'bytes';
export const NO_STORE = 'no-store';
export const RANGE_PREFIX = 'bytes=';
export const CONTENT_RANGE_PREFIX = 'bytes ';
export const RANGE_PATTERN = /^bytes=(\d*)-(\d*)$/;
export const RANGE_LIST_SEPARATOR = ',';

export const LINK_PATH_PREFIX = '/s/';
export const TRAILING_SLASH = /\/$/;
export const FRAGMENT_MARK = '#';
export const CURSOR_QUERY = '?cursor=';

export const MEDIA_PATH_PREFIX = '/__kdks_media/';
export const MEDIA_WORKER_SCRIPT = '/media-sw.js';
export const MEDIA_WORKER_SCOPE = '/';

export const DOWNLOAD_PREFIX = 'kodakiko-';
export const PHOTO_EXTENSION = 'jpg';
export const BINARY_EXTENSION = 'bin';
export const VIDEO_EXTENSIONS: Readonly<Record<string, string>> = Object.freeze({
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'video/x-matroska': 'mkv',
  'video/3gpp': '3gp',
});

export const MIDNIGHT_SUFFIX = 'T00:00:00.000Z';
export const DATE_SEPARATOR = '-';

export const KEYBOARD_COMMANDS: Readonly<Record<string, 'next' | 'previous' | 'close'>> =
  Object.freeze({
    ArrowRight: 'next',
    ArrowLeft: 'previous',
    Escape: 'close',
  });

export const CSS_CLASSES = Object.freeze({
  page: 'page',
  header: 'header',
  title: 'title',
  status: 'status',
  spinner: 'spinner',
  day: 'day',
  dayHeader: 'day-header',
  grid: 'grid',
  tile: 'tile',
  tileImage: 'tile-image',
  badge: 'badge',
  footer: 'footer',
  detail: 'detail',
  toolbar: 'toolbar',
  counter: 'counter',
  button: 'button',
  primary: 'primary',
  media: 'media',
  photo: 'photo',
  player: 'player',
  caption: 'caption',
  date: 'date',
  note: 'note',
  hint: 'hint',
  noScroll: 'no-scroll',
  getApp: 'get-app',
});

export const ELEMENT_ROLES = Object.freeze({
  dialog: 'dialog',
  status: 'status',
  alert: 'alert',
  progressbar: 'progressbar',
});
export const APP_ROOT_ID = 'app';
export const SELECTORS = Object.freeze({
  nativeMedia: 'video, audio',
  controls: 'button, a',
  textEntry: 'input, textarea, video, audio',
});
export const PRELOAD_ROOT_MARGIN = '100% 0px';
export const ASSET_PATHS = Object.freeze({
  script: '/viewer.js',
  styles: '/styles.css',
  worker: MEDIA_WORKER_SCRIPT,
});
