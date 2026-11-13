import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from '@playwright/test';
import { WEB_ORIGIN } from '../../support/constants.ts';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const IDLE_PAST_TIMEOUT = '16:00';
const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-16 runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-16', testInfo.project.name as UiProject, 0) });
});

test('U-16 keep me unlocked survives idle time and a browser restart; Lock Now turns it off', async () => {
  const profile = await mkdtemp(join(tmpdir(), 'kodakiko-u16-'));
  try {
    let context = await chromium.launchPersistentContext(profile, { baseURL: WEB_ORIGIN });
    let page = context.pages()[0] ?? (await context.newPage());
    await page.clock.install();
    await signInThroughUi(page, account);
    await expect(page.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
    await page.getByTestId('gallery.settings').click();
    await page.getByTestId('settings.row.security').click();
    await page.getByTestId('settings.keepUnlocked').click();
    await expect(page.getByTestId('settings.keepUnlocked')).toHaveAttribute('aria-checked', 'true');
    await page.clock.fastForward(IDLE_PAST_TIMEOUT);
    await expect(page.getByTestId('lock.title')).toHaveCount(0);
    await context.close();

    context = await chromium.launchPersistentContext(profile, { baseURL: WEB_ORIGIN });
    page = context.pages()[0] ?? (await context.newPage());
    await page.goto('/');
    await expect(page.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
    await page.getByTestId('gallery.more').click();
    await page.getByTestId('lock.lockNow').click();
    await expect(page.getByTestId('lock.title')).toBeVisible();
    await context.close();
  } finally {
    await rm(profile, { recursive: true, force: true });
  }
});
