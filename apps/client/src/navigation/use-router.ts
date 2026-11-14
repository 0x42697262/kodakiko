import { useRouter as useExpoRouter } from 'expo-router';
import { useMemo } from 'react';
import { createNavigationGuard } from './navigation-guard';

const guard = createNavigationGuard(() => Date.now());

export function useRouter(): ReturnType<typeof useExpoRouter> {
  const router = useExpoRouter();
  return useMemo(
    () => ({
      ...router,
      push: (...args: Parameters<typeof router.push>) => {
        if (guard.allow(JSON.stringify(args[0]))) router.push(...args);
      },
      navigate: (...args: Parameters<typeof router.navigate>) => {
        if (guard.allow(JSON.stringify(args[0]))) router.navigate(...args);
      },
    }),
    [router],
  );
}
