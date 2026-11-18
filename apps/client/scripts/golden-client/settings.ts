import { createMemoryRowStore } from '../../src/adapters/memory/memory-row-store';
import type { RowStore } from '../../src/adapters/rowstore/rowstore';
import {
  AUTO_LOCK_MINUTES_MAX,
  AUTO_LOCK_MINUTES_MIN,
  MIN_LARGE_UPLOAD_THRESHOLD_BYTES,
} from '../../src/constants/device-settings-constants';
import { DEVICE_SETTINGS_DEFAULTS } from '../../src/constants/device-settings-defaults.native';
import { GALLERY_COLUMNS_AUTO, GALLERY_COLUMNS_MAX } from '../../src/constants/ui-settings-constants';
import { STORAGE_KEYS } from '../../src/keys/storage-keys';
import { createDeviceSettings } from '../../src/services/settings/device-settings';
import { toJson, type GoldenCase, type GoldenWriter, type Json } from './shared';

const PATH = 'settings/device.json';
const ACCOUNT_KEY = 'golden/settings';
const ABSENT_KEY = '$absent';
const BYTES_KEY = '$bytes';
const HEX_RADIX = 16;
const HEX_PAIR = 2;
const MAX_SAFE = Number.MAX_SAFE_INTEGER;
const UTF8 = new TextEncoder();
const FATAL_UTF8 = new TextDecoder('utf-8', { fatal: true });

type JsonObject = { readonly [key: string]: Json };
type DeviceSettingsArgs = Parameters<Awaited<ReturnType<typeof createDeviceSettings>>['update']>[0];

const DEFAULTS = toJson(DEVICE_SETTINGS_DEFAULTS) as JsonObject;
const ABSENT: Json = { [ABSENT_KEY]: true };

function hex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(HEX_RADIX).padStart(HEX_PAIR, '0')).join('');
}

function rawBytes(...bytes: number[]): Json {
  return { [BYTES_KEY]: hex(Uint8Array.from(bytes)) };
}

function rawText(text: string): Json {
  return { [BYTES_KEY]: hex(UTF8.encode(text)) };
}

function fromHex(text: string): Uint8Array {
  const bytes = new Uint8Array(text.length / HEX_PAIR);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(text.slice(index * HEX_PAIR, index * HEX_PAIR + HEX_PAIR), HEX_RADIX);
  }
  return bytes;
}

function isMarker(raw: Json, key: string): raw is JsonObject {
  return typeof raw === 'object' && raw !== null && !Array.isArray(raw) && key in raw;
}

function v1(fields: JsonObject = {}): JsonObject {
  return { schemaVersion: 1, ...fields };
}

async function seededStore(raw: Json): Promise<RowStore> {
  const rowStore = createMemoryRowStore();
  await rowStore.open(ACCOUNT_KEY);
  if (isMarker(raw, ABSENT_KEY)) return rowStore;
  if (isMarker(raw, BYTES_KEY)) await rowStore.kvSet(STORAGE_KEYS.settingsDevice, fromHex(String(raw[BYTES_KEY])));
  else await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, raw);
  return rowStore;
}

async function storedJson(rowStore: RowStore): Promise<Json> {
  const bytes = await rowStore.kvGet(STORAGE_KEYS.settingsDevice);
  return bytes === null ? null : toJson(JSON.parse(FATAL_UTF8.decode(bytes)));
}

async function resolveStored(raw: Json): Promise<Json> {
  const rowStore = await seededStore(raw);
  const invalid: string[] = [];
  const settings = await createDeviceSettings({
    rowStore,
    defaults: DEVICE_SETTINGS_DEFAULTS,
    onInvalidFields: (fields) => invalid.push(...fields),
  });
  return { value: toJson(settings.get()), invalid, loaded: settings.loaded() };
}

async function update(raw: Json, partial: JsonObject): Promise<JsonObject> {
  const rowStore = await seededStore(raw);
  const settings = await createDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });
  let notified = 0;
  settings.subscribe(() => {
    notified += 1;
  });
  let rejected = false;
  try {
    await settings.update(partial as DeviceSettingsArgs);
  } catch {
    rejected = true;
  }
  return { rejected, value: toJson(settings.get()), stored: await storedJson(rowStore), notified };
}

