import { usePathname } from 'expo-router';
import { useEffect, useRef } from 'react';
import { useAppContext } from '../../composition/app-context';
import { isProtectedRoute } from '../../services/lock/return-to';

export function ReturnToRecorder() {
  const { accountState, services } = useAppContext();
  const pathname = usePathname();
  const current = useRef(pathname);
  current.current = pathname;

  useEffect(() => {
    let previous = accountState.get().kind;
    return accountState.subscribe(() => {
      const next = accountState.get().kind;
      if (previous === 'unlocked' && next === 'locked' && isProtectedRoute(current.current)) {
        services.lock.returnTo.set(current.current);
      }
      if (next !== 'locked' && next !== 'unlocked') services.lock.returnTo.set(null);
      previous = next;
    });
  }, [accountState, services.lock.returnTo]);

  return null;
}
