import { Redirect, type Href } from 'expo-router';
import { useAccountState } from '../src/composition/app-context';
import type { AccountStateKind } from '../src/composition/account-state';
import { APP_ROUTES } from '../src/keys/routes';

const ROUTE_BY_STATE: Readonly<Record<AccountStateKind, Href>> = {
  noServer: APP_ROUTES.welcome,
  signedOut: APP_ROUTES.welcome,
  locked: APP_ROUTES.lock,
  pendingDeletion: APP_ROUTES.cancelDeletion,
  unlocked: APP_ROUTES.tabs,
};

export default function Index() {
  const state = useAccountState();
  return <Redirect href={ROUTE_BY_STATE[state.kind]} />;
}
