export type Clock = {
  readonly now: () => number;
  readonly nowIso: () => string;
};

export const systemClock: Clock = {
  now: () => Date.now(),
  nowIso: () => new Date().toISOString(),
};
