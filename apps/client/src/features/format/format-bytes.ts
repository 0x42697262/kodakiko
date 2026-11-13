const STEP = 1024;
const UNITS = Object.freeze(['B', 'KB', 'MB', 'GB', 'TB']);
const DECIMALS = 1;

export function formatBytes(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= STEP && unit < UNITS.length - 1) {
    value /= STEP;
    unit += 1;
  }
  const label = UNITS[unit] ?? '';
  if (unit === 0 || Number.isInteger(value)) return `${value} ${label}`;
  const rounded = Number(value.toFixed(DECIMALS));
  return `${Number.isInteger(rounded) ? rounded.toString() : rounded.toFixed(DECIMALS)} ${label}`;
}
