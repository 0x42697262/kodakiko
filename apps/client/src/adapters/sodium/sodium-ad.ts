import { MalformedPayloadError, bytesEqual, utf8Decode, utf8Encode } from '@kodakiko/core';
import { EN } from '../../text/en';

export function additionalDataText(additionalData: Uint8Array): string {
  const text = utf8Decode(additionalData);
  if (!bytesEqual(utf8Encode(text), additionalData)) {
    throw new MalformedPayloadError(EN['sodium.additionalDataNotText']);
  }
  return text;
}
