export const SNIFF_HEADER_BYTES = 64;
export const EXIF_SCAN_MAX_BYTES = 262_144;
export const EXIF_MAX_IFD_ENTRIES = 512;
export const BOX_WALK_MAX_BOXES = 64;
export const MOOV_MAX_BYTES = 8_388_608;
export const MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800;
export const EXIF_ASCII_MAX_BYTES = 64;
export const KEYS_NAME_MAX_BYTES = 256;
export const ISO6709_MAX_BYTES = 64;
export const MAX_ISO_YEAR = 9999;

export const MEDIA_BYTE_RADIX = 256;
export const MEDIA_UINT8_BYTES = 1;
export const MEDIA_UINT16_BYTES = 2;
export const MEDIA_UINT32_BYTES = 4;
export const MEDIA_UINT64_BYTES = 8;
export const MEDIA_MS_PER_SECOND = 1000;
export const MEDIA_MS_PER_MINUTE = 60_000;
export const MEDIA_MINUTES_PER_HOUR = 60;
export const MEDIA_SECONDS_PER_MINUTE = 60;
export const MEDIA_SECONDS_PER_HOUR = 3600;
export const MEDIA_DECIMAL_RADIX = 10;

export type MediaContainer = 'jpeg' | 'png' | 'webp' | 'heif' | 'mp4' | 'mov' | 'webm';
export type MediaKind = 'photo' | 'video';

export type MagicPart = { readonly offset: number; readonly bytes: readonly number[] };
export type MagicSignature = {
  readonly kind: MediaKind;
  readonly mimeType: string;
  readonly container: MediaContainer;
  readonly parts: readonly MagicPart[];
};

const ASCII_RIFF = [0x52, 0x49, 0x46, 0x46];
const ASCII_WEBP = [0x57, 0x45, 0x42, 0x50];

const SIGNATURE_LIST: readonly MagicSignature[] = [
  {
    kind: 'photo',
    mimeType: 'image/jpeg',
    container: 'jpeg',
    parts: [{ offset: 0, bytes: [0xff, 0xd8, 0xff] }],
  },
  {
    kind: 'photo',
    mimeType: 'image/png',
    container: 'png',
    parts: [{ offset: 0, bytes: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] }],
  },
  {
    kind: 'photo',
    mimeType: 'image/webp',
    container: 'webp',
    parts: [
      { offset: 0, bytes: ASCII_RIFF },
      { offset: 8, bytes: ASCII_WEBP },
    ],
  },
  {
    kind: 'video',
    mimeType: 'video/webm',
    container: 'webm',
    parts: [{ offset: 0, bytes: [0x1a, 0x45, 0xdf, 0xa3] }],
  },
];

export const SIGNATURES: readonly MagicSignature[] = Object.freeze(SIGNATURE_LIST);

export const FTYP_TAG = 'ftyp';
export const FTYP_TAG_OFFSET = 4;
export const FTYP_MAJOR_BRAND_OFFSET = 8;
export const FTYP_FIRST_COMPATIBLE_OFFSET = 16;
export const FTYP_BRAND_BYTES = 4;
export const FTYP_MIN_BYTES = 12;

export const HEIC_BRANDS: ReadonlySet<string> = new Set(['heic', 'heix', 'heim', 'heis']);
export const HEIF_BRANDS: ReadonlySet<string> = new Set([
  'heic',
  'heix',
  'heim',
  'heis',
  'hevc',
  'hevx',
  'mif1',
  'msf1',
]);
export const MOV_BRANDS: ReadonlySet<string> = new Set(['qt  ']);
export const MP4_BRANDS: ReadonlySet<string> = new Set([
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
]);

export const MIME_HEIC = 'image/heic';
export const MIME_HEIF = 'image/heif';
export const MIME_MOV = 'video/quicktime';
export const MIME_MP4 = 'video/mp4';

export const TIFF_HEADER_BYTES = 8;
export const TIFF_ORDER_OFFSET = 0;
export const TIFF_MAGIC_OFFSET = 2;
export const TIFF_IFD0_POINTER_OFFSET = 4;
export const TIFF_MAGIC = 42;
export const TIFF_LITTLE_ENDIAN_MARK = 'II';
export const TIFF_BIG_ENDIAN_MARK = 'MM';
export const TIFF_ORDER_MARK_BYTES = 2;
export const IFD_COUNT_BYTES = 2;
export const IFD_ENTRY_BYTES = 12;
export const IFD_ENTRY_TYPE_OFFSET = 2;
export const IFD_ENTRY_COUNT_OFFSET = 4;
export const IFD_ENTRY_VALUE_OFFSET = 8;
export const IFD_INLINE_VALUE_BYTES = 4;

export const TIFF_TYPE_ASCII = 2;
export const TIFF_TYPE_SHORT = 3;
export const TIFF_TYPE_LONG = 4;
export const TIFF_TYPE_RATIONAL = 5;
export const RATIONAL_BYTES = 8;
export const RATIONAL_DENOMINATOR_OFFSET = 4;
export const GPS_COORDINATE_PARTS = 3;

export const TAG_ORIENTATION = 0x0112;
export const TAG_EXIF_POINTER = 0x8769;
export const TAG_GPS_POINTER = 0x8825;
export const TAG_DATE_TIME_ORIGINAL = 0x9003;
export const TAG_OFFSET_TIME_ORIGINAL = 0x9011;
export const TAG_GPS_LATITUDE_REF = 1;
export const TAG_GPS_LATITUDE = 2;
export const TAG_GPS_LONGITUDE_REF = 3;
export const TAG_GPS_LONGITUDE = 4;