type ResolveSpec = readonly [name: string, raw: Json];
type UpdateSpec = readonly [name: string, raw: Json, partial: JsonObject];

const BEFORE_FAVORITES = v1({
  galleryFilters: { video: 'include', note: 'off', multiple: 'off', shared: 'off', pending: 'off', keptOffline: 'off' },
  galleryBadges: { video: false, photoCount: true, note: true, pending: true, keptOffline: false },
});

const BEFORE_MATERIAL = v1({
  encryptThumbnails: false,
  originalCacheCapBytes: 0,
  largeUploadsOnWifiOnly: false,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: true,
  autoLockMinutes: 30,
});

const ALL_BOOLEANS_WRONG = v1({
  encryptThumbnails: 1,
  largeUploadsOnWifiOnly: null,
  locationForCaptures: 'yes',
  relativeDates: 'true',
  galleryGroupByDay: 0,
  galleryShowTagTabs: [],
  galleryShowCounts: {},
  hideShared: 'false',
  onThisDay: null,
  confirmTrash: 1,
  captureKeepScreenOn: 'on',
  hideNotePreviews: [true],
  secureScreen: 0,
  fullScreen: 'no',
  hideBarsOnScroll: null,
  showTechnicalDetails: 1,
  holdToRecordOn: 'true',
  syncPaused: 0,
  pureBlack: 'TRUE',
  hapticFeedback: null,
  dynamicColor: 1,
  autoplayVideos: 'yes',
  autoplayOnMobileData: {},
});

const ALL_BOOLEANS_FLIPPED = v1(
  Object.fromEntries(Object.entries(DEFAULTS).filter(([, value]) => typeof value === 'boolean').map(([key, value]) => [key, !value])),
);

const ALL_ENUMS_WRONG = v1({
  appTheme: 'sepia',
  dateFormat: 'ISO',
  startScreen: 'Gallery',
  galleryDisplayMode: 'grid',
  galleryGroupBy: 'week',
  captureStartCamera: 1,
  cameraApp: null,
  timeFormat: '24H',
});

const ALL_ENUMS_OTHER = v1({
  appTheme: 'dark',
  dateFormat: 'mdy',
  startScreen: 'calendar',
  galleryDisplayMode: 'list',
  galleryGroupBy: 'tag',
  captureStartCamera: 'front',
  cameraApp: 'system',
  timeFormat: '12h',
});

const FULL_BADGES = { video: false, photoCount: false, note: true, pending: false, keptOffline: true, favorite: false };
const FULL_FILTERS = { video: 'exclude', note: 'include', multiple: 'off', shared: 'exclude', pending: 'include', keptOffline: 'include', favorite: 'include' };
const { favorite: _badgeFavorite, ...BADGES_BEFORE_FAVORITES } = FULL_BADGES;
const { favorite: _filterFavorite, ...FILTERS_BEFORE_FAVORITES } = FULL_FILTERS;

