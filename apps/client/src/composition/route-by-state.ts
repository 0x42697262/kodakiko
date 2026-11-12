import type { Href } from 'expo-router';
import { APP_ROUTES } from '../keys/routes';
import type { AccountStateKind } from './account-state';

export const ROUTE_BY_STATE: Readonly<Record<AccountStateKind, Href>> = {
  noServer: APP_ROUTES.welcome,
  signedOut: APP_ROUTES.welcome,
  locked: APP_ROUTES.lock,
  pendingDeletion: APP_ROUTES.cancelDeletion,
  unlocked: APP_ROUTES.tabs,
};
