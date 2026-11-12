import { spawn, type ChildProcess } from 'node:child_process';
import { closeSync, mkdirSync, openSync } from 'node:fs';
import {
  API_PORT,
  BUILD_TIMEOUT_MS,
  CLIENT_BUILD_SCRIPT,
  CLIENT_PACKAGE,
  COMPOSE_FILE,
  COMPOSE_UP_TIMEOUT_MS,
  ENV_NAMES,
  HOST_FIREWALL_HINT,
  KILL_GRACE_MS,
  READY_POLL_MS,
  READY_REQUEST_TIMEOUT_MS,
  READY_URL,
  REPORTS_DIRECTORY,
  SERVER_CLI,
  SERVER_ENV_FILE,
  SERVER_LOG_FILE,
  SERVER_MAIN,
  SERVER_PACKAGE,
  STACK_PROJECT,
  UI_STACK_READY_MS,
} from './constants.ts';
import { bootstrapGarage, type GarageKey } from './garage.ts';
import { fromRoot } from './paths.ts';
import { run } from './process.ts';
import { STACK_TEXT } from './text.ts';

const COMPOSE_BASE = ['compose', '-p', STACK_PROJECT, '-f', COMPOSE_FILE];
const API_READY_URL = `http://127.0.0.1:${API_PORT}/health/ready`;
const HTTP_OK = 200;

let apiProcess: ChildProcess | null = null;
let apiExit: string | null = null;

function pause(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function serverEnvironment(): NodeJS.ProcessEnv {
  const accessKeyId = process.env[ENV_NAMES.s3AccessKeyId];
  const secretAccessKey = process.env[ENV_NAMES.s3SecretAccessKey];
  if (accessKeyId === undefined || secretAccessKey === undefined) {
    throw new Error(STACK_TEXT.s3KeysMissing);
  }
  return {
    ...process.env,
    [ENV_NAMES.serverS3AccessKeyId]: accessKeyId,
    [ENV_NAMES.serverS3SecretAccessKey]: secretAccessKey,
  };
}

export function exportGarageKey(key: GarageKey): void {
  process.env[ENV_NAMES.s3AccessKeyId] = key.accessKeyId;
  process.env[ENV_NAMES.s3SecretAccessKey] = key.secretAccessKey;
}

async function compose(args: readonly string[], timeoutMs: number): Promise<void> {
  await run('docker', [...COMPOSE_BASE, ...args], { cwd: fromRoot(), timeoutMs });
}

async function pnpm(args: readonly string[]): Promise<void> {
  await run('pnpm', args, { cwd: fromRoot(), timeoutMs: BUILD_TIMEOUT_MS });
}

async function answers(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(READY_REQUEST_TIMEOUT_MS) });
    return response.status === HTTP_OK;
  } catch {
    return false;
  }
}

async function runCli(args: readonly string[]): Promise<void> {
  await run('node', [`--env-file=${SERVER_ENV_FILE}`, SERVER_CLI, ...args], {
    cwd: fromRoot(),
    env: serverEnvironment(),
    timeoutMs: COMPOSE_UP_TIMEOUT_MS,
  });
}

function spawnApi(): void {
  mkdirSync(fromRoot(REPORTS_DIRECTORY), { recursive: true });
  const log = openSync(fromRoot(REPORTS_DIRECTORY, SERVER_LOG_FILE), 'w');
  apiExit = null;
  apiProcess = spawn('node', [`--env-file=${SERVER_ENV_FILE}`, SERVER_MAIN], {
    cwd: fromRoot(),
    env: serverEnvironment(),
    stdio: ['ignore', log, log],
  });
  closeSync(log);
  apiProcess.on('exit', (code, signal) => {
    apiExit = String(code ?? signal);
  });
}

async function waitUntilReady(): Promise<void> {
  const deadline = Date.now() + UI_STACK_READY_MS;
  while (Date.now() < deadline) {
    if (apiExit !== null) throw new Error(STACK_TEXT.apiExited(apiExit));
    if (await answers(READY_URL)) return;
    await pause(READY_POLL_MS);
  }
  throw new Error(STACK_TEXT.notReady(READY_URL, HOST_FIREWALL_HINT, await answers(API_READY_URL)));
}

export async function startStack({ build }: { readonly build: boolean }): Promise<void> {
  try {
    await pnpm(['--filter', SERVER_PACKAGE, 'build']);
    if (build) await pnpm(['--filter', CLIENT_PACKAGE, CLIENT_BUILD_SCRIPT]);
    await compose(['down', '-v'], COMPOSE_UP_TIMEOUT_MS);
    await compose(['up', '-d', '--wait'], COMPOSE_UP_TIMEOUT_MS);
    exportGarageKey(await bootstrapGarage());
    await runCli(['apply-bucket-cors']);
    spawnApi();
    await waitUntilReady();
  } catch (error) {
    await stopStack();
    throw error;
  }
}

function waitForExit(child: ChildProcess): Promise<void> {
  return new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve();
      return;
    }
    child.once('exit', () => resolve());
  });
}

async function stopApi(): Promise<void> {
  const child = apiProcess;
  apiProcess = null;
  if (child === null) return;
  child.kill('SIGTERM');
  const timer = setTimeout(() => child.kill('SIGKILL'), KILL_GRACE_MS);
  await waitForExit(child);
  clearTimeout(timer);
}

export async function stopStack(): Promise<void> {
  await stopApi();
  await compose(['down', '-v'], COMPOSE_UP_TIMEOUT_MS);
}