const RESOLVE_SPECS: readonly ResolveSpec[] = [
  ['keeps stored gallery filters and badges from before favorites and fills the new keys', BEFORE_FAVORITES],
  ['fills newer fields with defaults for settings stored before they existed', BEFORE_MATERIAL],
  ['returns the platform defaults when nothing is stored', ABSENT],
  ['falls back to the defaults when the stored value is not readable', rawBytes(1, 2, 3)],
  [
    'replaces an invalid field by its default and keeps the valid ones',
    v1({ autoLockMinutes: 99, locationForCaptures: true, largeUploadThresholdBytes: 10 }),
  ],
  ['is loaded after a successful read, with or without a stored value (nothing stored)', ABSENT],
  ['is not loaded after a read error and serves the defaults', rawBytes(0x7b, 0xff, 0x7d)],
  ['uses the defaults and reports schemaVersion when the stored version is not 1', { schemaVersion: 2, autoLockMinutes: 10 }],
  ['uses the defaults when the stored value has no schemaVersion', { autoLockMinutes: 10 }],
  ['fills fields that are missing from an older stored value with the defaults', v1({ autoLockMinutes: 10 })],
  ['edge: schemaVersion as a string', { schemaVersion: '1', autoLockMinutes: 10 }],
  ['edge: schemaVersion 0', { schemaVersion: 0, autoLockMinutes: 10 }],
  ['edge: schemaVersion null', { schemaVersion: null, autoLockMinutes: 10 }],
  ['edge: schemaVersion 1.0 counts as 1', rawText('{"schemaVersion":1.0,"autoLockMinutes":10}')],
  ['edge: a newer schemaVersion with otherwise valid fields', { ...DEFAULTS, schemaVersion: 2, pureBlack: true }],
  ['edge: empty object', {}],
  ['edge: only the schemaVersion', v1()],
  ['edge: every stored field valid and non-default', v1({ ...ALL_BOOLEANS_FLIPPED, ...ALL_ENUMS_OTHER, galleryBadges: FULL_BADGES, galleryFilters: FULL_FILTERS })],
  ['edge: every boolean field with a wrong type', ALL_BOOLEANS_WRONG],
  ['edge: every boolean field flipped', ALL_BOOLEANS_FLIPPED],
  ['edge: every enum field with an unknown value', ALL_ENUMS_WRONG],
  ['edge: every enum field with another valid value', ALL_ENUMS_OTHER],
  [
    'edge: int fields at their lower boundaries',
    v1({
      originalCacheCapBytes: 0,
      largeUploadThresholdBytes: MIN_LARGE_UPLOAD_THRESHOLD_BYTES,
      autoLockMinutes: AUTO_LOCK_MINUTES_MIN,
      galleryColumnsPortrait: GALLERY_COLUMNS_AUTO,
      galleryColumnsLandscape: 1,
    }),
  ],
  [
    'edge: int fields at their upper boundaries',
    v1({
      originalCacheCapBytes: MAX_SAFE,
      largeUploadThresholdBytes: MAX_SAFE,
      autoLockMinutes: AUTO_LOCK_MINUTES_MAX,
      galleryColumnsPortrait: GALLERY_COLUMNS_MAX,
      galleryColumnsLandscape: GALLERY_COLUMNS_MAX,
    }),
  ],
  [
    'edge: int fields just outside their boundaries',
    v1({
      originalCacheCapBytes: -1,
      largeUploadThresholdBytes: MIN_LARGE_UPLOAD_THRESHOLD_BYTES - 1,
      autoLockMinutes: AUTO_LOCK_MINUTES_MAX + 1,
      galleryColumnsPortrait: GALLERY_COLUMNS_MAX + 1,
      galleryColumnsLandscape: GALLERY_COLUMNS_AUTO - 1,
    }),
  ],
  [
    'edge: int fields with fractions, unsafe integers and strings',
    v1({
      originalCacheCapBytes: MAX_SAFE + 1,
      largeUploadThresholdBytes: MIN_LARGE_UPLOAD_THRESHOLD_BYTES + 0.5,
      autoLockMinutes: 1.5,
      galleryColumnsPortrait: '3',
      galleryColumnsLandscape: null,
    }),
  ],
  ['edge: auto-lock of 0 and -5 minutes', v1({ autoLockMinutes: 0, originalCacheCapBytes: -5 })],
  [
    'edge: integral numbers written with a fraction, an exponent or a minus zero',
    rawText(
      '{"schemaVersion":1,"autoLockMinutes":3.0,"originalCacheCapBytes":1e3,"largeUploadThresholdBytes":2E+6,"galleryColumnsPortrait":-0,"galleryColumnsLandscape":10.000,"holdToRecordMaxSeconds":1.2e2}',
    ),
  ],
  ['edge: numbers that overflow to infinity', rawText('{"schemaVersion":1,"originalCacheCapBytes":1e400,"autoLockMinutes":-1e400,"holdToRecordMaxSeconds":1e400}')],
  ['edge: holdToRecordMaxSeconds 5 is allowed', v1({ holdToRecordMaxSeconds: 5 })],
  ['edge: holdToRecordMaxSeconds 120 is allowed', v1({ holdToRecordMaxSeconds: 120 })],
  ['edge: holdToRecordMaxSeconds 7 is not allowed', v1({ holdToRecordMaxSeconds: 7, holdToRecordOn: false })],
  ['edge: holdToRecordMaxSeconds as a string is not allowed', v1({ holdToRecordMaxSeconds: '15' })],
  ['edge: holdToRecordMaxSeconds 0 is not allowed', v1({ holdToRecordMaxSeconds: 0 })],
  ['edge: tagOrder with non-ASCII, empty and duplicate tags', v1({ tagOrder: ['beach', 'Café 東京 🌸', '', 'beach', 'a"b\\c\n\t\u0001\u007f\u2028'] })],
  ['edge: tagOrder with a non-string element', v1({ tagOrder: ['beach', 1], pureBlack: true })],
  ['edge: tagOrder as a string', v1({ tagOrder: 'beach' })],
  ['edge: tagOrder as an object', v1({ tagOrder: { 0: 'beach' } })],
  ['edge: tagOrder with a lone surrogate', rawText('{"schemaVersion":1,"tagOrder":["\\ud800","\\udfff\\ud83c\\udf38","\\u00e9"]}')],
  ['edge: nested objects without their defaulted fields', v1({ galleryBadges: BADGES_BEFORE_FAVORITES, galleryFilters: FILTERS_BEFORE_FAVORITES })],
  ['edge: nested objects with explicit defaulted fields', v1({ galleryBadges: FULL_BADGES, galleryFilters: FULL_FILTERS })],
  ['edge: nested objects with unknown keys', v1({ galleryBadges: { ...FULL_BADGES, sparkle: true }, galleryFilters: { ...FULL_FILTERS, sparkle: 'on' }, gallerySort: { key: 'random', descending: false, seed: 4 } })],
  ['edge: nested objects with a missing required field', v1({ galleryBadges: { video: false }, galleryFilters: { favorite: 'include' }, gallerySort: { key: 'random' } })],
  ['edge: nested defaulted fields set to null', v1({ galleryBadges: { ...FULL_BADGES, favorite: null }, galleryFilters: { ...FULL_FILTERS, favorite: null } })],
  ['edge: nested objects as arrays and strings', v1({ galleryBadges: [], galleryFilters: 'off', gallerySort: null })],
  ['edge: nested values of the wrong type', v1({ galleryBadges: { ...FULL_BADGES, video: 'yes' }, galleryFilters: { ...FULL_FILTERS, video: 'maybe' }, gallerySort: { key: 'name', descending: true } })],
  ['edge: unknown top-level keys are ignored', v1({ autoLockMinutes: 10, holdToRecord: true, sparkle: { deep: [1, 2] } })],
  ['edge: a __proto__ key is a plain unknown key', rawText('{"schemaVersion":1,"__proto__":{"pureBlack":true},"autoLockMinutes":10}')],
  ['edge: duplicate keys, the last one wins', rawText('{"schemaVersion":1,"autoLockMinutes":10,"pureBlack":"no","autoLockMinutes":20,"pureBlack":true}')],
  ['edge: a raw array', []],
  ['edge: a raw array holding a settings object', [v1({ autoLockMinutes: 10 })]],
  ['edge: a raw string', 'settings'],
  ['edge: a raw number', 1],
  ['edge: a raw boolean', true],
  ['edge: a raw null', null],
  ['edge: invalid JSON: empty bytes', rawBytes()],
  ['edge: invalid JSON: truncated object', rawText('{"schemaVersion":1,"autoLockMinutes":10')],
  ['edge: invalid JSON: trailing comma', rawText('{"schemaVersion":1,"autoLockMinutes":10,}')],
  ['edge: invalid JSON: unquoted value', rawText('{"schemaVersion":1,"pureBlack":yes}')],
  ['edge: invalid JSON: unquoted key', rawText('{schemaVersion:1}')],
  ['edge: invalid JSON: single quotes', rawText("{'schemaVersion':1}")],
  ['edge: invalid JSON: leading zero', rawText('{"schemaVersion":01}')],
  ['edge: invalid JSON: NaN', rawText('{"schemaVersion":1,"autoLockMinutes":NaN}')],
  ['edge: invalid JSON: plus sign', rawText('{"schemaVersion":+1}')],
  ['edge: invalid JSON: fraction without digits', rawText('{"schemaVersion":1.}')],
  ['edge: invalid JSON: trailing garbage', rawText('{"schemaVersion":1} x')],
  ['edge: invalid JSON: two values', rawText('{"schemaVersion":1}{}')],
  ['edge: invalid JSON: comment', rawText('{"schemaVersion":1}//')],
  ['edge: invalid JSON: raw control character in a string', rawText('{"schemaVersion":1,"tagOrder":["a\tb"]}')],
  ['edge: invalid JSON: unknown escape', rawText('{"schemaVersion":1,"tagOrder":["\\x41"]}')],
  ['edge: invalid JSON: short unicode escape', rawText('{"schemaVersion":1,"tagOrder":["\\u41"]}')],
  ['edge: invalid JSON: capitalized literal', rawText('{"schemaVersion":1,"pureBlack":True}')],
  ['edge: invalid JSON: whitespace only', rawText(' \n\t\r')],
  ['edge: invalid JSON: non-breaking space as whitespace', rawText('\u00a0{"schemaVersion":1}')],
  ['edge: invalid JSON: second byte order mark', rawBytes(0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x7b, 0x7d)],
  ['edge: invalid UTF-8: lone continuation byte', rawBytes(0x7b, 0x7d, 0x80)],
  ['edge: invalid UTF-8: overlong encoding', rawBytes(0x22, 0xc0, 0xa2, 0x22)],
  ['edge: invalid UTF-8: encoded surrogate', rawBytes(0x22, 0xed, 0xa0, 0x80, 0x22)],
  ['edge: invalid UTF-8: truncated sequence', rawBytes(0x22, 0xe2, 0x82, 0x22)],
  ['edge: leading byte order mark is skipped', rawBytes(0xef, 0xbb, 0xbf, ...UTF8.encode('{"schemaVersion":1,"autoLockMinutes":10}'))],
  ['edge: JSON whitespace around and inside the value', rawText(' \r\n\t{ "schemaVersion" : 1 ,\n "autoLockMinutes" : 10 } \n')],
  ['edge: escaped key names match', rawText('{"schema\\u0056ersion":1,"\\u0061utoLockMinutes":10}')],
];

