export const STACK_TEXT = Object.freeze({
  seedShareFlagOff: 'seedShare needs FLAG_SHARING=true on the UI server.',
  seedShareIncomplete: (added: number, wanted: number) => `seedShare added ${added} of ${wanted} entries.`,
  commandFailed: (command: string, detail: string) => `Command failed: ${command}\n${detail}`,
  commandTimedOut: (command: string) => `Command timed out: ${command}`,
  workspaceRootMissing: (start: string) => `Cannot find the workspace root above ${start}.`,
  garageNodeIdMissing: 'Cannot read the Garage node id from `garage status`.',
  garageStatusTimeout: 'Garage did not answer `garage status` in time.',
  garageS3Timeout: 'The Garage S3 API did not answer in time.',
  apiExited: (code: string) =>
    `The API process exited with ${code} before it was ready. See tests/ui/reports/server.log.`,
  notReady: (url: string, firewallHint: string, apiAnswers: boolean) =>
    apiAnswers
      ? `${url} did not answer 200 in time, but the API itself is ready. ${firewallHint}`
      : `${url} did not answer 200 in time. See tests/ui/reports/server.log.`,
  s3KeysMissing:
    'UI_S3_ACCESS_KEY_ID and UI_S3_SECRET_ACCESS_KEY are not set; the stack is not running.',
  inviteUrlMissing: 'The create-invite command printed no URL.',
  deletionRequestFailed: (status: number) => `Requesting the account deletion answered ${status}.`,
  sessionTokenMissing: 'The seeded account has no session token.',
  seedOutboxNotEmpty: (kinds: string) => `The seeded entries did not finish syncing: ${kinds}`,
  seedMediaMissing: (name: string) => `Cannot read the fixture ${name}.`,
  seedImportRefused: (name: string, reason: string) => `Importing ${name} was refused: ${reason}`,
  serverRequestFailed: (path: string, status: number) => `GET ${path} answered ${status}.`,
  homeCollectionMissing: 'The server lists no home collection for the account.',
});

export const ANDROID_TEXT = Object.freeze({
  missing: (names: readonly string[]) => `Missing: ${names.join(', ')}`,
  adbMissing: '`adb` is not installed or not on PATH.',
  noDevice: (hint: string) => `No Android device is connected. ${hint}`,
  manyDevices: (hint: string) =>
    `More than one Android device is connected; use exactly one. ${hint}`,
  maestroMissing: (path: string) =>
    `Maestro is not installed at ${path}. Run: pnpm --filter @kodakiko/ui-tests install:maestro`,
  javaMissing: (minMajor: number) => `Java ${minMajor} or newer is required (java -version).`,
  metroTimeout:
    'Metro did not report packager-status:running in time. See tests/ui/reports/android/metro.log.',
  flowFailed: (id: string, file: string) => `Flow ${id} failed in ${file}.`,
  flowPassed: (id: string) => `Flow ${id} passed.`,
  summaryFailed: (ids: readonly string[]) => `Failed flows: ${ids.join(', ')}`,
  summaryPassed: 'All flows passed.',
});
