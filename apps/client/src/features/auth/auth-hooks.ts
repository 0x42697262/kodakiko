import { useEffect, useState } from 'react';
import { z } from 'zod';
import { useAppContext } from '../../composition/app-context';
import { STORAGE_KEYS } from '../../keys/storage-keys';

export function useDeletionDueAt(): string | null {
  const { rowStore } = useAppContext();
  const [dueAt, setDueAt] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    rowStore
      .kvGetJson(STORAGE_KEYS.accountDeletionDueAt, z.string())
      .then((value) => {
        if (!cancelled) setDueAt(value);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [rowStore]);
  return dueAt;
}

export type RecoveryPending = 'unknown' | 'pending' | 'confirmed';

export function useRecoveryPending(active: boolean): RecoveryPending {
  const { services } = useAppContext();
  const [result, setResult] = useState<RecoveryPending>('unknown');
  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    services.auth
      .recoveryPending()
      .then((pending) => {
        if (!cancelled) setResult(pending ? 'pending' : 'confirmed');
      })
      .catch(() => {
        if (!cancelled) setResult('pending');
      });
    return () => {
      cancelled = true;
    };
  }, [active, services]);
  return result;
}
