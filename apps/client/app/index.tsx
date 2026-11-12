import { Redirect, type Href } from 'expo-router';
import { useAccountState } from '../src/composition/app-context';
import type { AccountStateKind } from '../src/composition/account-state';
import { useRecoveryPending } from '../src/features/auth/auth-hooks';
import { APP_ROUTES } from '../src/keys/routes';
import { Screen } from '../src/ui/Screen';
import { Spinner } from '../src/ui/Spinner';

const ROUTE_BY_STATE: Readonly<Record<AccountStateKind, Href>> = {
  noServer: APP_ROUTES.welcome,
  signedOut: APP_ROUTES.welcome,
  locked: APP_ROUTES.lock,
  pendingDeletion: APP_ROUTES.cancelDeletion,
  unlocked: APP_ROUTES.tabs,
};

export default function Index() {
  const state = useAccountState();
  const unlocked = state.kind === 'unlocked';
  const recovery = useRecoveryPending(unlocked);
  if (unlocked && recovery === 'unknown') {
    return (
      <Screen testID="app.routing.screen">
        <Spinner testID="app.routing" />
      </Screen>
    );
  }
  if (unlocked && recovery === 'pending') return <Redirect href={APP_ROUTES.recoveryKey} />;
  return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
}
