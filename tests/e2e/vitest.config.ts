import { defineConfig } from 'vitest/config';

const TEST_TIMEOUT_MS = 180_000;
const HOOK_TIMEOUT_MS = 120_000;

export default defineConfig({
  test: {
    environment: 'node',
    include: ['behaviors/**/*.test.ts', 'scenarios/**/*.test.ts'],
    globalSetup: ['support/global-setup.ts'],
    fileParallelism: false,
    testTimeout: TEST_TIMEOUT_MS,
    hookTimeout: HOOK_TIMEOUT_MS,
  },
});
