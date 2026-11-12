import type { TextKey } from '../text/en';

export function testId(key: TextKey): string {
  return key;
}

export function listTestId(base: string, position: number): string {
  return `${base}.${position}`;
}
