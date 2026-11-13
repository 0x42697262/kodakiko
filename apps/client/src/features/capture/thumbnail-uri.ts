import { BASE64_CHUNK_BYTES, THUMBNAIL_DATA_URI_PREFIX } from '../../constants/capture-constants';

export function thumbnailDataUri(bytes: Uint8Array): string {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += BASE64_CHUNK_BYTES) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + BASE64_CHUNK_BYTES));
  }
  return `${THUMBNAIL_DATA_URI_PREFIX}${btoa(binary)}`;
}
