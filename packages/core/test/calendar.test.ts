import { describe, expect, it } from 'vitest';
import { addMonths, isValidYearMonth, monthGrid, yearMonthOf } from '../src/index.ts';

const MONDAY = 1;
const SUNDAY = 0;
const ROWS = 6;
const DAYS_PER_WEEK = 7;

function inMonthDays(yearMonth: string, todayLocalDate: string, weekStartsOn: 0 | 1): string[] {
  return monthGrid(yearMonth, todayLocalDate, weekStartsOn)
    .flat()
    .filter((cell) => cell.inMonth)
    .map((cell) => cell.localDate);
}

describe('monthGrid', () => {
  it('always returns 6 rows of 7 cells', () => {
    for (const month of ['2026-02', '2026-11', '2015-02', '2024-09']) {
      const grid = monthGrid(month, '2026-11-12', MONDAY);
      expect(grid).toHaveLength(ROWS);
      for (const row of grid) expect(row).toHaveLength(DAYS_PER_WEEK);
    }
  });

  it('covers February in a leap year and a non-leap year', () => {
    const leap = inMonthDays('2024-02', '2026-11-12', MONDAY);
    expect(leap).toHaveLength(29);
    expect(leap.at(-1)).toBe('2024-02-29');
    const plain = inMonthDays('2026-02', '2026-11-12', MONDAY);
    expect(plain).toHaveLength(28);
    expect(plain.at(-1)).toBe('2026-02-28');
  });

  it('starts a month that begins on Monday in the first cell (Monday start)', () => {
    const grid = monthGrid('2026-06', '2026-11-12', MONDAY);
    expect(grid[0]?.[0]).toEqual({ localDate: '2026-06-01', inMonth: true, isToday: false });
  });

  it('pads a month that begins on Sunday with the previous month (Monday start)', () => {
    const grid = monthGrid('2026-11', '2026-11-12', MONDAY);
    const firstRow = grid[0] ?? [];
    expect(firstRow.map((cell) => cell.localDate)).toEqual([
      '2026-10-26',
      '2026-10-27',
      '2026-10-28',
      '2026-10-29',
      '2026-10-30',
      '2026-10-31',
      '2026-11-01',
    ]);
    expect(firstRow.slice(0, 6).every((cell) => !cell.inMonth)).toBe(true);
    expect(firstRow[6]?.inMonth).toBe(true);
  });

  it('starts on Sunday when weekStartsOn is 0', () => {
    const grid = monthGrid('2026-11', '2026-11-12', SUNDAY);
    expect(grid[0]?.[0]?.localDate).toBe('2026-11-01');
  });

  it('fills the trailing cells with the next month', () => {
    const last = monthGrid('2026-11', '2026-11-12', MONDAY).at(-1) ?? [];
    expect(last.at(-1)).toEqual({ localDate: '2026-12-06', inMonth: false, isToday: false });
  });

  it('marks exactly today', () => {
    const today = monthGrid('2026-11', '2026-11-12', MONDAY)
      .flat()
      .filter((cell) => cell.isToday);
    expect(today).toEqual([{ localDate: '2026-11-12', inMonth: true, isToday: true }]);
    const elsewhere = monthGrid('2026-10', '2026-11-12', MONDAY)
      .flat()
      .filter((cell) => cell.isToday);
    expect(elsewhere).toEqual([]);
  });
});

describe('addMonths', () => {
  it('moves forward and backward within a year', () => {
    expect(addMonths('2026-05', 1)).toBe('2026-06');
    expect(addMonths('2026-05', -4)).toBe('2026-01');
  });

  it('wraps December to January and back', () => {
    expect(addMonths('2026-12', 1)).toBe('2027-01');
    expect(addMonths('2027-01', -1)).toBe('2026-12');
    expect(addMonths('2026-11', -23)).toBe('2024-12');
  });
});

describe('yearMonthOf and isValidYearMonth', () => {
  it('takes the year and month of a local date', () => {
    expect(yearMonthOf('2026-11-09')).toBe('2026-11');
  });

  it('accepts YYYY-MM with months 01 to 12 only', () => {
    expect(isValidYearMonth('2026-11')).toBe(true);
    expect(isValidYearMonth('0001-01')).toBe(true);
    for (const bad of ['2026-13', '2026-00', '2026-1', '26-11', '2026/11', '2026-11-01', '']) {
      expect(isValidYearMonth(bad)).toBe(false);
    }
  });
});
