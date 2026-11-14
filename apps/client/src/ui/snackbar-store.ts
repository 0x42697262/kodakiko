export type SnackbarAction = { readonly label: string; readonly testID: string; readonly onPress: () => void };
export type SnackbarRequest = { readonly text: string; readonly testID: string; readonly action?: SnackbarAction };
export type SnackbarMessage = SnackbarRequest & { readonly id: number };

export function createSnackbarStore() {
  let current: SnackbarMessage | null = null;
  let nextId = 1;
  const listeners = new Set<() => void>();
  const emit = () => {
    for (const listener of listeners) listener();
  };
  return {
    get: (): SnackbarMessage | null => current,
    subscribe: (listener: () => void): (() => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    show: (request: SnackbarRequest): void => {
      current = { ...request, id: nextId };
      nextId += 1;
      emit();
    },
    dismiss: (id: number): void => {
      if (current?.id !== id) return;
      current = null;
      emit();
    },
  };
}

export const snackbar = createSnackbarStore();
