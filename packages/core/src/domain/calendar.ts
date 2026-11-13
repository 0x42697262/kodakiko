import {
  CALENDAR_ROWS,
  DATE_PAD_CHAR,
  DATE_PART_SEPARATOR,
  DAYS_PER_WEEK,
  DECIMAL_RADIX,
  FIRST_DAY_OF_MONTH,
  ISO_DATE_LENGTH,
  MONTH_DIGITS,
  MONTHS_PER_YEAR,
  MS_PER_DAY,
  YEAR_DIGITS,
  YEAR_MONTH_LENGTH,
  YEAR_MONTH_PATTERN,
} from './constants.ts';

export type CalendarCell = {
  readonly localDate: string;
  readonly inMonth: boolean;
  readonly isToday: boolean;
};

export type WeekStart = 0 | 1;

function parseYearMonth(yearMonth: string): { readonly year: number; readonly monthIndex: number } {
  const [yearText = '', monthText = ''] = yearMonth.split(DATE_PART_SEPARATOR);
  return {
    year: Number.parseInt(yearText, DECIMAL_RADIX),
    monthIndex: Number.parseInt(monthText, DECIMAL_RADIX) - 1,
  };
}

function formatYearMonth(year: number, monthIndex: number): string {
  const yearText = String(year).padStart(YEAR_DIGITS, DATE_PAD_CHAR);
  const monthText = String(monthIndex + 1).padStart(MONTH_DIGITS, DATE_PAD_CHAR);
  return `${yearText}${DATE_PART_SEPARATOR}${monthText}`;
}

function utcDate(year: number, monthIndex: number, day: number): Date {
  const date = new Date(0);
  date.setUTCFullYear(year, monthIndex, day);
  return date;
}

function localDateOfUtc(date: Date): string {
  const yearText = String(date.getUTCFullYear()).padStart(YEAR_DIGITS, DATE_PAD_CHAR);
  return `${yearText}${date.toISOString().slice(YEAR_DIGITS, ISO_DATE_LENGTH)}`;
}

export function isValidYearMonth(value: string): boolean {
  return YEAR_MONTH_PATTERN.test(value);
}

export function yearMonthOf(localDate: string): string {
  return localDate.slice(0, YEAR_MONTH_LENGTH);
}

export function addMonths(yearMonth: string, delta: number): string {
  const { year, monthIndex } = parseYearMonth(yearMonth);
  const total = year * MONTHS_PER_YEAR + monthIndex + delta;
  const nextYear = Math.floor(total / MONTHS_PER_YEAR);
  return formatYearMonth(nextYear, total - nextYear * MONTHS_PER_YEAR);
}

export function monthGrid(
  yearMonth: string,
  todayLocalDate: string,
  weekStartsOn: WeekStart,
): readonly (readonly CalendarCell[])[] {
  const { year, monthIndex } = parseYearMonth(yearMonth);
  const first = utcDate(year, monthIndex, FIRST_DAY_OF_MONTH);
  const leading = (first.getUTCDay() - weekStartsOn + DAYS_PER_WEEK) % DAYS_PER_WEEK;
  const startMs = first.getTime() - leading * MS_PER_DAY;
  const rows: CalendarCell[][] = [];
  for (let row = 0; row < CALENDAR_ROWS; row += 1) {
    const cells: CalendarCell[] = [];
    for (let column = 0; column < DAYS_PER_WEEK; column += 1) {
      const date = new Date(startMs + (row * DAYS_PER_WEEK + column) * MS_PER_DAY);
      const localDate = localDateOfUtc(date);
      cells.push({
        localDate,
        inMonth: date.getUTCFullYear() === year && date.getUTCMonth() === monthIndex,
        isToday: localDate === todayLocalDate,
      });
    }
    rows.push(cells);
  }
  return rows;
}
