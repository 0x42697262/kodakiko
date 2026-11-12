export type LogFields = Readonly<Record<string, string | number | boolean | null>>;

export type Logger = {
  readonly warn: (event: string, fields: LogFields) => void;
};

export const consoleLogger: Logger = {
  warn: (event, fields) => {
    console.warn(event, JSON.stringify(fields));
  },
};

export const silentLogger: Logger = { warn: () => undefined };
