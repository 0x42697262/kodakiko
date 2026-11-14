import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-38 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-38', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [{ takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'clip', tags: [], media: ['video-20s.mp4'] }]);
});

test('U-38 a video page autoplays muted and loops, and the setting turns it off', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  const video = page.getByTestId('viewer.inlineVideo.0').locator('video');
  await expect(video).toHaveCount(1, { timeout: SYNC_WAIT_MS });
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => !element.paused && element.muted && element.loop), { timeout: SYNC_WAIT_MS }).toBe(true);
  await page.getByTestId('viewer.inlineVideo.0.mute').click();
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.muted)).toBe(false);

  await page.getByTestId('viewer.back').click();
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.dataStorage').click();
  await page.getByTestId('settings.autoplay').click();
  await page.locator('[data-testid="settings.back"]:visible').click();
  await page.getByTestId('tabs.gallery').click();
  await page.getByTestId('gallery.tile.0').click();
  await expect(page.getByTestId('viewer.play')).toBeVisible();
  await expect(page.getByTestId('viewer.inlineVideo.0')).toHaveCount(0);
});
