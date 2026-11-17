import { SWIPE_DIRECTION_RATIO, SWIPE_MIN_DISTANCE_PX } from './constants.ts';
import { KEYBOARD_COMMANDS } from './keys.ts';

export type Direction = 'next' | 'previous';
export type KeyCommand = Direction | 'close';
export type Movement = { readonly dx: number; readonly dy: number };

export function stepIndex(index: number, count: number, direction: Direction): number {
  const next = direction === 'next' ? index + 1 : index - 1;
  return Math.min(Math.max(next, 0), Math.max(count - 1, 0));
}

export function canMove(index: number, count: number, direction: Direction): boolean {
  return stepIndex(index, count, direction) !== index;
}

export function swipeDirection(movement: Movement): Direction | null {
  const horizontal = Math.abs(movement.dx);
  if (horizontal < SWIPE_MIN_DISTANCE_PX) return null;
  if (horizontal < SWIPE_DIRECTION_RATIO * Math.abs(movement.dy)) return null;
  return movement.dx < 0 ? 'next' : 'previous';
}

export function keyCommand(key: string): KeyCommand | null {
  return Object.hasOwn(KEYBOARD_COMMANDS, key) ? (KEYBOARD_COMMANDS[key] ?? null) : null;
}
