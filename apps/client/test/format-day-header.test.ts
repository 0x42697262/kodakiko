import { describe, expect, it } from 'vitest';
import { formatDayHeader } from '../src/features/gallery/format-day-header';

describe('formatDayHeader', () => {
  it.each([
    ['2026-11-11', '2026-11-11', 'Today'],
    ['2026-11-10', '2026-11-11', 'Yesterday'],
    ['2026-11-09', '2026-11-11', 'Monday, Nov 9'],
    ['2026-01-01', '2026-11-11', 'Thursday, Jan 1'],
    ['2026-12-25', '2026-11-11', 'Friday, Dec 25'],
    ['2024-11-09', '2026-11-11', 'Nov 9, 2024'],
    ['2024-02-29', '2026-11-11', 'Feb 29, 2024'],
    ['2025-12-31', '2026-01-01', 'Yesterday'],
    ['2025-12-30', '2026-01-01', 'Dec 30, 2025'],
    ['2024-02-29', '2024-03-01', 'Yesterday'],
  ])('%s seen on %s → %s', (localDate, today, expected) => {
    expect(formatDayHeader(localDate, today)).toBe(expected);
  });
});
