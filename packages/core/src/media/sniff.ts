import { readAscii, readUint32, startsWithBytes } from './binary.ts';
import {
  FTYP_BRAND_BYTES,
  FTYP_FIRST_COMPATIBLE_OFFSET,
  FTYP_MAJOR_BRAND_OFFSET,
  FTYP_MIN_BYTES,
  FTYP_TAG,
  FTYP_TAG_OFFSET,
  HEIC_BRANDS,
  HEIF_BRANDS,
  MIME_HEIC,
  MIME_HEIF,
  MIME_MOV,
  MIME_MP4,
  MOV_BRANDS,
  MP4_BRANDS,
  SIGNATURES,
  SNIFF_HEADER_BYTES,
  type MediaContainer,
  type MediaKind,
} from './constants.ts';

export type MediaFormat = {
  readonly kind: MediaKind;
  readonly mimeType: string;
  readonly container: MediaContainer;
};

function brandsOf(header: Uint8Array): readonly string[] {
  const brands = [readAscii(header, FTYP_MAJOR_BRAND_OFFSET, FTYP_BRAND_BYTES)];
  const boxSize = readUint32(header, 0);
  const end = Math.min(boxSize, header.length);
  for (
    let offset = FTYP_FIRST_COMPATIBLE_OFFSET;
    offset + FTYP_BRAND_BYTES <= end;
    offset += FTYP_BRAND_BYTES
  ) {
    brands.push(readAscii(header, offset, FTYP_BRAND_BYTES));
  }
  return brands;
}

function sniffFtyp(header: Uint8Array): MediaFormat | null {
  if (header.length < FTYP_MIN_BYTES) return null;
  if (readAscii(header, FTYP_TAG_OFFSET, FTYP_TAG.length) !== FTYP_TAG) return null;
  const brands = brandsOf(header);
  const major = brands[0] ?? '';
  if (brands.some((brand) => HEIF_BRANDS.has(brand))) {
    const heic = brands.some((brand) => HEIC_BRANDS.has(brand));
    return { kind: 'photo', mimeType: heic ? MIME_HEIC : MIME_HEIF, container: 'heif' };
  }
  if (MOV_BRANDS.has(major)) return { kind: 'video', mimeType: MIME_MOV, container: 'mov' };
  if (MP4_BRANDS.has(major)) return { kind: 'video', mimeType: MIME_MP4, container: 'mp4' };
  return null;
}

export function sniffFormat(header: Uint8Array): MediaFormat | null {
  const window = header.subarray(0, SNIFF_HEADER_BYTES);
  for (const signature of SIGNATURES) {
    if (signature.parts.every((part) => startsWithBytes(window, part.offset, part.bytes))) {
      return {
        kind: signature.kind,
        mimeType: signature.mimeType,
        container: signature.container,
      };
    }
  }
  return sniffFtyp(window);
}
