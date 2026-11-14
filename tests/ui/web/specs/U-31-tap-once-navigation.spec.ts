import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const TAPS = 5;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-31 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-31', testInfo.project.name as UiProject, 0) });
});

test('U-31 tapping a row many times quickly opens its page once', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.capture')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('tabs.more').click();
  const row = page.getByTestId('more.tags');
  await row.evaluate((element, taps) => {
    for (let tap = 0; tap < taps; tap += 1) (element as HTMLElement).click();
  }, TAPS);
  await expect(page.locator('[data-testid="tags.screen"]')).toHaveCount(1);
  await page.locator('[data-testid="settings.back"]:visible').click();
  await expect(page.getByTestId('more.screen')).toBeVisible();
  await expect(page.locator('[data-testid="tags.screen"]')).toHaveCount(0);
});
