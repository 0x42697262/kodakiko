import { Redirect } from 'expo-router';
import { useAccountState } from '../src/composition/app-context';
import { ROUTE_BY_STATE } from '../src/composition/route-by-state';
import { useRecoveryPending } from '../src/features/auth/auth-hooks';
import { APP_ROUTES } from '../src/keys/routes';
import { Screen } from '../src/ui/Screen';
import { Spinner } from '../src/ui/Spinner';

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