export const ORIENTATION_MIN = 1;
export const ORIENTATION_MAX = 8;
export const ORIENTATION_SWAP_MIN = 5;
export const LATITUDE_LIMIT = 90;
export const LONGITUDE_LIMIT = 180;
export const GPS_NORTH = 'N';
export const GPS_SOUTH = 'S';
export const GPS_EAST = 'E';
export const GPS_WEST = 'W';

export const EXIF_DATE_PATTERN = /^\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}$/;
export const EXIF_DATE_PARTS_PATTERN = /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;
export const EXIF_OFFSET_PATTERN = /^([+-])(\d{2}):(\d{2})$/;
export const TRAILING_NULS_PATTERN = /\u0000+$/;
export const ISO6709_PATTERN =
  /^([+-]\d{2}(?:\.\d+)?)([+-]\d{3}(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?\/?$/;

export const JPEG_MARKER_PREFIX = 0xff;
export const JPEG_SOI_BYTES = 2;
export const JPEG_MARKER_BYTES = 2;
export const JPEG_SOI_SECOND_BYTE = 0xd8;
export const JPEG_MARKER_SOS = 0xda;
export const JPEG_MARKER_EOI = 0xd9;
export const JPEG_MARKER_APP1 = 0xe1;
export const JPEG_MARKER_TEM = 0x01;
export const JPEG_MARKER_RST_MIN = 0xd0;
export const JPEG_MARKER_RST_MAX = 0xd7;
export const JPEG_LENGTH_FIELD_BYTES = 2;
export const JPEG_SEGMENT_HEADER_BYTES = 4;
export const EXIF_IDENTIFIER = 'Exif\u0000\u0000';

export const PNG_SIGNATURE_BYTES = 8;
export const PNG_SIGNATURE: readonly number[] = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
export const PNG_CHUNK_HEADER_BYTES = 8;
export const PNG_CHUNK_TYPE_OFFSET = 4;
export const PNG_CHUNK_TYPE_BYTES = 4;
export const PNG_CRC_BYTES = 4;
export const PNG_EXIF_CHUNK = 'eXIf';
export const PNG_END_CHUNK = 'IEND';

export const RIFF_HEADER_BYTES = 12;
export const RIFF_CHUNK_HEADER_BYTES = 8;
export const RIFF_CHUNK_SIZE_OFFSET = 4;
export const RIFF_TAG = 'RIFF';
export const RIFF_WEBP_TAG = 'WEBP';
export const RIFF_WEBP_TAG_OFFSET = 8;
export const WEBP_EXIF_CHUNK = 'EXIF';
export const RIFF_PAD_ALIGNMENT = 2;

export const BOX_HEADER_BYTES = 8;
export const BOX_LARGE_HEADER_BYTES = 16;
export const BOX_PEEK_BYTES = 16;
export const BOX_TYPE_OFFSET = 4;
export const BOX_TYPE_BYTES = 4;
export const BOX_LARGE_SIZE_MARKER = 1;
export const BOX_LARGE_SIZE_OFFSET = 8;
export const BOX_TO_END_MARKER = 0;
export const BOX_MOOV = 'moov';
export const BOX_MVHD = 'mvhd';
export const BOX_UDTA = 'udta';
export const BOX_META = 'meta';
export const BOX_KEYS = 'keys';
export const BOX_ILST = 'ilst';
export const BOX_DATA = 'data';
export const BOX_XYZ = '\u00a9xyz';
export const MVHD_VERSION_1 = 1;
export const MVHD_CREATION_OFFSET = 4;
export const FULL_BOX_HEADER_BYTES = 4;
export const XYZ_LENGTH_BYTES = 2;
export const XYZ_LANGUAGE_BYTES = 2;
export const KEYS_COUNT_OFFSET = 4;
export const KEYS_FIRST_ENTRY_OFFSET = 8;
export const KEYS_ENTRY_SIZE_BYTES = 4;
export const KEYS_ENTRY_NAMESPACE_BYTES = 4;
export const DATA_BOX_VALUE_OFFSET = 8;
export const BOX_TRAK = 'trak';
export const BOX_TKHD = 'tkhd';
export const BOX_MDIA = 'mdia';
export const BOX_HDLR = 'hdlr';
export const HANDLER_VIDEO = 'vide';
export const HDLR_HANDLER_OFFSET = 8;
export const HDLR_HANDLER_BYTES = 4;
export const TKHD_VERSION_1 = 1;
export const TKHD_MATRIX_OFFSET_V0 = 40;
export const TKHD_MATRIX_OFFSET_V1 = 52;
export const MATRIX_CELL_BYTES = 4;
export const MATRIX_A_OFFSET = 0;
export const MATRIX_B_OFFSET = 4;
export const MATRIX_C_OFFSET = 12;
export const MATRIX_D_OFFSET = 16;
export const MATRIX_ONE = 0x10000;
export const UINT32_RANGE = 0x1_0000_0000;
export const INT32_MAX = 0x7fff_ffff;

export type VideoRotation = 0 | 90 | 180 | 270;

export type RotationMatrix = {
  readonly degrees: VideoRotation;
  readonly cells: readonly [number, number, number, number];
};

const ROTATION_MATRIX_LIST: readonly RotationMatrix[] = [
  { degrees: 0, cells: [MATRIX_ONE, 0, 0, MATRIX_ONE] },
  { degrees: 90, cells: [0, MATRIX_ONE, -MATRIX_ONE, 0] },
  { degrees: 180, cells: [-MATRIX_ONE, 0, 0, -MATRIX_ONE] },
  { degrees: 270, cells: [0, -MATRIX_ONE, MATRIX_ONE, 0] },
];

export const ROTATION_MATRICES: readonly RotationMatrix[] = Object.freeze(ROTATION_MATRIX_LIST);
export const APPLE_LOCATION_KEY = 'com.apple.quicktime.location.ISO6709';
