import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { Text } from 'react-native';
import { EN } from '../text/en';
import { Screen } from '../ui/Screen';
import { Spinner } from '../ui/Spinner';
import type { AccountState } from './account-state';
import { createAppContext, type AppContext } from './create-app-context';

type LoadState =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly context: AppContext }
  | { readonly status: 'failed'; readonly errorClass: string };

const Context = createContext<AppContext | null>(null);

let contextPromise: Promise<AppContext> | null = null;

function loadContext(): Promise<AppContext> {
  contextPromise ??= createAppContext();
  return contextPromise;
}

export function AppContextProvider({ children }: { readonly children: ReactNode }) {
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    loadContext().then(
      (context) => {
        if (!cancelled) setState({ status: 'ready', context });
      },
      (error: unknown) => {
        if (!cancelled) {
          const errorClass = error instanceof Error ? error.constructor.name : typeof error;
          setState({ status: 'failed', errorClass });
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.status === 'ready')
    return <Context.Provider value={state.context}>{children}</Context.Provider>;
  if (state.status === 'failed') {
    return (
      <Screen testID="app.loadFailed.screen">
        <Text testID="app.loadFailed">{EN['app.loadFailed']}</Text>
        <Text testID="app.loadFailedClass">{EN['app.loadFailedClass'](state.errorClass)}</Text>
      </Screen>
    );
  }
  return (
    <Screen testID="app.loading.screen">
      <Spinner testID="app.loading" />
    </Screen>
  );
}

export function useAppContext(): AppContext {
  const context = useContext(Context);
  if (context === null) throw new Error(EN['app.contextMissing']);
  return context;
}

export function useAccountState(): AccountState {
  const { accountState } = useAppContext();
  return useSyncExternalStore(accountState.subscribe, accountState.get);
}
