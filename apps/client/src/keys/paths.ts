import { ID_REGEX } from '@kodakiko/contract';
import { MalformedPayloadError } from '@kodakiko/core';
import { EN } from '../text/en';

export const APP_DATA_DIRECTORY = 'kodakiko';
export const OBJECTS_DIRECTORY = 'objects';
export const OBJECT_EXTENSION = '.kdks';
export const PARTIAL_EXTENSION = '.partial';
export const PLAIN_THUMBNAILS_DIRECTORY = 'thumbs-plain';
export const PLAIN_THUMBNAIL_EXTENSION = '.webp';
export const TMP_DIRECTORY = 'tmp';
export const TEMP_FILE_SEPARATOR = '-';

function requireObjectId(objectId: string): string {
  if (!ID_REGEX.test(objectId)) throw new MalformedPayloadError(EN['filestore.invalidObjectId']);
  return objectId;
}

export function objectFileName(objectId: string): string {
  return `${requireObjectId(objectId)}${OBJECT_EXTENSION}`;
}

export function partialFileName(objectId: string): string {
  return `${requireObjectId(objectId)}${OBJECT_EXTENSION}${PARTIAL_EXTENSION}`;
}

export function plainThumbnailFileName(mediaId: string): string {
  return `${requireObjectId(mediaId)}${PLAIN_THUMBNAIL_EXTENSION}`;
}

export function mediaIdFromPlainThumbnailName(fileName: string): string | null {
  return fileName.endsWith(PLAIN_THUMBNAIL_EXTENSION)
    ? fileName.slice(0, -PLAIN_THUMBNAIL_EXTENSION.length)
    : null;
}

export function tmpDirectory(accountHash: string): string {
  return `${APP_DATA_DIRECTORY}/${accountHash}/${TMP_DIRECTORY}`;
}

export function tempFileName(purpose: string, random: string, extension: string): string {
  return `${purpose}${TEMP_FILE_SEPARATOR}${random}.${extension}`;
}

export function objectIdFromFileName(fileName: string): string | null {
  return fileName.endsWith(OBJECT_EXTENSION) ? fileName.slice(0, -OBJECT_EXTENSION.length) : null;
}

export function rowStoreFileName(accountHash: string): string {
  return `kodakiko-${accountHash}.db`;
}

export function rowStoreDatabaseName(accountHash: string): string {
  return `kodakiko-${accountHash}`;
}

export const WEB_ARGON2_WORKER_PATH = '/workers/argon2.js';
