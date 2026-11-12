export const API_PREFIX = '/api/v1';

export const ROUTE_PATTERNS = Object.freeze({
  config: `${API_PREFIX}/config`,
  healthLive: '/health/live',
  healthReady: '/health/ready',
  authParams: `${API_PREFIX}/auth/params`,
  authSignUp: `${API_PREFIX}/auth/sign-up`,
  authSignIn: `${API_PREFIX}/auth/sign-in`,
  authKeyBundle: `${API_PREFIX}/auth/key-bundle`,
  authRecoveryStart: `${API_PREFIX}/auth/recovery/start`,
  authRecoveryComplete: `${API_PREFIX}/auth/recovery/complete`,
  authPassword: `${API_PREFIX}/auth/password`,
  authSignOut: `${API_PREFIX}/auth/sign-out`,
  authSession: `${API_PREFIX}/auth/session`,
  usersMe: `${API_PREFIX}/users/me`,
  usersMeDelete: `${API_PREFIX}/users/me/delete`,
  usersMeDeleteCancel: `${API_PREFIX}/users/me/delete/cancel`,
});

export const ROUTES = Object.freeze({
  config: () => `${API_PREFIX}/config`,
  healthLive: () => '/health/live',
  healthReady: () => '/health/ready',
  authParams: () => `${API_PREFIX}/auth/params`,
  authSignUp: () => `${API_PREFIX}/auth/sign-up`,
  authSignIn: () => `${API_PREFIX}/auth/sign-in`,
  authKeyBundle: () => `${API_PREFIX}/auth/key-bundle`,
  authRecoveryStart: () => `${API_PREFIX}/auth/recovery/start`,
  authRecoveryComplete: () => `${API_PREFIX}/auth/recovery/complete`,
  authPassword: () => `${API_PREFIX}/auth/password`,
  authSignOut: () => `${API_PREFIX}/auth/sign-out`,
  authSession: () => `${API_PREFIX}/auth/session`,
  usersMe: () => `${API_PREFIX}/users/me`,
  usersMeDelete: () => `${API_PREFIX}/users/me/delete`,
  usersMeDeleteCancel: () => `${API_PREFIX}/users/me/delete/cancel`,
});
