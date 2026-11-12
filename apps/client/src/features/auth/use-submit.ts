import { useCallback, useState } from 'react';
import { AuthError } from '../../services/auth-errors';

export type Submission = {
  readonly busy: boolean;
  readonly error: AuthError | null;
  readonly clearError: () => void;
  readonly submit: (action: () => Promise<void>) => Promise<void>;
};

export function useSubmit(onFailure?: () => void): Submission {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AuthError | null>(null);

  const submit = useCallback(
    async (action: () => Promise<void>) => {
      setBusy(true);
      setError(null);
      try {
        await action();
      } catch (caught) {
        setError(caught instanceof AuthError ? caught : new AuthError('generic'));
        onFailure?.();
      } finally {
        setBusy(false);
      }
    },
    [onFailure],
  );

  const clearError = useCallback(() => setError(null), []);
  return { busy, error, clearError, submit };
}
