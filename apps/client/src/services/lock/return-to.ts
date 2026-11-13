import { ENTRY_ROUTE_PATTERN, HOME_ROUTE, UNPROTECTED_ROUTES } from './constants';

export function isProtectedRoute(path: string): boolean {
  return !UNPROTECTED_ROUTES.some((pattern) => pattern.test(path));
}

export function entryIdOfRoute(path: string): string | null {
  return ENTRY_ROUTE_PATTERN.exec(path)?.[1] ?? null;
}

export function nextRouteAfterUnlock(returnTo: string | null, entryExists: boolean): string {
  if (returnTo === null || !isProtectedRoute(returnTo)) return HOME_ROUTE;
  if (entryIdOfRoute(returnTo) !== null && !entryExists) return HOME_ROUTE;
  return returnTo;
}
