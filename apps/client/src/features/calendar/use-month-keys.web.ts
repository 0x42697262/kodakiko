import { useEffect } from 'react';
import { NEXT_MONTH, PREVIOUS_MONTH } from '../../constants/calendar-constants';

const KEY_DELTAS: Readonly<Record<string, number>> = Object.freeze({
  ArrowLeft: PREVIOUS_MONTH,
  ArrowRight: NEXT_MONTH,
});
const TYPING_TAGS: ReadonlySet<string> = new Set(['INPUT', 'TEXTAREA']);

export function useMonthKeys(onMove: (delta: number) => void): void {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      const target = event.target;
      if (target instanceof HTMLElement && TYPING_TAGS.has(target.tagName)) return;
      const delta = KEY_DELTAS[event.key];
      if (delta === undefined) return;
      event.preventDefault();
      onMove(delta);
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onMove]);
}
