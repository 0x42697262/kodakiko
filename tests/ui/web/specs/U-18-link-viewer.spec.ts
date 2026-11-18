import { readFile } from 'node:fs/promises';
import { encodeBytes } from '@kodakiko/contract';
import type { CDPSession, Locator, Page } from '@playwright/test';
import { expect, test } from '../../support/fixtures.ts';
import {
  fixtureBytes,
  revokePublicLink,
  seedLinkOwner,
  seedPublicLink,
  type LinkEntrySeed,
  type LinkMediaSeed,
  type LinkOwner,
  type SeededLink,
} from '../../support/link-seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const OPEN_WAIT_MS = 60_000;
const SEED_WAIT_MS = 180_000;
const SEEK_SECONDS = 10;
const TITLE = 'Picnic weekend';
const NOTE = 'Picnic behind the old mill <img src=x onerror=alert(1)>';
const UNKNOWN_LINK_ID = 'abcdefghijklmnopqrstuv';
const RELEASES_URL = 'https://github.com/0x42697262/kodakiko/releases';
const ITEMS = /^linkViewer\.item\.\d+$/;
const DAYS = /^linkViewer\.day\.\d+$/;
const PINCH_START_PX = 60;
const PINCH_END_PX = 240;
const ZOOM_GROWTH = 1.5;
const SWIPE_PX = 180;
const GESTURE_STEPS = 8;
const OWNER_INDEX_BASE = 1;

const IDS = Object.freeze({
  title: 'linkViewer.title',
  message: 'linkViewer.message',
  detail: 'linkViewer.detail',
  download: 'linkViewer.download',
  next: 'linkViewer.next',
  previous: 'linkViewer.previous',
  back: 'linkViewer.back',
  play: 'linkViewer.play',
  note: 'linkViewer.note',
  footer: 'linkViewer.footer',
  counter: 'linkViewer.counter',
  date: 'linkViewer.date',
  media: 'linkViewer.media',
  videoPlayer: 'video.player',
  getApp: 'getApp.screen',
  getAppDownload: 'getApp.download',
  item: (index: number) => `linkViewer.item.${index}`,
  day: (index: number) => `linkViewer.day.${index}`,
});

const TEXT = Object.freeze({
  footer: 'Shared with Kodakiko. End-to-end encrypted: the server never saw these photos.',
  incomplete: 'This link is incomplete. Ask for the full link.',
  invalid: "This link doesn't work. It may have been revoked or mistyped.",
  expired: 'This link has expired.',
  offline: "You're offline. Connect to the internet and open the link again.",
  getAppHeading: 'Get the Android app',
  newerDay: 'May 5, 2024',
  olderDay: 'May 3, 2024',
});

const PORTRAIT: LinkMediaSeed = { file: 'photo-portrait.jpg', kind: 'photo', mimeType: 'image/jpeg', width: 1200, height: 1600, durationMs: null };
const VIDEO: LinkMediaSeed = { file: 'video-20s.mp4', kind: 'video', mimeType: 'video/mp4', width: 1280, height: 720, durationMs: 20_000 };
const LANDSCAPE: LinkMediaSeed = { file: 'photo-gps-base.jpg', kind: 'photo', mimeType: 'image/jpeg', width: 2000, height: 1500, durationMs: null };

const OLDER: LinkEntrySeed = { takenAtUtc: '2024-05-03T18:30:00.000Z', utcOffsetMinutes: -300, note: '', media: [LANDSCAPE] };
const NEWER: LinkEntrySeed = { takenAtUtc: '2024-05-05T08:00:00.000Z', utcOffsetMinutes: 120, note: NOTE, media: [PORTRAIT, VIDEO] };

let owner: LinkOwner;
let link: SeededLink;
let seeded = 0;

test.use({ hasTouch: true, acceptDownloads: true });

test.beforeAll(async ({}, testInfo) => {
  testInfo.setTimeout(SEED_WAIT_MS);
  owner = await seedLinkOwner(uiUsername('U-18', testInfo.project.name as UiProject, OWNER_INDEX_BASE + testInfo.workerIndex));
  link = await seedPublicLink(owner, { title: TITLE, expiry: '7d', entries: [OLDER, NEWER] });
});

async function smallLink(expiry: '7d' | 'one_view'): Promise<SeededLink> {
  seeded += 1;
  return seedPublicLink(owner, { title: `${TITLE} ${seeded}`, expiry, entries: [{ ...OLDER, media: [PORTRAIT] }] });
}

