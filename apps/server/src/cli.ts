import { createDatabase } from './adapters/db/database.ts';
import { applyBucketCors } from './adapters/storage/bucket-cors.ts';
import { createFailedSignInTracker } from './adapters/memory/failed-sign-in-tracker.ts';
import { parseCreateInviteArguments } from './cli-arguments.ts';
import { createMigrator } from './adapters/db/migrator.ts';
import {
  EXIT_CODES,
  FAILED_SIGN_IN_TRACKER_MAX_ENTRIES,
  MINUTE_MS,
} from './config/constants.ts';
import { loadSettings } from './config/settings.ts';
import { SettingsError } from './domain/errors.ts';
import { fatalLine } from './platform/process.ts';
import { systemClock } from './platform/clock.ts';
import { silentLogger } from './platform/silent-logger.ts';
import { createAuthService } from './services/auth-service.ts';
import { LOG_EVENTS } from './text/events.ts';
import { STORAGE_TEXT } from './text/storage.ts';
import { SYSTEM_TEXT } from './text/system.ts';

type Command = (args: readonly string[]) => Promise<number>;

const NEWLINE = '\n';
const ARGV_COMMAND_START = 2;
const JSON_INDENT_SPACES = 2;

function printLines(lines: readonly string[]): void {
  process.stdout.write(`${lines.join(NEWLINE)}${NEWLINE}`);
}

function reportPoolError(error: Error): void {
  process.stderr.write(`${fatalLine(LOG_EVENTS.dbPoolError, error)}${NEWLINE}`);
}

async function migrateStatus(): Promise<number> {
  const db = createDatabase(loadSettings(process.env).database, reportPoolError);
  try {
    const migrations = await createMigrator(db).getMigrations();
    const names = (applied: boolean) =>
      migrations
        .filter((migration) => (migration.executedAt !== undefined) === applied)
        .map((migration) => `  ${migration.name}`);
    const listed = (applied: boolean) => {
      const lines = names(applied);
      return lines.length === 0 ? [SYSTEM_TEXT.noneListed] : lines;
    };
    printLines([
      SYSTEM_TEXT.appliedHeading,
      ...listed(true),
      SYSTEM_TEXT.pendingHeading,
      ...listed(false),
    ]);
    return EXIT_CODES.success;
  } finally {
    await db.destroy();
  }
}

async function createInvite(args: readonly string[]): Promise<number> {
  const parsed = parseCreateInviteArguments(args);
  if (parsed === null) {
    process.stderr.write(`${SYSTEM_TEXT.usage}${NEWLINE}`);
    return EXIT_CODES.usage;
  }
  const settings = loadSettings(process.env);
  const db = createDatabase(settings.database, reportPoolError);
  try {
    const service = createAuthService({
      db,
      settings,
      clock: systemClock,
      failedSignIns: createFailedSignInTracker({
        windowMs: settings.auth.failedSignInWindowMinutes * MINUTE_MS,
        maxEntries: FAILED_SIGN_IN_TRACKER_MAX_ENTRIES,
      }),
      logger: silentLogger,
      checkOpenSignUpRate: async () => true,
    });
    const { inviteUrl } = await service.createInvite(
      parsed.role,
      parsed.expiresInDays ?? settings.auth.inviteDefaultExpiryDays,
      null,
    );
    printLines([inviteUrl]);
    return EXIT_CODES.success;
  } finally {
    await db.destroy();
  }
}

async function applyBucketCorsCommand(): Promise<number> {
  const settings = loadSettings(process.env);
  try {
    const rules = await applyBucketCors(settings.storage, settings.server.publicBaseUrl);
    printLines([STORAGE_TEXT.corsApplied, JSON.stringify(rules, null, JSON_INDENT_SPACES)]);
    return EXIT_CODES.success;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`${STORAGE_TEXT.corsFailed(message)}${NEWLINE}`);
    return EXIT_CODES.failure;
  }
}

async function help(): Promise<number> {
  printLines([SYSTEM_TEXT.usage]);
  return EXIT_CODES.success;
}

const COMMANDS: Readonly<Record<string, Command>> = Object.freeze({
  'migrate-status': migrateStatus,
  'create-invite': createInvite,
  'apply-bucket-cors': applyBucketCorsCommand,
  help,
});

async function run(argv: readonly string[]): Promise<number> {
  const [name = '', ...args] = argv;
  const command = Object.hasOwn(COMMANDS, name) ? COMMANDS[name] : undefined;
  if (command === undefined) {
    process.stderr.write(
      `${SYSTEM_TEXT.unknownCommand(name)}${NEWLINE}${SYSTEM_TEXT.usage}${NEWLINE}`,
    );
    return EXIT_CODES.usage;
  }
  return command(args);
}

try {
  process.exitCode = await run(process.argv.slice(ARGV_COMMAND_START));
} catch (error) {
  if (!(error instanceof SettingsError)) throw error;
  process.stderr.write(`${error.message}${NEWLINE}`);
  process.exitCode = EXIT_CODES.failure;
}
