import { spawn } from 'node:child_process';
import { context } from 'esbuild';
import { BUILD_OPTIONS } from './build-options.ts';

const ENV_FILE = '.env.dev';
const MAIN_FILE = 'dist/main.js';

const watcher = await context(BUILD_OPTIONS);
await watcher.rebuild();
await watcher.watch();

const child = spawn('node', [`--env-file=${ENV_FILE}`, '--watch', MAIN_FILE], {
  stdio: 'inherit',
});

async function stopAll(): Promise<void> {
  child.kill('SIGTERM');
  await watcher.dispose();
  process.exit(0);
}

process.once('SIGINT', stopAll);
process.once('SIGTERM', stopAll);
child.once('exit', (code) => {
  watcher.dispose().finally(() => process.exit(code ?? 0));
});
