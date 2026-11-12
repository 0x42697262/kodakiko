import { bytesToHex } from '@kodakiko/core';
import { EN } from '../../text/en';

export class SelfTestError extends Error {}

export function expectCheck(condition: boolean, name: string): void {
  if (!condition) throw new SelfTestError(EN['diagnostics.selfTestCheck'](name));
}

function canonical(value: unknown): unknown {
  if (value instanceof Uint8Array) return bytesToHex(value);
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, entry]) => [key, canonical(entry)]),
    );
  }
  return value;
}

export function sameValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
}
