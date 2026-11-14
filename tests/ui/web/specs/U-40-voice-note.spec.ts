import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const RECORD_MS = 2_000;

test.use({
  permissions: ['microphone'],
  launchOptions: { args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
});

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-40 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-40', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [{ takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'talk', tags: [], media: ['photo-gps.jpg'] }]);
});

test('U-40 a voice note recorded on the edit page is saved and plays in the viewer', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  const editor = page.getByTestId('edit.screen');
  await editor.getByTestId('edit.recordVoice').click();
  await expect(editor.getByTestId('voice.recordingTile')).toBeVisible();
  await page.waitForTimeout(RECORD_MS);
  await editor.getByTestId('voice.stop').click();
  await expect(editor.getByTestId('voice.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await expect(editor.getByTestId('voice.duration.0')).toHaveText(/^00:0[1-4]$/);
  await expect(editor.getByTestId('edit.dots')).toHaveCount(0);
  await editor.getByTestId('compose.back').click();

  const voice = page.getByTestId('voice.tile.0');
  await expect(voice).toBeVisible({ timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId('viewer.pageIndicator')).toHaveText('1 / 1');
  await page.getByTestId('voice.play.0').click();
  const audio = page.getByTestId('voice.play.0.audio');
  await expect(audio).toBeAttached({ timeout: SYNC_WAIT_MS });
  await expect.poll(() => audio.evaluate((element: HTMLAudioElement) => element.currentTime > 0 || element.ended), { timeout: SYNC_WAIT_MS }).toBe(true);
});
