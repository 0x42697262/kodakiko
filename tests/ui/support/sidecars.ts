import { readdir } from 'node:fs/promises';
import { FLOW_FILE_PATTERN } from './constants.ts';

export type AdbArguments = readonly (readonly string[])[];

export type SidecarPart = {
  readonly file: string;
  readonly waitAfterMs?: number;
  readonly adb?: AdbArguments;
};

export type Sidecar = {
  readonly seed?: (project: 'android') => Promise<Record<string, string>>;
  readonly parts: readonly SidecarPart[];
};

export class SidecarError extends Error {}

export async function findFlowIds(directory: string): Promise<readonly string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile())
    .map((entry) => FLOW_FILE_PATTERN.exec(entry.name)?.[1])
    .filter((id): id is string => id !== undefined)
    .sort();
}

export function selectFlowIds(
  available: readonly string[],
  requested: readonly string[],
): readonly string[] {
  if (requested.length === 0) return available;
  const unknown = requested.filter((id) => !available.includes(id));
  if (unknown.length > 0) throw new SidecarError(`Unknown flow: ${unknown.join(', ')}`);
  return available.filter((id) => requested.includes(id));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parseAdb(value: unknown, file: string): AdbArguments {
  const valid =
    Array.isArray(value) &&
    value.every(
      (list) => Array.isArray(list) && list.every((argument) => typeof argument === 'string'),
    );
  if (!valid) throw new SidecarError(`Part ${file}: adb must be a list of argument lists`);
  return value as AdbArguments;
}

function parsePart(value: unknown): SidecarPart {
  if (!isRecord(value) || typeof value['file'] !== 'string') {
    throw new SidecarError('Every part needs a file name');
  }
  const file = value['file'];
  const waitAfterMs = value['waitAfterMs'];
  if (waitAfterMs !== undefined && typeof waitAfterMs !== 'number') {
    throw new SidecarError(`Part ${file}: waitAfterMs must be a number`);
  }
  return {
    file,
    ...(waitAfterMs === undefined ? {} : { waitAfterMs }),
    ...(value['adb'] === undefined ? {} : { adb: parseAdb(value['adb'], file) }),
  };
}

export function parseSidecar(value: unknown): Sidecar {
  if (!isRecord(value) || !Array.isArray(value['parts']) || value['parts'].length === 0) {
    throw new SidecarError('A sidecar must export at least one part');
  }
  const seed = value['seed'];
  if (seed !== undefined && typeof seed !== 'function') {
    throw new SidecarError('seed must be a function');
  }
  return {
    parts: value['parts'].map(parsePart),
    ...(seed === undefined ? {} : { seed: seed as NonNullable<Sidecar['seed']> }),
  };
}
