import { EXIT_CODES, FATAL_LOG_LEVEL } from '../config/constants.ts';

const NEWLINE = '\n';

export function exitWith(code: number, message: string): never {
  process.stderr.write(`${message}${NEWLINE}`);
  process.exit(code);
}

export function fatalLine(event: string, error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return JSON.stringify({ level: FATAL_LOG_LEVEL, event, message });
}

export function onShutdownSignal(timeoutMs: number, shutdown: () => Promise<void>): void {
  let started = false;
  const handle = (): void => {
    if (started) return;
    started = true;
    setTimeout(() => process.exit(EXIT_CODES.failure), timeoutMs).unref();
    shutdown().then(
      () => process.exit(EXIT_CODES.success),
      () => process.exit(EXIT_CODES.failure),
    );
  };
  process.once('SIGTERM', handle);
  process.once('SIGINT', handle);
}
