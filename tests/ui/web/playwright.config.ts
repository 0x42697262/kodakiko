import { defineConfig, devices } from '@playwright/test';
import {
  WEB_EXPECT_TIMEOUT_MS,
  WEB_ORIGIN,
  WEB_REPORT_DIRECTORY,
  WEB_RESULTS_DIRECTORY,
  WEB_TEST_TIMEOUT_MS,
} from '../support/constants.ts';

export default defineConfig({
  testDir: './specs',
  globalSetup: './global-setup.ts',
  globalTeardown: './global-teardown.ts',
  workers: 1,
  timeout: WEB_TEST_TIMEOUT_MS,
  expect: { timeout: WEB_EXPECT_TIMEOUT_MS },
  retries: 0,
  outputDir: `../reports/${WEB_RESULTS_DIRECTORY}`,
  reporter: [
    ['list'],
    ['html', { outputFolder: `../reports/${WEB_REPORT_DIRECTORY}`, open: 'never' }],
  ],
  use: {
    baseURL: WEB_ORIGIN,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    serviceWorkers: 'allow',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
});