async function openGrid(page: Page, url: string, count: number): Promise<void> {
  await page.goto(url);
  await expect(page.getByTestId(ITEMS)).toHaveCount(count, { timeout: OPEN_WAIT_MS });
}

async function openItem(page: Page, index: number): Promise<void> {
  await openGrid(page, link.url, 3);
  await page.getByTestId(IDS.item(index)).click();
  await expect(page.getByTestId(IDS.detail)).toBeVisible();
}

async function center(locator: Locator): Promise<{ readonly x: number; readonly y: number }> {
  const box = await locator.boundingBox();
  if (box === null) throw new Error('no bounding box');
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

async function touches(cdp: CDPSession, frames: readonly (readonly { readonly x: number; readonly y: number }[])[]): Promise<void> {
  const [first, ...rest] = frames;
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [...(first ?? [])] });
  for (const points of rest) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [...points] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
}

async function swipe(page: Page, dx: number): Promise<void> {
  const cdp = await page.context().newCDPSession(page);
  const start = await center(page.getByTestId(IDS.media));
  const frames = Array.from({ length: GESTURE_STEPS + 1 }, (_, step) => [{ x: start.x + (dx * step) / GESTURE_STEPS, y: start.y }]);
  await touches(cdp, frames);
  await cdp.detach();
}

async function pinch(page: Page, fromDistance: number, toDistance: number): Promise<void> {
  const cdp = await page.context().newCDPSession(page);
  const middle = await center(page.getByTestId(IDS.media));
  const frames = Array.from({ length: GESTURE_STEPS + 1 }, (_, step) => {
    const half = (fromDistance + ((toDistance - fromDistance) * step) / GESTURE_STEPS) / 2;
    return [
      { x: middle.x - half, y: middle.y },
      { x: middle.x + half, y: middle.y },
    ];
  });
  await touches(cdp, frames);
  await cdp.detach();
}

