export const NATIVE_DEVICE_LABEL = 'Android';
export const FALLBACK_BROWSER_LABEL = 'Browser';

export const BROWSER_LABEL_PATTERNS: readonly {
  readonly pattern: string;
  readonly label: string;
}[] = Object.freeze([
  { pattern: 'Edg/', label: 'Edge' },
  { pattern: 'Firefox/', label: 'Firefox' },
  { pattern: 'Chrome/', label: 'Chrome' },
  { pattern: 'Safari/', label: 'Safari' },
]);
