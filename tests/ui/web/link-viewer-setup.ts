import {
  BUILD_TIMEOUT_MS,
  ENV_NAMES,
  FLAG_ON,
  LINK_VIEWER_BUILD_SCRIPT,
  LINK_VIEWER_CADDYFILE,
  LINK_VIEWER_PACKAGE,
  LINK_VIEWER_WEB_ROOT,
} from '../support/constants.ts';
import { fromRoot } from '../support/paths.ts';
import { run } from '../support/process.ts';
import { startStack } from '../support/stack.ts';

export default async function linkViewerSetup(): Promise<void> {
  process.env[ENV_NAMES.uiCaddyfile] = LINK_VIEWER_CADDYFILE;
  process.env[ENV_NAMES.uiWebRoot] = LINK_VIEWER_WEB_ROOT;
  if (process.env[ENV_NAMES.skipBuild] !== FLAG_ON) {
    await run('pnpm', ['--filter', LINK_VIEWER_PACKAGE, LINK_VIEWER_BUILD_SCRIPT], { cwd: fromRoot(), timeoutMs: BUILD_TIMEOUT_MS });
  }
  await startStack({ build: false });
}
