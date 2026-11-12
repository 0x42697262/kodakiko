export type Clock = { readonly now: () => Date };

export const systemClock: Clock = Object.freeze({ now: () => new Date() });

export function fixedClock(date: Date): Clock {
  return Object.freeze({ now: () => new Date(date.getTime()) });
}
