import { describe, expect, it, vi } from 'vitest';
import { createSnackbarStore } from '../src/ui/snackbar-store';

describe('snackbar store', () => {
  it('shows a message and notifies listeners', () => {
    const store = createSnackbarStore();
    const listener = vi.fn();
    store.subscribe(listener);
    store.show({ text: 'Saved.', testID: 'entry.saved' });
    expect(store.get()?.text).toBe('Saved.');
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('replaces the current message with a newer one', () => {
    const store = createSnackbarStore();
    store.show({ text: 'one', testID: 'a' });
    store.show({ text: 'two', testID: 'b' });
    expect(store.get()?.text).toBe('two');
  });

  it('dismisses only the message it was asked to dismiss', () => {
    const store = createSnackbarStore();
    store.show({ text: 'one', testID: 'a' });
    const first = store.get()?.id ?? 0;
    store.show({ text: 'two', testID: 'b' });
    store.dismiss(first);
    expect(store.get()?.text).toBe('two');
    store.dismiss(store.get()?.id ?? 0);
    expect(store.get()).toBeNull();
  });
});
