import {
  MILLISECONDS_PER_TIMER_SECOND,
  TIMER_DIGITS,
  TIMER_PAD,
  TIMER_SECONDS_PER_MINUTE,
} from '../../constants/capture-constants';

export function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.floor(Math.max(0, milliseconds) / MILLISECONDS_PER_TIMER_SECOND);
  const minutes = Math.floor(totalSeconds / TIMER_SECONDS_PER_MINUTE);
  const seconds = totalSeconds % TIMER_SECONDS_PER_MINUTE;
  return `${String(minutes).padStart(TIMER_DIGITS, TIMER_PAD)}:${String(seconds).padStart(TIMER_DIGITS, TIMER_PAD)}`;
}
