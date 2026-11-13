import { describe, expect, it } from 'vitest';
import { SNIFF_HEADER_BYTES, sniffFormat } from '../../src/index.ts';
import { asciiBytes, concat, ftyp, seededBytes, u32le } from './builders.ts';

const JPEG_HEADER = Uint8Array.of(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46);
const PNG_HEADER = Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d);
const WEBM_HEADER = Uint8Array.of(0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x86, 0x81);
const WEBP_HEADER = concat(asciiBytes('RIFF'), u32le(36), asciiBytes('WEBP'), asciiBytes('VP8 '));

const HEIC_BRANDS = ['heic', 'heix', 'heim', 'heis'];
const HEIF_BRANDS = ['hevc', 'hevx', 'mif1', 'msf1'];
const MP4_BRANDS = [
  'isom',
  'iso2',
  'iso4',
  'iso5',
  'iso6',
  'mp41',
  'mp42',
  'avc1',
  'M4V ',
  'dash',
  '3gp4',
  '3gp5',
  '3g2a',
];

function header(bytes: Uint8Array): Uint8Array {
  return bytes.slice(0, SNIFF_HEADER_BYTES);
}

describe('sniffFormat', () => {
  it('reads a window of 64 bytes', () => {
    expect(SNIFF_HEADER_BYTES).toBe(64);
  });

  it('recognizes JPEG', () => {
    expect(sniffFormat(JPEG_HEADER)).toEqual({
      kind: 'photo',
      mimeType: 'image/jpeg',
      container: 'jpeg',
    });
  });

  it('recognizes PNG', () => {
    expect(sniffFormat(PNG_HEADER)).toEqual({
      kind: 'photo',
      mimeType: 'image/png',
      container: 'png',
    });
  });

  it('recognizes WebP', () => {
    expect(sniffFormat(WEBP_HEADER)).toEqual({
      kind: 'photo',
      mimeType: 'image/webp',
      container: 'webp',
    });
  });

  it('refuses a RIFF file that is not WebP', () => {
    const avi = concat(asciiBytes('RIFF'), u32le(36), asciiBytes('AVI '), asciiBytes('LIST'));
    expect(sniffFormat(avi)).toBeNull();
  });

  it.each(HEIC_BRANDS)('recognizes a HEIF file with major brand %s as image/heic', (brand) => {
    expect(sniffFormat(header(ftyp(brand, [brand])))).toEqual({
      kind: 'photo',
      mimeType: 'image/heic',
      container: 'heif',
    });
  });

  it.each(HEIF_BRANDS)('recognizes a HEIF file with major brand %s as image/heif', (brand) => {
    expect(sniffFormat(header(ftyp(brand, [brand])))).toEqual({
      kind: 'photo',
      mimeType: 'image/heif',
      container: 'heif',
    });
  });

  it('treats a file whose major brand is mif1 as an image', () => {
    expect(sniffFormat(header(ftyp('mif1', ['mif1', 'miaf'])))).toMatchObject({
      kind: 'photo',
      container: 'heif',
    });
  });

  it('checks compatible brands before MP4 brands', () => {
    expect(sniffFormat(header(ftyp('mp42', ['mp42', 'mif1'])))).toMatchObject({
      kind: 'photo',
      container: 'heif',
    });
  });

  it('recognizes QuickTime movies', () => {
    expect(sniffFormat(header(ftyp('qt  ', ['qt  '])))).toEqual({
      kind: 'video',
      mimeType: 'video/quicktime',
      container: 'mov',
    });
  });

  it.each(MP4_BRANDS)('recognizes an MP4 file with major brand "%s"', (brand) => {
    expect(sniffFormat(header(ftyp(brand, [brand])))).toEqual({
      kind: 'video',
      mimeType: 'video/mp4',
      container: 'mp4',
    });
  });

  it('recognizes an MP4 file with major brand isom and only MP4 compatible brands', () => {
    expect(sniffFormat(header(ftyp('isom', ['isom', 'iso2', 'avc1', 'mp41'])))).toEqual({
      kind: 'video',
      mimeType: 'video/mp4',
      container: 'mp4',
    });
  });

  it('recognizes WebM', () => {
    expect(sniffFormat(WEBM_HEADER)).toEqual({
      kind: 'video',
      mimeType: 'video/webm',
      container: 'webm',
    });
  });

  it('refuses an ftyp box with an unknown brand', () => {
    expect(sniffFormat(header(ftyp('abcd', ['abcd'])))).toBeNull();
  });

  it('refuses empty and truncated headers without throwing', () => {
    expect(sniffFormat(new Uint8Array(0))).toBeNull();
    expect(sniffFormat(Uint8Array.of(0xff, 0xd8))).toBeNull();
    expect(sniffFormat(PNG_HEADER.slice(0, 7))).toBeNull();
    expect(sniffFormat(asciiBytes('ftyp'))).toBeNull();
  });

  it.each([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])('refuses random bytes (seed %i)', (seed) => {
    expect(sniffFormat(seededBytes(SNIFF_HEADER_BYTES, seed))).toBeNull();
  });
});