async function documentOrder(page: Page, ids: readonly string[]): Promise<boolean> {
  return page.evaluate((testIds) => {
    const nodes = testIds.map((id) => document.querySelector(`[data-testid="${id}"]`));
    if (nodes.some((node) => node === null)) return false;
    return nodes.every((node, index) => {
      const next = nodes[index + 1];
      return next === undefined || ((node as Node).compareDocumentPosition(next as Node) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    });
  }, ids);
}

function decodedImage(locator: Locator): Promise<number> {
  return locator.evaluate((element: HTMLImageElement) => (element.complete ? element.naturalWidth : 0));
}

test('U-18 a public link opens without an account: title, days, decrypted thumbnails, footer, and the secret never leaves the browser', async ({ page, context }) => {
  const secret = encodeBytes(link.secret);
  const leaks: string[] = [];
  context.on('request', (request) => {
    const url = new URL(request.url());
    url.hash = '';
    const visible = `${url.toString()} ${JSON.stringify(request.headers())} ${request.postData() ?? ''}`;
    if (visible.includes(secret)) leaks.push(request.url());
  });
  await openGrid(page, link.url, 3);
  await expect(page).not.toHaveURL(/#/);
  await expect(page.getByTestId(IDS.title)).toHaveText(TITLE);
  await expect(page).toHaveTitle(TITLE);
  await expect(page.getByTestId(DAYS)).toHaveCount(2);
  await expect(page.getByTestId(IDS.day(0))).toHaveText(TEXT.newerDay);
  await expect(page.getByTestId(IDS.day(1))).toHaveText(TEXT.olderDay);
  expect(await documentOrder(page, [IDS.day(0), IDS.item(0), IDS.item(1), IDS.day(1), IDS.item(2)])).toBe(true);
  for (let index = 0; index < 3; index += 1) {
    await expect.poll(() => decodedImage(page.getByTestId(IDS.item(index)).locator('img')), { timeout: OPEN_WAIT_MS }).toBeGreaterThan(0);
  }
  await expect(page.getByText(NOTE)).toHaveCount(0);
  await expect(page.getByTestId(IDS.footer)).toHaveText(TEXT.footer);
  expect(leaks).toEqual([]);
});

test('U-18 full screen shows the original with its date and note, and moves by swipe, arrow keys and buttons', async ({ page }) => {
  await openItem(page, 0);
  const viewport = page.viewportSize();
  const detailBox = await page.getByTestId(IDS.detail).boundingBox();
  expect(Math.round(detailBox?.width ?? 0)).toBeGreaterThanOrEqual((viewport?.width ?? 1) - 1);
  expect(Math.round(detailBox?.height ?? 0)).toBeGreaterThanOrEqual((viewport?.height ?? 1) - 1);
  await expect(page.getByTestId(IDS.counter)).toHaveText('1 of 3');
  await expect(page.getByTestId(IDS.date)).toHaveText(TEXT.newerDay);
  await expect(page.getByTestId(IDS.note)).toHaveText(NOTE);
  await expect(page.getByTestId(IDS.note).locator('img')).toHaveCount(0);
  const photo = page.getByTestId(IDS.media).locator('img');
  await expect.poll(() => decodedImage(photo), { timeout: OPEN_WAIT_MS }).toBe(PORTRAIT.width);
  await expect(page.getByTestId(IDS.previous)).toBeDisabled();
  await expect(page.getByTestId(IDS.next)).toBeEnabled();

  await swipe(page, -SWIPE_PX);
  await expect(page.getByTestId(IDS.counter)).toHaveText('2 of 3');
  await expect(page.getByTestId(IDS.play)).toBeVisible();

  await page.keyboard.press('ArrowRight');
  await expect(page.getByTestId(IDS.counter)).toHaveText('3 of 3');
  await expect(page.getByTestId(IDS.date)).toHaveText(TEXT.olderDay);
  await expect(page.getByTestId(IDS.note)).toHaveCount(0);
  await expect(page.getByTestId(IDS.next)).toBeDisabled();
  await expect.poll(() => decodedImage(page.getByTestId(IDS.media).locator('img')), { timeout: OPEN_WAIT_MS }).toBe(LANDSCAPE.width);

  await page.keyboard.press('ArrowRight');
  await expect(page.getByTestId(IDS.counter)).toHaveText('3 of 3');
  await swipe(page, SWIPE_PX);
  await expect(page.getByTestId(IDS.counter)).toHaveText('2 of 3');
  await page.keyboard.press('ArrowLeft');
  await expect(page.getByTestId(IDS.counter)).toHaveText('1 of 3');
  await page.getByTestId(IDS.next).click();
  await expect(page.getByTestId(IDS.counter)).toHaveText('2 of 3');
  await page.getByTestId(IDS.previous).click();
  await expect(page.getByTestId(IDS.counter)).toHaveText('1 of 3');

  await page.keyboard.press('Escape');
  await expect(page.getByTestId(IDS.detail)).toBeHidden();
  await expect(page.getByTestId(ITEMS)).toHaveCount(3);
  await page.getByTestId(IDS.item(2)).click();
  await expect(page.getByTestId(IDS.counter)).toHaveText('3 of 3');
  await page.getByTestId(IDS.back).click();
  await expect(page.getByTestId(IDS.detail)).toBeHidden();
  await expect(page.getByTestId(IDS.item(0))).toBeVisible();
});

test('U-18 pinch zoom enlarges the photo, and a drag while zoomed pans instead of changing the item', async ({ page }) => {
  await openItem(page, 0);
  const photo = page.getByTestId(IDS.media).locator('img');
  await expect.poll(() => decodedImage(photo), { timeout: OPEN_WAIT_MS }).toBe(PORTRAIT.width);
  const before = (await photo.boundingBox())?.width ?? 0;
  expect(before).toBeGreaterThan(0);
  await pinch(page, PINCH_START_PX, PINCH_END_PX);
  await expect.poll(async () => (await photo.boundingBox())?.width ?? 0).toBeGreaterThan(before * ZOOM_GROWTH);
  await swipe(page, -SWIPE_PX);
  await expect(page.getByTestId(IDS.counter)).toHaveText('1 of 3');
  expect((await photo.boundingBox())?.width ?? 0).toBeGreaterThan(before * ZOOM_GROWTH);
});

test('U-18 Download saves the decrypted original, named by its position', async ({ page }) => {
  await openItem(page, 0);
  const photoDownload = page.waitForEvent('download');
  await page.getByTestId(IDS.download).click();
  const photo = await photoDownload;
  expect(photo.suggestedFilename()).toBe('kodakiko-1.jpg');
  expect(new Uint8Array(await readFile((await photo.path()) ?? ''))).toEqual(await fixtureBytes(PORTRAIT.file));

  await page.getByTestId(IDS.next).click();
  await expect(page.getByTestId(IDS.counter)).toHaveText('2 of 3');
  const videoDownload = page.waitForEvent('download', { timeout: OPEN_WAIT_MS });
  await page.getByTestId(IDS.download).click();
  const video = await videoDownload;
  expect(video.suggestedFilename()).toBe('kodakiko-2.mp4');
  expect(new Uint8Array(await readFile((await video.path()) ?? ''))).toEqual(await fixtureBytes(VIDEO.file));
});

test('U-18 a video streams through the media service worker and plays from a seek', async ({ page }) => {
  await openItem(page, 1);
  await page.getByTestId(IDS.play).click();
  const video = page.getByTestId(IDS.videoPlayer).locator('video');
  await expect(video).toBeAttached({ timeout: OPEN_WAIT_MS });
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentSrc), { timeout: OPEN_WAIT_MS }).toContain('/__kdks_media/');
  expect(await page.evaluate(() => navigator.serviceWorker.controller?.scriptURL ?? '')).toMatch(/\/media-sw\.js$/);
  await video.evaluate((element: HTMLVideoElement, seconds) => {
    element.muted = true;
    element.currentTime = seconds;
    return element.play();
  }, SEEK_SECONDS);
  await expect
    .poll(() => video.evaluate((element: HTMLVideoElement, seconds) => element.currentTime >= seconds && !element.paused, SEEK_SECONDS), { timeout: OPEN_WAIT_MS })
    .toBe(true);
});

