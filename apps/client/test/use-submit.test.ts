import { beforeEach, describe, expect, it, vi } from 'vitest';

type Cell = { value: unknown };
const cells: Cell[] = [];
let cursor = 0;

vi.mock('react', () => ({
  useState: (initial: unknown) => {
    const index = cursor++;
    cells[index] ??= { value: initial };
    const cell = cells[index] as Cell;
    return [cell.value, (next: unknown) => (cell.value = next)];
  },
  useCallback: (callback: unknown) => callback,
}));

const { useSubmit } = await import('../src/features/auth/use-submit');
const { AuthError } = await import('../src/services/auth-errors');

function render(onFailure?: () => void) {
  cursor = 0;
  return onFailure === undefined ? useSubmit() : useSubmit(onFailure);
}

describe('useSubmit', () => {
  beforeEach(() => {
    cells.length = 0;
  });

  it('calls onFailure and keeps the error when the action fails', async () => {
    const onFailure = vi.fn();
    const first = render(onFailure);
    await first.submit(async () => {
      throw new AuthError('invalidCredentials');
    });
    expect(onFailure).toHaveBeenCalledTimes(1);
    const second = render(onFailure);
    expect(second.error?.reason).toBe('invalidCredentials');
    expect(second.busy).toBe(false);
  });

  it('does not call onFailure when the action succeeds', async () => {
    const onFailure = vi.fn();
    await render(onFailure).submit(async () => undefined);
    expect(onFailure).not.toHaveBeenCalled();
  });

  it('works without onFailure', async () => {
    await render().submit(async () => {
      throw new Error('boom');
    });
    expect(render().error?.reason).toBe('generic');
  });
});
