import { defineConfig } from '@playwright/test';
import { WEB_EXPECT_TIMEOUT_MS, WEB_ORIGIN } from '../support/constants.ts';

const CAPTURE_TIMEOUT_MS = 600_000;

export default defineConfig({
  testDir: '.',
  testMatch: 'capture.spec.ts',
  globalSetup: '../web/global-setup.ts',
  globalTeardown: '../web/global-teardown.ts',
  workers: 1,
  timeout: CAPTURE_TIMEOUT_MS,
  expect: { timeout: WEB_EXPECT_TIMEOUT_MS },
  retries: 0,
  outputDir: '../reports/screenshots-results',
  reporter: [['list']],
  use: { baseURL: WEB_ORIGIN, serviceWorkers: 'allow' },
});
