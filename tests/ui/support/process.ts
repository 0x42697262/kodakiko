import { execFile } from 'node:child_process';
import { COMMAND_MAX_BUFFER_BYTES } from './constants.ts';
import { STACK_TEXT } from './text.ts';

export type RunOptions = {
  readonly cwd: string;
  readonly env?: NodeJS.ProcessEnv;
  readonly timeoutMs: number;
};

export type RunResult = { readonly stdout: string; readonly stderr: string };

export function run(
  command: string,
  args: readonly string[],
  options: RunOptions,
): Promise<RunResult> {
  const label = [command, ...args].join(' ');
  return new Promise((resolve, reject) => {
    execFile(
      command,
      [...args],
      {
        cwd: options.cwd,
        env: options.env ?? process.env,
        timeout: options.timeoutMs,
        maxBuffer: COMMAND_MAX_BUFFER_BYTES,
      },
      (error, stdout, stderr) => {
        if (error === null) {
          resolve({ stdout, stderr });
          return;
        }
        const timedOut = 'killed' in error && error.killed === true;
        reject(
          new Error(
            timedOut
              ? STACK_TEXT.commandTimedOut(label)
              : STACK_TEXT.commandFailed(label, stderr.trim()),
          ),
        );
      },
    );
  });
}
