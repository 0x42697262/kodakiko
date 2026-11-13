import { z } from 'zod';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import {
  AUTO_LOCK_MINUTES_MAX,
  AUTO_LOCK_MINUTES_MIN,
  DEVICE_SETTINGS_SCHEMA_VERSION,
  MIN_LARGE_UPLOAD_THRESHOLD_BYTES,
} from '../../constants/device-settings-constants';
import { STORAGE_KEYS } from '../../keys/storage-keys';

export const deviceSettingsSchema = z.object({
  schemaVersion: z.literal(DEVICE_SETTINGS_SCHEMA_VERSION),
  encryptThumbnails: z.boolean(),
  originalCacheCapBytes: z.number().int().nonnegative(),
  largeUploadsOnWifiOnly: z.boolean(),
  largeUploadThresholdBytes: z.number().int().min(MIN_LARGE_UPLOAD_THRESHOLD_BYTES),
  locationForCaptures: z.boolean(),
  autoLockMinutes: z.number().int().min(AUTO_LOCK_MINUTES_MIN).max(AUTO_LOCK_MINUTES_MAX),
});

export type DeviceSettingsValue = Omit<z.infer<typeof deviceSettingsSchema>, 'schemaVersion'>;

export type DeviceSettings = {
  readonly get: () => DeviceSettingsValue;
  readonly loaded: () => boolean;
  readonly update: (partial: Partial<DeviceSettingsValue>) => Promise<void>;
  readonly subscribe: (listener: () => void) => () => void;
};

export type DeviceSettingsDeps = {
  readonly rowStore: RowStore;
  readonly defaults: DeviceSettingsValue;
  readonly onInvalidFields?: (fields: readonly string[]) => void;
};

const SCHEMA_VERSION_FIELD = 'schemaVersion';
const { schemaVersion: _schemaVersion, ...FIELD_SCHEMAS } = deviceSettingsSchema.shape;
const FIELD_KEYS = Object.keys(FIELD_SCHEMAS) as (keyof DeviceSettingsValue)[];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

type Resolved = { readonly value: DeviceSettingsValue; readonly invalid: readonly string[] };

function resolveStored(raw: unknown, defaults: DeviceSettingsValue): Resolved {
  if (!isRecord(raw)) return { value: defaults, invalid: [] };
  if (raw[SCHEMA_VERSION_FIELD] !== DEVICE_SETTINGS_SCHEMA_VERSION) {
    return { value: defaults, invalid: [SCHEMA_VERSION_FIELD] };
  }
  const next: Record<string, unknown> = { ...defaults };
  const invalid: string[] = [];
  for (const key of FIELD_KEYS) {
    if (!(key in raw)) continue;
    const parsed = FIELD_SCHEMAS[key].safeParse(raw[key]);
    if (parsed.success) next[key] = parsed.data;
    else invalid.push(key);
  }
  return { value: next as DeviceSettingsValue, invalid };
}

async function readStored(
  rowStore: RowStore,
): Promise<{ readonly ok: boolean; readonly raw: unknown }> {
  try {
    return { ok: true, raw: await rowStore.kvGetJson(STORAGE_KEYS.settingsDevice, z.unknown()) };
  } catch {
    return { ok: false, raw: null };
  }
}

export async function createDeviceSettings(deps: DeviceSettingsDeps): Promise<DeviceSettings> {
  const read = await readStored(deps.rowStore);
  const resolved = resolveStored(read.raw, deps.defaults);
  if (resolved.invalid.length > 0) deps.onInvalidFields?.(resolved.invalid);
  let current = resolved.value;
  let loaded = read.ok;
  const listeners = new Set<() => void>();

  return {
    get: () => current,
    loaded: () => loaded,
    update: async (partial) => {
      const next = deviceSettingsSchema.parse({
        schemaVersion: DEVICE_SETTINGS_SCHEMA_VERSION,
        ...current,
        ...partial,
      });
      await deps.rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, next);
      const { schemaVersion: _version, ...value } = next;
      current = value;
      loaded = true;
      for (const listener of [...listeners]) listener();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
