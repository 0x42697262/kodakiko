import { ENV_NAMES, FLAG_ON } from '../support/constants.ts';
import { startStack } from '../support/stack.ts';

export default async function globalSetup(): Promise<void> {
  await startStack({ build: process.env[ENV_NAMES.skipBuild] !== FLAG_ON });
}
