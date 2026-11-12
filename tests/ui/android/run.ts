import { spawn, type ChildProcess } from 'node:child_process';
import { closeSync, mkdirSync, openSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import {
  ADB_COMMAND,
  ADB_DEVICES_HEADER,
  ADB_DEVICE_STATE,
  ADB_TIMEOUT_MS,
  ANDROID_API_ENV_OVERRIDES,
  ANDROID_EMULATOR_HINT,
  ANDROID_FLOWS_SOURCE_DIRECTORY,
  ANDROID_PROJECT_NAME,
  ANDROID_REPORTS_DIRECTORY,
  ANDROID_WEB_ORIGIN,
  CLIENT_ANDROID_DIRECTORY,
  CLIENT_METRO_SCRIPT,
  CLIENT_PACKAGE,
  CLIENT_PREBUILD_SCRIPT,
  DEFAULT_SERVER_INPUT_NAME,
  ENV_NAMES,
  FLAG_ON,
  GRADLE_COMMAND,
  GRADLE_INSTALL_TASK,
  GRADLE_TIMEOUT_MS,
  JAVA_MIN_MAJOR,
  JAVA_VERSION_PATTERN,
  MAESTRO_HOME_DIRECTORY,
  MAESTRO_JUNIT_FORMAT,
  MAESTRO_RELATIVE_BINARY,
  MAESTRO_TIMEOUT_MS,
  MAESTRO_VERSION,
  METRO_LOG_FILE,
  METRO_READY_MS,
  METRO_RUNNING_STATUS,
  METRO_STATUS_URL,
  METRO_STOP_SIGNAL,
  READY_POLL_MS,
  REPORTS_DIRECTORY,
  BUILD_TIMEOUT_MS,
  CADDY_START,
  CADDY_STOP,
} from '../support/constants.ts';
import { fromRoot } from '../support/paths.ts';
import { run } from '../support/process.ts';
import { findFlowIds, parseSidecar, selectFlowIds, type Sidecar } from '../support/sidecars.ts';
import { startCaddy, startStack, stopCaddy, stopStack } from '../support/stack.ts';
import { ANDROID_TEXT } from '../support/text.ts';

const SOURCE_FLOWS_DIRECTORY = fromRoot(ANDROID_FLOWS_SOURCE_DIRECTORY);
const REQUESTED_ARGUMENTS_START = 2;

let metroProcess: ChildProcess | null = null;

function pause(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function adb(args: readonly string[]): Promise<string> {
  return run(ADB_COMMAND, args, { cwd: fromRoot(), timeoutMs: ADB_TIMEOUT_MS }).then(
    (result) => result.stdout,
  );
}

function maestroBinary(): string {
  return join(homedir(), MAESTRO_HOME_DIRECTORY, MAESTRO_VERSION, MAESTRO_RELATIVE_BINARY);
}

async function connectedDevices(): Promise<readonly string[]> {
  const output = await adb(['devices']);
  return output
    .split('\n')
    .filter((line) => line !== '' && !line.startsWith(ADB_DEVICES_HEADER))
    .filter((line) => line.trim().split(/\s+/)[1] === ADB_DEVICE_STATE);
}

async function javaMajor(): Promise<number | null> {
  try {
    const { stderr, stdout } = await run('java', ['-version'], {
      cwd: fromRoot(),
      timeoutMs: ADB_TIMEOUT_MS,
    });
    const match = JAVA_VERSION_PATTERN.exec(`${stderr}${stdout}`);
    return match === null ? null : Number(match[1]);
  } catch {
    return null;
  }
}

async function checkEnvironment(): Promise<readonly string[]> {
  const problems: string[] = [];
  try {
    const devices = await connectedDevices();
    if (devices.length === 0) problems.push(ANDROID_TEXT.noDevice(ANDROID_EMULATOR_HINT));
    if (devices.length > 1) problems.push(ANDROID_TEXT.manyDevices(ANDROID_EMULATOR_HINT));
  } catch {
    problems.push(ANDROID_TEXT.adbMissing);
  }
  try {
    await run(maestroBinary(), ['--version'], { cwd: fromRoot(), timeoutMs: ADB_TIMEOUT_MS });
  } catch {
    problems.push(ANDROID_TEXT.maestroMissing(maestroBinary()));
  }
  const major = await javaMajor();
  if (major === null || major < JAVA_MIN_MAJOR)
    problems.push(ANDROID_TEXT.javaMissing(JAVA_MIN_MAJOR));
  return problems;
}

async function buildApp(): Promise<void> {
  await run('pnpm', ['--filter', CLIENT_PACKAGE, CLIENT_PREBUILD_SCRIPT], {
    cwd: fromRoot(),
    timeoutMs: BUILD_TIMEOUT_MS,
  });
  await run(GRADLE_COMMAND, [GRADLE_INSTALL_TASK], {
    cwd: fromRoot(CLIENT_ANDROID_DIRECTORY),
    timeoutMs: GRADLE_TIMEOUT_MS,
  });
}

async function metroIsRunning(): Promise<boolean> {
  try {
    const response = await fetch(METRO_STATUS_URL);
    return (await response.text()) === METRO_RUNNING_STATUS;
  } catch {
    return false;
  }
}

async function startMetro(): Promise<void> {
  mkdirSync(fromRoot(REPORTS_DIRECTORY, ANDROID_REPORTS_DIRECTORY), { recursive: true });
  const log = openSync(fromRoot(REPORTS_DIRECTORY, ANDROID_REPORTS_DIRECTORY, METRO_LOG_FILE), 'w');
  metroProcess = spawn('pnpm', ['--filter', CLIENT_PACKAGE, CLIENT_METRO_SCRIPT], {
    cwd: fromRoot(),
    env: { ...process.env, [ENV_NAMES.nonInteractive]: FLAG_ON, [ENV_NAMES.uiTestMode]: FLAG_ON },
    stdio: ['ignore', log, log],
    detached: true,
  });
  closeSync(log);
  const deadline = Date.now() + METRO_READY_MS;
  while (Date.now() < deadline) {
    if (await metroIsRunning()) return;
    await pause(READY_POLL_MS);
  }
  throw new Error(ANDROID_TEXT.metroTimeout);
}

function stopMetro(): void {
  const child = metroProcess;
  metroProcess = null;
  if (child?.pid === undefined) return;
  try {
    process.kill(-child.pid, METRO_STOP_SIGNAL);
  } catch {
    child.kill(METRO_STOP_SIGNAL);
  }
}

async function loadSidecar(id: string): Promise<Sidecar> {
  const loaded: unknown = await import(`./flows/${id}.flow.ts`);
  return parseSidecar((loaded as { default: unknown }).default);
}

async function runPart(
  part: Sidecar['parts'][number],
  environment: Readonly<Record<string, string>>,
): Promise<void> {
  const variables = Object.entries(environment).flatMap(([name, value]) => [
    '-e',
    `${name}=${value}`,
  ]);
  await run(
    maestroBinary(),
    [
      'test',
      ...variables,
      join(SOURCE_FLOWS_DIRECTORY, part.file),
      '--format',
      MAESTRO_JUNIT_FORMAT,
      '--output',
      fromRoot(REPORTS_DIRECTORY, ANDROID_REPORTS_DIRECTORY, `${part.file}.xml`),
    ],
    { cwd: SOURCE_FLOWS_DIRECTORY, timeoutMs: MAESTRO_TIMEOUT_MS },
  );
  for (const adbArguments of part.adb ?? []) await adb(adbArguments);
  if (part.caddy === CADDY_STOP) await stopCaddy();
  if (part.caddy === CADDY_START) await startCaddy();
  if (part.waitAfterMs !== undefined) await pause(part.waitAfterMs);
}

async function runFlow(id: string): Promise<boolean> {
  const sidecar = await loadSidecar(id);
  const seeded = (await sidecar.seed?.(ANDROID_PROJECT_NAME)) ?? {};
  const environment = { [DEFAULT_SERVER_INPUT_NAME]: ANDROID_WEB_ORIGIN, ...seeded };
  for (const part of sidecar.parts) {
    try {
      await runPart(part, environment);
    } catch {
      process.stderr.write(`${ANDROID_TEXT.flowFailed(id, part.file)}\n`);
      await startCaddy().catch(() => undefined);
      return false;
    }
  }
  process.stdout.write(`${ANDROID_TEXT.flowPassed(id)}\n`);
  return true;
}

async function runFlows(requested: readonly string[]): Promise<readonly string[]> {
  const ids = selectFlowIds(await findFlowIds(SOURCE_FLOWS_DIRECTORY), requested);
  const failed: string[] = [];
  for (const id of ids) {
    if (!(await runFlow(id))) failed.push(id);
  }
  return failed;
}

async function main(requested: readonly string[]): Promise<number> {
  const problems = await checkEnvironment();
  if (problems.length > 0) {
    process.stderr.write(`${problems.join('\n')}\n`);
    return 1;
  }
  let failed: readonly string[] = [];
  try {
    await startStack({ build: false, apiEnvOverrides: ANDROID_API_ENV_OVERRIDES });
    if (process.env[ENV_NAMES.skipBuild] !== FLAG_ON) await buildApp();
    await startMetro();
    failed = await runFlows(requested);
  } finally {
    stopMetro();
    await stopStack();
  }
  process.stdout.write(
    `${failed.length === 0 ? ANDROID_TEXT.summaryPassed : ANDROID_TEXT.summaryFailed(failed)}\n`,
  );
  return failed.length === 0 ? 0 : 1;
}

process.once('SIGINT', () => {
  stopMetro();
  stopStack().finally(() => process.exit(1));
});

process.exitCode = await main(process.argv.slice(REQUESTED_ARGUMENTS_START));
