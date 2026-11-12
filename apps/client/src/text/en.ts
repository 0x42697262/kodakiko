export const EN = {
  'app.loading': 'Loading…',
  'app.loadFailed': 'Kodakiko could not start.',
  'app.loadFailedClass': (errorClass: string) => `Error: ${errorClass}`,
  'app.contextMissing': 'The app context is not available.',
  'welcome.title': 'Welcome',
  'invite.title': 'Opening invite…',
  'signUp.title': 'Create account',
  'signIn.title': 'Sign In',
  'recover.title': 'Recover account',
  'lock.title': 'Locked',
  'cancelDeletion.title': 'Account deletion pending',
  'home.title': 'Gallery',
  'home.signedInAs': (username: string) => `Signed in as ${username}`,
  'home.signOut': 'Sign Out',
  'http.noServer': 'No server has been chosen yet.',
  'http.networkError': 'The server could not be reached.',
  'http.apiError': (code: string) => `The server answered with ${code}.`,
  'http.schemaMismatch': (fields: string) => `Response did not match the contract at: ${fields}`,
  'serverUrl.needsPlatformWiring': 'The server address store needs its platform wiring.',
  'sodium.missingFunction': (name: string) => `The libsodium wrapper does not provide ${name}.`,
  'sodium.additionalDataNotText': 'The additional data is not valid UTF-8 text.',
  'sodium.workerUnavailable': 'The Argon2id worker is unavailable.',
  'rowstore.deleteBlocked': 'The local database is in use by another tab and cannot be deleted.',
  'rowstore.notOpen': 'The local database is not open.',
  'rowstore.newerSchema': 'The local database was created by a newer version of the app.',
  'filestore.notOpen': 'The local file store is not open.',
  'filestore.invalidObjectId': 'The object id is not valid.',
  'filestore.missingObject': 'The stored file does not exist.',
  'diagnostics.title': 'Diagnostics',
  'diagnostics.runVectors': 'Run crypto vectors',
  'diagnostics.measureKdf': 'Measure key derivation',
  'diagnostics.rowStoreSelfTest': 'RowStore self-test',
  'diagnostics.fileStoreSelfTest': 'FileStore self-test',
  'diagnostics.running': 'Running…',
  'diagnostics.vectorSummary': (passed: number, total: number) => `${passed} of ${total} passed`,
  'diagnostics.vectorCase': (name: string, passed: boolean, durationMs: number) =>
    `${passed ? 'PASS' : 'FAIL'} ${name} (${Math.round(durationMs)} ms)`,
  'diagnostics.kdfSummary': (minMs: number, medianMs: number, maxMs: number) =>
    `min ${Math.round(minMs)} ms, median ${Math.round(medianMs)} ms, max ${Math.round(maxMs)} ms`,
  'diagnostics.kdfParams': (opsLimit: number, memLimitBytes: number) =>
    `final params: opsLimit=${opsLimit} memLimitBytes=${memLimitBytes}`,
  'diagnostics.selfTestPassed': (name: string) => `${name}: passed`,
  'diagnostics.selfTestFailed': (name: string, message: string) => `${name}: failed (${message})`,
  'diagnostics.selfTestCheck': (name: string) => `check failed: ${name}`,
  'diagnostics.failed': (message: string) => `Failed: ${message}`,
} as const;

export type TextKey = keyof typeof EN;

export type StaticTextKey = {
  [Key in TextKey]: (typeof EN)[Key] extends string ? Key : never;
}[TextKey];
