const UNITS = Object.freeze(['B', 'KB', 'MB', 'GB', 'TB']);
const STEP = 1024;
const ONE_DECIMAL_BELOW = 10;

export function formatSize(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= STEP && unit < UNITS.length - 1) {
    value /= STEP;
    unit += 1;
  }
  const text = unit === 0 || value >= ONE_DECIMAL_BELOW ? Math.round(value).toString() : value.toFixed(1);
  return `${text} ${UNITS[unit] ?? ''}`;
}
