import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const SEEK_SECONDS = 15;
const SEEK_WAIT_MS = 10_000;
const BUCKET_ORIGIN = 'http://localhost:9581';
const BUCKET_DELAY_MS = 250;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-13 runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-13', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-05-02T08:00:00.000Z', offsetMinutes: 0, note: '', tags: [], media: ['video-20s.mp4'] },
  ]);
});

test('U-13 a video streams through the media worker and seeks without a full download', async ({ page, context }) => {
  let bucketBytes = 0;
  let objectSize = 0;
  context.on('response', (response) => {
    if (!response.url().startsWith(BUCKET_ORIGIN)) return;
    const length = Number(response.headers()['content-length'] ?? 0);
    bucketBytes += length;
    const total = /\/(\d+)$/.exec(response.headers()['content-range'] ?? '');
    if (total !== null) objectSize = Math.max(objectSize, Number(total[1]));
  });
  await context.route(`${BUCKET_ORIGIN}/**`, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, BUCKET_DELAY_MS));
    await route.continue();
  });
  await signInThroughUi(page, account);
  await page.getByTestId('gallery.tile.0').click({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('viewer.play').click();
  const video = page.getByTestId('video.player').locator('video');
  await expect(video).toBeAttached({ timeout: SYNC_WAIT_MS });
  await video.evaluate((element: HTMLVideoElement, seconds) => {
    element.muted = true;
    element.currentTime = seconds;
    return element.play();
  }, SEEK_SECONDS);
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime >= 15 && !element.paused), {
      timeout: SEEK_WAIT_MS,
    })
    .toBe(true);
  expect(objectSize).toBeGreaterThan(0);
  expect(bucketBytes).toBeLessThan(objectSize);
});
