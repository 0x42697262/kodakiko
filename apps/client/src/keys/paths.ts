import { ID_REGEX } from '@kodakiko/contract';
import { MalformedPayloadError } from '@kodakiko/core';
import { EN } from '../text/en';

export const APP_DATA_DIRECTORY = 'kodakiko';
export const OBJECTS_DIRECTORY = 'objects';
export const OBJECT_EXTENSION = '.kdks';
export const PARTIAL_EXTENSION = '.partial';

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
