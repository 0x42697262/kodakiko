import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const IDLE_PAST_TIMEOUT = '16:00';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-14 runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-14', testInfo.project.name as UiProject, 0) });
});

test('U-14 the web app locks itself after 15 minutes without input', async ({ page }) => {
  await page.clock.install();
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.capture')).toBeVisible();
  await page.clock.fastForward(IDLE_PAST_TIMEOUT);
  await expect(page.getByTestId('lock.title')).toBeVisible();
});
