export const EXPORT_FILE_OVERHEAD_BYTES = 1024;
export const EXPORT_PART_MAX_FILES = 65_000;
export const EXPORT_PART_MAX_BYTES = 4_026_531_840;
export const EXPORT_WEB_MEMORY_PART_MAX_BYTES = 1_073_741_824;
export const EXPORT_NOTE_FILE = 'entry.md';
export const EXPORT_README_FILE = 'README.txt';
export const EXPORT_MISSING_FILE = 'MISSING.txt';
export const EXPORT_MTIME_MIN = new Date('1980-01-01T12:00:00.000Z');
export const EXPORT_MTIME_MAX = new Date('2099-12-31T12:00:00.000Z');
export const EXPORT_FALLBACK_EXTENSION = 'bin';
export const EXPORT_EXTENSIONS: Readonly<Record<string, string>> = Object.freeze({
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/heic': 'heic',
  'image/heif': 'heif',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'audio/mp4': 'm4a',
  'audio/webm': 'weba',
  'audio/ogg': 'ogg',
});
