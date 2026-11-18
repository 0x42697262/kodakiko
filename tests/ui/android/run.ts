import { mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import {
  ADB_COMMAND,
  ADB_DEVICES_HEADER,
  ADB_DEVICE_STATE,
  ADB_INSTALL_ARGUMENTS,
  ADB_TIMEOUT_MS,
  ANDROID_API_ENV_OVERRIDES,
  ANDROID_DEBUG_APK,
  ANDROID_EMULATOR_HINT,
  ANDROID_FLOWS_SOURCE_DIRECTORY,
  ANDROID_PROJECT_DIRECTORY,
  ANDROID_PROJECT_NAME,
  ANDROID_REPORTS_DIRECTORY,
  ANDROID_WEB_ORIGIN,
  APP_ID,
  APP_ID_VARIABLE,
  APP_SETTLE_MS,
  CADDY_START,
  CADDY_STOP,
  DEFAULT_SERVER_INPUT_NAME,
  ENV_NAMES,
  FLAG_ON,
  GRADLE_BUILD_ARGUMENTS,
  GRADLE_COMMAND,
  GRADLE_TIMEOUT_MS,
  JAVA_COMMAND,
  JAVA_HOME_BINARY,
  JAVA_MIN_MAJOR,
  JAVA_VERSION_PATTERN,
  LEGACY_APP_ID,
  MAESTRO_HOME_DIRECTORY,
  MAESTRO_JUNIT_FORMAT,
  MAESTRO_OUTPUT_SUFFIX,
  MAESTRO_RELATIVE_BINARY,
  MAESTRO_TIMEOUT_MS,
  MAESTRO_VERSION,
  NICE_ARGUMENTS,
  NICE_COMMAND,
  REPORTS_DIRECTORY,
} from '../support/constants.ts';
import { fromRoot } from '../support/paths.ts';
import { run } from '../support/process.ts';
import { findFlowIds, parseSidecar, selectFlowIds, type Sidecar } from '../support/sidecars.ts';
import { startCaddy, startStack, stopCaddy, stopStack } from '../support/stack.ts';
import { ANDROID_TEXT } from '../support/text.ts';

const SOURCE_FLOWS_DIRECTORY = fromRoot(ANDROID_FLOWS_SOURCE_DIRECTORY);
const REQUESTED_ARGUMENTS_START = 2;

const APP_UNDER_TEST = process.env[ENV_NAMES.appId] ?? APP_ID;

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
    const javaHome = process.env[ENV_NAMES.javaHome];
    const java = javaHome === undefined ? JAVA_COMMAND : join(javaHome, JAVA_HOME_BINARY);
    const { stderr, stdout } = await run(java, ['-version'], {
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
  if (APP_UNDER_TEST === LEGACY_APP_ID) problems.push(ANDROID_TEXT.legacyAppId(LEGACY_APP_ID));
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
  await run(NICE_COMMAND, [...NICE_ARGUMENTS, GRADLE_COMMAND, ...GRADLE_BUILD_ARGUMENTS], {
    cwd: fromRoot(ANDROID_PROJECT_DIRECTORY),
    timeoutMs: GRADLE_TIMEOUT_MS,
  });
}

async function installApp(): Promise<void> {
  await adb([...ADB_INSTALL_ARGUMENTS, fromRoot(ANDROID_DEBUG_APK)]);
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
      '--test-output-dir',
      fromRoot(REPORTS_DIRECTORY, ANDROID_REPORTS_DIRECTORY, `${part.file}${MAESTRO_OUTPUT_SUFFIX}`),
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
  const environment = {
    [DEFAULT_SERVER_INPUT_NAME]: ANDROID_WEB_ORIGIN,
    ...seeded,
    [APP_ID_VARIABLE]: APP_UNDER_TEST,
  };
  await adb(['shell', 'am', 'force-stop', APP_UNDER_TEST]);
  await pause(APP_SETTLE_MS);
  for (const part of sidecar.parts) {
    try {
      await runPart(part, environment);
    } catch (error) {
      process.stderr.write(`${ANDROID_TEXT.flowFailed(id, part.file)}\n${String(error)}\n`);
      await startCaddy().catch(() => undefined);
      return false;
    }
  }
  process.stdout.write(`${ANDROID_TEXT.flowPassed(id)}\n`);
  return true;
}

async function runFlows(requested: readonly string[]): Promise<readonly string[]> {
  const ids = selectFlowIds(await findFlowIds(SOURCE_FLOWS_DIRECTORY), requested);
  mkdirSync(fromRoot(REPORTS_DIRECTORY, ANDROID_REPORTS_DIRECTORY), { recursive: true });
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
  if (process.env[ENV_NAMES.skipBuild] !== FLAG_ON) {
    await buildApp();
    await installApp();
  }
  try {
    await startStack({ build: false, apiEnvOverrides: ANDROID_API_ENV_OVERRIDES });
    failed = await runFlows(requested);
  } finally {
    await stopStack();
  }
  process.stdout.write(
    `${failed.length === 0 ? ANDROID_TEXT.summaryPassed : ANDROID_TEXT.summaryFailed(failed)}\n`,
  );
  return failed.length === 0 ? 0 : 1;
}

process.once('SIGINT', () => {
  stopStack().finally(() => process.exit(1));
});

process.exitCode = await main(process.argv.slice(REQUESTED_ARGUMENTS_START));