const AUTO_LOCK_REJECTED: readonly number[] = [0, 61, 1.5, -5];
const AUTO_LOCK_ACCEPTED: readonly number[] = [1, 60];

const UPDATE_SPECS: readonly UpdateSpec[] = [
  ['merges an update, keeps the other fields and stores JSON with schemaVersion 1', ABSENT, { locationForCaptures: true, autoLockMinutes: 30 }],
  ['notifies subscribers after an update and stops after unsubscribe', ABSENT, { largeUploadsOnWifiOnly: false }],
  ['loads what an earlier instance stored (first instance)', ABSENT, { originalCacheCapBytes: 0, encryptThumbnails: true }],
  ['is loaded after a successful read, with or without a stored value (update)', ABSENT, { autoLockMinutes: 10 }],
  ['accepts the defaults', ABSENT, DEFAULTS],
  ['rejects unknown choices and out-of-range columns (appTheme sepia)', ABSENT, { appTheme: 'sepia' }],
  ['rejects unknown choices and out-of-range columns (galleryColumnsPortrait 11)', ABSENT, { galleryColumnsPortrait: 11 }],
  ['rejects unknown choices and out-of-range columns (galleryColumnsPortrait 10)', ABSENT, { galleryColumnsPortrait: 10 }],
  [
    'rejects unknown choices and out-of-range columns (galleryFilters video maybe)',
    ABSENT,
    { galleryFilters: { ...(DEFAULTS['galleryFilters'] as JsonObject), video: 'maybe' } },
  ],
  ['rejects unknown choices and out-of-range columns (gallerySort random ascending)', ABSENT, { gallerySort: { key: 'random', descending: false } }],
  ['rejects unknown choices and out-of-range columns (holdToRecordMaxSeconds 7)', ABSENT, { holdToRecordMaxSeconds: 7 }],
  ['rejects unknown choices and out-of-range columns (cameraApp system)', ABSENT, { cameraApp: 'system' }],
  ...AUTO_LOCK_REJECTED.map((minutes): UpdateSpec => [`rejects an auto-lock of ${minutes} minutes`, ABSENT, { autoLockMinutes: minutes }]),
  ...AUTO_LOCK_ACCEPTED.map((minutes): UpdateSpec => [`accepts an auto-lock of ${minutes} minutes`, ABSENT, { autoLockMinutes: minutes }]),
  [
    'rejects an upload threshold below 1 MiB and accepts exactly 1 MiB (below)',
    ABSENT,
    { largeUploadThresholdBytes: MIN_LARGE_UPLOAD_THRESHOLD_BYTES - 1 },
  ],
  ['rejects an upload threshold below 1 MiB and accepts exactly 1 MiB (exactly)', ABSENT, { largeUploadThresholdBytes: MIN_LARGE_UPLOAD_THRESHOLD_BYTES }],
  ['rejects a negative or fractional cache cap and accepts 0 for no limit (negative)', ABSENT, { originalCacheCapBytes: -1 }],
  ['rejects a negative or fractional cache cap and accepts 0 for no limit (fractional)', ABSENT, { originalCacheCapBytes: 1.5 }],
  ['rejects a negative or fractional cache cap and accepts 0 for no limit (zero)', ABSENT, { originalCacheCapBytes: 0 }],
  ['rejects non-boolean switches (locationForCaptures)', ABSENT, { locationForCaptures: 'yes' }],
  ['rejects non-boolean switches (encryptThumbnails)', ABSENT, { encryptThumbnails: 1 }],
  ['rejects non-boolean switches (largeUploadsOnWifiOnly)', ABSENT, { largeUploadsOnWifiOnly: null }],
  [
    'edge: merging several fields at once',
    v1({ autoLockMinutes: 10, tagOrder: ['old'] }),
    { appTheme: 'dark', pureBlack: true, tagOrder: ['beach', 'Café 東京 🌸'], timeFormat: '12h', holdToRecordMaxSeconds: 60, galleryColumnsLandscape: 6 },
  ],
  ['edge: only nested objects', v1({ pureBlack: true }), { galleryBadges: FULL_BADGES, galleryFilters: FULL_FILTERS, gallerySort: { key: 'mediaCount', descending: false } }],
  [
    'edge: nested objects without their defaulted fields get the defaults',
    ABSENT,
    { galleryBadges: BADGES_BEFORE_FAVORITES, galleryFilters: FILTERS_BEFORE_FAVORITES },
  ],
  ['edge: a partial nested object is rejected, there is no deep merge', ABSENT, { galleryBadges: { video: false } }],
  ['edge: nested unknown keys are dropped', ABSENT, { gallerySort: { key: 'noteLength', descending: true, seed: 3 } }],
  ['edge: unknown top-level keys are dropped', ABSENT, { holdToRecord: true, sparkle: 1, pureBlack: true }],
  ['edge: unknown keys stored earlier are dropped on the next write', v1({ autoLockMinutes: 10, holdToRecord: true, sparkle: { deep: 1 } }), {}],
  ['edge: an empty partial writes the current value', ABSENT, {}],
  ['edge: an invalid value is rejected, nothing is stored and nobody is notified', ABSENT, { autoLockMinutes: 0 }],
  ['edge: one invalid field rejects the whole update', v1({ autoLockMinutes: 10 }), { pureBlack: true, appTheme: 'sepia' }],
  ['edge: a rejected update keeps the stored value', v1({ autoLockMinutes: 10, holdToRecord: true }), { tagOrder: ['a', 1] }],
  ['edge: schemaVersion 2 in the partial is rejected', ABSENT, { schemaVersion: 2, pureBlack: true }],
  ['edge: schemaVersion 1 in the partial is accepted', ABSENT, { schemaVersion: 1, pureBlack: true }],
  ['edge: null for a field is rejected', ABSENT, { appTheme: null }],
  ['edge: an update over unreadable bytes replaces them', rawBytes(1, 2, 3), { pureBlack: true }],
  ['edge: an update over invalid fields stores their defaults', v1({ autoLockMinutes: 99, pureBlack: true }), { hapticFeedback: false }],
  ['edge: an update over a newer schemaVersion overwrites it with version 1', { schemaVersion: 2, autoLockMinutes: 10 }, { pureBlack: true }],
  ['edge: an update over a non-object value', ['x'], { autoLockMinutes: 10 }],
  ['edge: holdToRecordMaxSeconds 5', ABSENT, { holdToRecordMaxSeconds: 5 }],
  ['edge: holdToRecordMaxSeconds 10', ABSENT, { holdToRecordMaxSeconds: 10 }],
  ['edge: holdToRecordMaxSeconds 30', ABSENT, { holdToRecordMaxSeconds: 30 }],
  ['edge: holdToRecordMaxSeconds 120', ABSENT, { holdToRecordMaxSeconds: 120 }],
  ['edge: holdToRecordMaxSeconds 15.5', ABSENT, { holdToRecordMaxSeconds: 15.5 }],
  ['edge: largest safe cache cap', ABSENT, { originalCacheCapBytes: MAX_SAFE }],
  ['edge: unsafe cache cap', ABSENT, { originalCacheCapBytes: MAX_SAFE + 1 }],
  ['edge: a cache cap of 1e20 is not a safe integer', ABSENT, { originalCacheCapBytes: 1e20 }],
  ['edge: columns automatic and 1', v1({ galleryColumnsPortrait: 4 }), { galleryColumnsPortrait: GALLERY_COLUMNS_AUTO, galleryColumnsLandscape: 1 }],
  ['edge: tagOrder with special characters is stored escaped like JSON.stringify', ABSENT, { tagOrder: ['a"b\\c', '\n\t\b\f\r', '\u0000\u001f\u007f', '\u2028\u2029', 'é東🌸', '\ud800', '\udc00x'] }],
  ['edge: tagOrder with a non-string is rejected', ABSENT, { tagOrder: ['a', null] }],
];

