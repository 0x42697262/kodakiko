export const AUTOSAVE_DEBOUNCE_MS = 600;

export function createAutosaver<Value>(write: (value: Value) => Promise<void>, onError: (error: unknown) => void = () => undefined) {
  let chain: Promise<void> = Promise.resolve();
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pending: { readonly value: Value } | null = null;

  const enqueue = (value: Value): void => {
    chain = chain.then(() => write(value)).catch(onError);
  };

  const fire = (): void => {
    timer = null;
    if (pending === null) return;
    const { value } = pending;
    pending = null;
    enqueue(value);
  };

  return {
    change: (value: Value, debounced: boolean): void => {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      pending = { value };
      if (debounced) timer = setTimeout(fire, AUTOSAVE_DEBOUNCE_MS);
      else fire();
    },
    flush: async (): Promise<void> => {
      if (timer !== null) clearTimeout(timer);
      fire();
      await chain;
    },
  };
}
