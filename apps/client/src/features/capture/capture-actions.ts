export function captureActionsEnabled(state: { readonly items: number; readonly pending: number; readonly recording: boolean }): {
  readonly next: boolean;
  readonly addToEntry: boolean;
} {
  const ready = state.items > 0 && !state.recording;
  return { next: ready, addToEntry: ready && state.pending === 0 };
}
