export type EventBus<Events extends Record<string, unknown>> = {
  readonly on: <Name extends keyof Events>(
    name: Name,
    listener: (payload: Events[Name]) => void,
  ) => () => void;
  readonly emit: <Name extends keyof Events>(name: Name, payload: Events[Name]) => void;
};

export type AppEvents = {
  readonly entriesChanged: readonly string[];
};

export type AppEventBus = EventBus<AppEvents>;

export function createEventBus<Events extends Record<string, unknown>>(): EventBus<Events> {
  const listeners = new Map<keyof Events, Set<(payload: never) => void>>();
  return {
    on: (name, listener) => {
      const forName = listeners.get(name) ?? new Set<(payload: never) => void>();
      forName.add(listener as (payload: never) => void);
      listeners.set(name, forName);
      return () => {
        forName.delete(listener as (payload: never) => void);
      };
    },
    emit: (name, payload) => {
      for (const listener of [...(listeners.get(name) ?? [])]) {
        (listener as (value: typeof payload) => void)(payload);
      }
    },
  };
}
