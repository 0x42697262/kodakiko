import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAutosaver, AUTOSAVE_DEBOUNCE_MS } from '../src/features/entries/autosaver';

describe('autosaver (spec 25, D-89)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('writes an immediate change at once', async () => {
    const write = vi.fn(() => Promise.resolve());
    const saver = createAutosaver<string>(write);
    saver.change('a', false);
    await vi.runAllTimersAsync();
    expect(write).toHaveBeenCalledWith('a');
  });

  it('debounces typing and writes only the latest value', async () => {
    const write = vi.fn(() => Promise.resolve());
    const saver = createAutosaver<string>(write);
    saver.change('h', true);
    saver.change('he', true);
    saver.change('hello', true);
    await vi.advanceTimersByTimeAsync(AUTOSAVE_DEBOUNCE_MS - 1);
    expect(write).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(write).toHaveBeenCalledTimes(1);
    expect(write).toHaveBeenCalledWith('hello');
  });

  it('flush writes a pending debounced value and waits for writes in flight', async () => {
    let release: () => void = () => undefined;
    const write = vi.fn(() => new Promise<void>((resolve) => (release = resolve)));
    const saver = createAutosaver<string>(write);
    saver.change('draft', true);
    const flushed = saver.flush();
    await vi.advanceTimersByTimeAsync(0);
    expect(write).toHaveBeenCalledWith('draft');
    let done = false;
    void flushed.then(() => (done = true));
    await vi.advanceTimersByTimeAsync(0);
    expect(done).toBe(false);
    release();
    await flushed;
    expect(done).toBe(true);
  });

  it('runs writes one after another and reports failures', async () => {
    const order: string[] = [];
    const onError = vi.fn();
    const write = vi.fn((value: string) => {
      order.push(value);
      return value === 'bad' ? Promise.reject(new Error('x')) : Promise.resolve();
    });
    const saver = createAutosaver<string>(write, onError);
    saver.change('bad', false);
    saver.change('good', false);
    await saver.flush();
    expect(order).toEqual(['bad', 'good']);
    expect(onError).toHaveBeenCalledTimes(1);
  });
});
