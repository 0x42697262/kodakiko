export const APP_ROUTES = Object.freeze({
  root: '/',
  tabs: '/(tabs)',
  welcome: '/welcome',
  invite: '/invite',
  signUp: '/sign-up',
  signIn: '/sign-in',
  securing: '/securing',
  recoveryKey: '/recovery-key',
  recover: '/recover',
  lock: '/lock',
  cancelDeletion: '/cancel-deletion',
  diagnostics: '/settings/diagnostics',
  capture: '/capture',
  entryNew: '/entry/new',
  trash: '/trash',
} as const);

export function entryRoute(entryId: string): `/entry/${string}` {
  return `/entry/${entryId}`;
}
