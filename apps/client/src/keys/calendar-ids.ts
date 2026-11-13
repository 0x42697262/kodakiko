import { TEST_IDS } from './test-ids';

export const CALENDAR_IDS = Object.freeze({
  screen: 'calendar.screen',
  monthTitle: 'calendar.monthTitle',
  jumpSheet: 'calendar.jumpSheet',
  jumpError: 'calendar.jumpInvalid',
  weekday: 'calendar.weekday',
  dayNumber: 'calendar.dayNumber',
  dayScreen: 'day.screen',
  dayTitle: 'day.title',
});

export function calendarDayTestId(localDate: string): string {
  return `${TEST_IDS.calendarDay}.${localDate}`;
}
