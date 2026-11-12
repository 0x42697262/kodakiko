import { createDatabase } from './adapters/db/database.ts';
import { createMigrator } from './adapters/db/migrator.ts';
import { EXIT_CODES } from './config/constants.ts';
import { loadSettings } from './config/settings.ts';
import { SettingsError } from './domain/errors.ts';
import { fatalLine } from './platform/process.ts';
import { LOG_EVENTS } from './text/events.ts';
import { SYSTEM_TEXT } from './text/system.ts';

type Command = (args: readonly string[]) => Promise<number>;

const NEWLINE = '\n';
const ARGV_COMMAND_START = 2;

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

async function help(): Promise<number> {
  printLines([SYSTEM_TEXT.usage]);
  return EXIT_CODES.success;
}

const COMMANDS: Readonly<Record<string, Command>> = Object.freeze({
  'migrate-status': migrateStatus,
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
