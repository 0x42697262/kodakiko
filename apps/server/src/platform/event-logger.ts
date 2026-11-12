export type EventLogger = {
  readonly info: (entry: Record<string, unknown>) => void;
  readonly warn: (entry: Record<string, unknown>) => void;
};