async function resolveCases(): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  for (const [name, raw] of RESOLVE_SPECS) {
    cases.push({ name: `resolveStored: ${name}`, fn: 'resolveStored', args: { raw }, result: await resolveStored(raw) });
  }
  for (const [name, partial] of [
    ['loads what an earlier instance stored', { originalCacheCapBytes: 0, encryptThumbnails: true }],
    ['is loaded after a successful read, with or without a stored value (stored value)', { autoLockMinutes: 10 }],
  ] as const) {
    const raw = (await update(ABSENT, partial))['stored'] ?? null;
    cases.push({ name: `resolveStored: ${name}`, fn: 'resolveStored', args: { raw }, result: await resolveStored(raw) });
  }
  return cases;
}

async function updateCases(): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  for (const [name, raw, partial] of UPDATE_SPECS) {
    const args = toJson({ raw, partial }) as JsonObject;
    const result = await update(args['raw'] ?? null, args['partial'] as JsonObject);
    if (result['rejected'] === true && isMarker(raw, BYTES_KEY)) throw new Error(`${PATH}: ${name}: rejected update over raw bytes`);
    cases.push({ name: `update: ${name}`, fn: 'update', args, result });
  }
  return cases;
}

export async function writeSettingsGoldens(writer: GoldenWriter): Promise<void> {
  await writer.functionCases(PATH, [
    { name: 'nativeDefaults: has the Android defaults', fn: 'nativeDefaults', args: {}, result: DEFAULTS },
    ...(await resolveCases()),
    ...(await updateCases()),
  ]);
}