test('U-18 a revoked link says it does not work', async ({ page }) => {
  const revoked = await smallLink('7d');
  await revokePublicLink(owner, revoked.linkId);
  await page.goto(revoked.url);
  await expect(page.getByTestId(IDS.message)).toHaveText(TEXT.invalid, { timeout: OPEN_WAIT_MS });
  await expect(page.getByTestId(ITEMS)).toHaveCount(0);
});

test('U-18 a one-view link shows once and then says it has expired', async ({ page, context }) => {
  const once = await smallLink('one_view');
  await openGrid(page, once.url, 1);
  const again = await context.newPage();
  await again.goto(once.url);
  await expect(again.getByTestId(IDS.message)).toHaveText(TEXT.expired, { timeout: OPEN_WAIT_MS });
  await expect(again.getByTestId(ITEMS)).toHaveCount(0);
});

test('U-18 an unknown link id and a wrong key both say the link does not work', async ({ page }) => {
  const otherSecret = encodeBytes(Uint8Array.from({ length: 32 }, (_, index) => 255 - index));
  await page.goto(`/s/${UNKNOWN_LINK_ID}#${encodeBytes(link.secret)}`);
  await expect(page.getByTestId(IDS.message)).toHaveText(TEXT.invalid, { timeout: OPEN_WAIT_MS });
  await page.goto(`/s/${link.linkId}#${otherSecret}`);
  await expect(page.getByTestId(IDS.message)).toHaveText(TEXT.invalid, { timeout: OPEN_WAIT_MS });
  await expect(page.getByTestId(ITEMS)).toHaveCount(0);
});

test('U-18 a link without its full fragment says it is incomplete, without asking the server', async ({ page, context }) => {
  const opens: string[] = [];
  context.on('request', (request) => {
    if (request.url().includes('/api/')) opens.push(request.url());
  });
  await page.goto(`/s/${link.linkId}`);
  await expect(page.getByTestId(IDS.message)).toHaveText(TEXT.incomplete, { timeout: OPEN_WAIT_MS });
  const cut = encodeBytes(link.secret).slice(0, 20);
  const second = await context.newPage();
  await second.goto(`/s/${link.linkId}#${cut}`);
  await expect(second.getByTestId(IDS.message)).toHaveText(TEXT.incomplete, { timeout: OPEN_WAIT_MS });
  await expect(second).not.toHaveURL(/#/);
  expect(opens).toEqual([]);
});

test('U-18 a visitor without a connection is told to reconnect', async ({ page }) => {
  await page.route('**/api/v1/public-links/**', (route) => route.abort('internetdisconnected'));
  await page.goto(link.url);
  await expect(page.getByTestId(IDS.message)).toHaveText(TEXT.offline, { timeout: OPEN_WAIT_MS });
});

test('U-18 the site root is the Get the Android app page', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId(IDS.getApp)).toBeVisible();
  await expect(page.getByRole('heading', { name: TEXT.getAppHeading })).toBeVisible();
  await expect(page.getByTestId(IDS.getAppDownload)).toHaveAttribute('href', RELEASES_URL);
});
