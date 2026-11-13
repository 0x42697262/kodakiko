export const ACTIVITY_THROTTLE_MS = 5_000;
export const IDLE_CHECK_INTERVAL_MS = 30_000;
export const MS_PER_MINUTE = 60_000;
export const LOCK_LOG_EVENTS = Object.freeze({ stepFailed: 'lock.step_failed', unlockStepFailed: 'unlock.step_failed' });
export const UNPROTECTED_ROUTES: readonly RegExp[] = Object.freeze([
  /^\/welcome$/,
  /^\/invite$/,
  /^\/sign-in$/,
  /^\/sign-up$/,
  /^\/securing$/,
  /^\/recover$/,
  /^\/lock$/,
  /^\/cancel-deletion$/,
  /^\/s\/[^/]+$/,
]);
export const ENTRY_ROUTE_PATTERN = /^\/entry\/([^/]+)/;
export const HOME_ROUTE = '/';
