import { SODIUM_READY_TIMEOUT_MS } from '../../support/constants.ts';
import { expect, test } from '../../support/fixtures.ts';

test('U-00 smoke: welcome screen, libsodium ready, config reachable', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/welcome$/);
  await expect(page.getByTestId('welcome.title')).toBeVisible();
  await page.waitForFunction(
    () => document.documentElement.dataset['sodiumReady'] === 'true',
    null,
    {
      timeout: SODIUM_READY_TIMEOUT_MS,
    },
  );
  const status = await page.evaluate(async () => (await fetch('/api/v1/config')).status);
  expect(status).toBe(200);
});
