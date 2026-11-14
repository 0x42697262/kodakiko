import { describe, expect, it } from 'vitest';
import { formatClock } from '../src/features/format/format-clock';

describe('formatClock (spec 23 appearance)', () => {
  it('keeps 24-hour times and converts to 12-hour with AM and PM', () => {
    expect(formatClock('17:40', '24h')).toBe('17:40');
    expect(formatClock('17:40', '12h')).toBe('5:40 PM');
    expect(formatClock('00:05', '12h')).toBe('12:05 AM');
    expect(formatClock('12:00', '12h')).toBe('12:00 PM');
  });
});
