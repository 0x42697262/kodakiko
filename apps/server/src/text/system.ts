export const SYSTEM_TEXT = Object.freeze({
  readinessTimeout: 'Readiness check timed out.',
  integerOutOfRange: 'A bigint column value is outside the safe integer range.',
  migrationFailed: (name: string | undefined) => `Migration failed: ${name ?? 'unknown'}.`,
  jobTimedOut: 'Job exceeded its time limit.',
  unknownCommand: (name: string) => `Unknown command: ${name}`,
  usage: [
    'Usage: node dist/cli.js <command>',
    '',
    'Commands:',
    '  migrate-status   Print applied and pending migrations',
    '  create-invite    --role <admin|member> [--expires-days <1-90>]  Print an invite URL',
    '  apply-bucket-cors  Apply the bucket CORS rule for PUBLIC_BASE_URL and print it',
    '  help             Show this text',
  ].join('\n'),
  appliedHeading: 'Applied:',
  pendingHeading: 'Pending:',
  noneListed: '  (none)',
});
