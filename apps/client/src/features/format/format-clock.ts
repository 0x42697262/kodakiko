const NOON = 12;
const HOUR_CHARS = 2;
const MINUTE_START = 3;

export function formatClock(time: string, format: '24h' | '12h'): string {
  if (format === '24h') return time;
  const hour = Number(time.slice(0, HOUR_CHARS));
  const minutes = time.slice(MINUTE_START);
  const suffix = hour >= NOON ? 'PM' : 'AM';
  const twelve = hour % NOON === 0 ? NOON : hour % NOON;
  return `${twelve}:${minutes} ${suffix}`;
}
