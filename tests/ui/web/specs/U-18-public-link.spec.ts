import { readFile } from 'node:fs/promises';
import { byteSourceFromBytes, readImageMetadata } from '@kodakiko/core';
import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;
const LINK_WAIT_MS = 120_000;
const NOTE = 'Secret picnic spot behind the old mill';
const SEEK_SECONDS = 10;
const ITEMS = /^linkViewer\.item\.\d+$/;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-18 runs in the desktop project only (07c §8).');
  account = await seedAccount({ username: uiUsername('U-18', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    {
      takenAtUtc: '2026-05-05T08:00:00.000Z',
      offsetMinutes: 0,
      note: NOTE,
      tags: ['picnic'],
      media: ['photo-gps.jpg', 'video-gps.mov', 'video-20s.mp4'],
    },
  ]);
});

test('U-18 a public link shows only the selected media without notes or metadata', async ({ page, browser }) => {
  test.setTimeout(LINK_WAIT_MS * 3);
  await signInThroughUi(page, account);
  await page.getByTestId('gallery.tile.0').click({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('viewer.more').click();
  await page.getByTestId('viewer.share').click();
  await page.getByTestId('shareEntries.createLink').click();
  await expect(page.getByTestId('links.mediaToggle.2')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('links.mediaToggle.1').click();
  await page.getByTestId('links.includeNotes').click();
  await page.getByTestId('links.create').click();
  const urlText = page.getByTestId('links.url');
  await expect(urlText).toContainText('/s/', { timeout: LINK_WAIT_MS });
  const url = (await urlText.textContent())?.trim() ?? '';

  const visitor = await browser.newContext({ acceptDownloads: true });
  const viewer = await visitor.newPage();
  await viewer.goto(url);
  await expect(viewer.getByTestId(ITEMS)).toHaveCount(2, { timeout: SYNC_WAIT_MS });
  expect(viewer.url()).not.toContain('#');
  await expect(viewer.getByText(NOTE)).toHaveCount(0);

  await viewer.getByTestId('linkViewer.item.0').click();
  const downloading = viewer.waitForEvent('download');
  await viewer.getByTestId('linkViewer.download').click();
  const download = await downloading;
  const bytes = new Uint8Array(await readFile((await download.path()) ?? ''));
  const metadata = await readImageMetadata(byteSourceFromBytes(bytes), 'jpeg');
  expect(metadata.dateTimeOriginal).toBeNull();
  expect(metadata.gps).toBeNull();
  await expect(viewer.getByText(NOTE)).toHaveCount(0);

  await viewer.getByTestId('linkViewer.next').click();
  await viewer.getByTestId('linkViewer.play').click();
  const video = viewer.getByTestId('video.player').locator('video');
  await expect(video).toBeAttached({ timeout: SYNC_WAIT_MS });
  await video.evaluate((element: HTMLVideoElement, seconds) => {
    element.muted = true;
    element.currentTime = seconds;
    return element.play();
  }, SEEK_SECONDS);
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime >= 10 && !element.paused), { timeout: SYNC_WAIT_MS })
    .toBe(true);
  await visitor.close();
});
