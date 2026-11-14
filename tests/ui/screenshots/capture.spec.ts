import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { devices, type Browser, type BrowserContextOptions, type Page } from '@playwright/test';
import { buildTiff, insertExifIntoJpeg } from '../../../packages/core/test/media/builders.ts';
import { expect, signInThroughUi, test } from '../support/fixtures.ts';
import { run } from '../support/process.ts';
import { seedAccount, seedShare, type SeededAccount } from '../support/seed.ts';

const HERE = fileURLToPath(new URL('./', import.meta.url));
const MEDIA_DIRECTORY = join(HERE, '../reports/demo-media');
const DATED_DIRECTORY = join(MEDIA_DIRECTORY, 'dated');
const OUTPUT_DIRECTORY = join(HERE, '../../../docs/screenshots');
const GENERATOR = join(HERE, 'generate-demo-media.sh');
const GENERATE_TIMEOUT_MS = 120_000;
const SYNC_WAIT_MS = 90_000;
const SETTLE_MS = 1_500;
const OFFSET = '+01:00';
const TILES = /^gallery\.tile\.\d+$/;
const PENDING = /^gallery\.tilePending\.\d+$/;
const VISIBLE_CAPTURE = '[data-testid="gallery.capture"]:visible';
const DESKTOP: BrowserContextOptions = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } };
const MOBILE: BrowserContextOptions = { ...devices['Pixel 7'] };
const RIDGE = { latitude: 46.5586, longitude: 7.8355 };

const RIDGE_NOTE = [
  '# Evening at the ridge',
  '',
  'We made it up just before **sunset**. The light turned everything *purple*.',
  '',
  '- packed tea and two sandwiches',
  '- [x] found the old trail marker',
  '- [ ] remember gloves next time',
  '',
  '> Start an hour earlier in winter.',
].join('\n');

type DemoPhoto = {
  readonly source: string;
  readonly target: string;
  readonly takenAt: string;
  readonly location?: { readonly latitude: number; readonly longitude: number };
};

type DemoEntry = { readonly files: readonly string[]; readonly note: string; readonly tags: readonly string[] };

const PHOTOS: readonly DemoPhoto[] = [
  { source: 'sunset.jpg', target: 'ridge-sunset.jpg', takenAt: '2026:11:12 17:40:00', location: RIDGE },
  { source: 'mountains.jpg', target: 'ridge-peaks.jpg', takenAt: '2026:11:12 17:52:00', location: RIDGE },
  { source: 'sea.jpg', target: 'ferry.jpg', takenAt: '2026:11:12 08:15:00' },
  { source: 'forest.jpg', target: 'woods.jpg', takenAt: '2026:11:09 14:05:00' },
  { source: 'flowers.jpg', target: 'meadow.jpg', takenAt: '2026:11:09 14:20:00' },
  { source: 'night.jpg', target: 'moon.jpg', takenAt: '2026:11:05 22:30:00' },
  { source: 'mountains.jpg', target: 'first-snow.jpg', takenAt: '2026:11:01 11:00:00' },
  { source: 'flowers.jpg', target: 'last-flowers.jpg', takenAt: '2025:11:13 16:00:00' },
  { source: 'flowers.jpg', target: 'tulips.jpg', takenAt: '2026:11:10 10:00:00' },
];

const ADA_ENTRIES: readonly DemoEntry[] = [
  { files: ['last-flowers.jpg'], note: 'Last flowers of the season.', tags: ['garden'] },
  { files: ['first-snow.jpg'], note: 'First snow on the peaks.', tags: ['hiking'] },
  { files: ['moon.jpg'], note: 'Clear sky and a full moon.', tags: ['night'] },
  { files: ['woods.jpg', 'meadow.jpg'], note: 'Sunday walk in the woods with the kids.', tags: ['walk', 'family'] },
  { files: ['ferry.jpg', 'fractal.mp4'], note: 'Ferry across the bay. Calm water all the way.', tags: ['travel', 'sea'] },
  { files: ['ridge-sunset.jpg', 'ridge-peaks.jpg'], note: RIDGE_NOTE, tags: ['hiking', 'sunset'] },
];

const BEN_ENTRIES: readonly DemoEntry[] = [{ files: ['tulips.jpg'], note: 'Tulip bulbs are in.', tags: ['garden'] }];

let ada: SeededAccount;

async function prepareMedia(): Promise<void> {
  await run('sh', [GENERATOR, MEDIA_DIRECTORY], { cwd: HERE, timeoutMs: GENERATE_TIMEOUT_MS });
  await mkdir(DATED_DIRECTORY, { recursive: true });
  for (const photo of PHOTOS) {
    const tiff = buildTiff({
      dateTimeOriginal: photo.takenAt,
      offsetTimeOriginal: OFFSET,
      ...(photo.location === undefined ? {} : { gps: photo.location }),
    });
    const bytes = new Uint8Array(await readFile(join(MEDIA_DIRECTORY, photo.source)));
    await writeFile(join(DATED_DIRECTORY, photo.target), insertExifIntoJpeg(bytes, tiff));
  }
  await copyFile(join(MEDIA_DIRECTORY, 'fractal.mp4'), join(DATED_DIRECTORY, 'fractal.mp4'));
}

async function createTags(page: Page, tags: readonly string[]): Promise<void> {
  await page.getByTestId('tabs.more').click();
  await page.getByTestId('more.tags').click();
  for (const tag of tags) {
    await page.getByTestId('tags.add').click();
    await page.getByTestId('tags.nameField').fill(tag);
    await page.getByTestId('tags.save').click();
  }
  await page.locator('[data-testid="settings.back"]:visible').click();
  await page.getByTestId('tabs.gallery').click();
}

async function createThroughUi(page: Page, entry: DemoEntry): Promise<void> {
  await page.locator(VISIBLE_CAPTURE).click();
  await page.getByTestId('capture.fileInput').setInputFiles(entry.files.map((file) => join(DATED_DIRECTORY, file)));
  await expect(page.getByTestId(`capture.trayItem.${entry.files.length - 1}`)).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('capture.next').click();
  await page.getByTestId('compose.note').fill(entry.note);
  if (entry.tags.length > 0) {
    await page.getByTestId('tags.choose').click();
    for (const tag of entry.tags) await page.getByTestId(`tags.option.${tag}`).click();
    await page.getByTestId('tags.done').click();
  }
  await page.getByTestId('compose.save').click();
  await expect(page.getByTestId('compose.note')).toHaveCount(0, { timeout: SYNC_WAIT_MS });
  await expect(page.locator(VISIBLE_CAPTURE)).toHaveCount(1, { timeout: SYNC_WAIT_MS });
}

async function author(browser: Browser, account: SeededAccount, entries: readonly DemoEntry[]): Promise<void> {
  const context = await browser.newContext(DESKTOP);
  const page = await context.newPage();
  await signInThroughUi(page, account);
  await expect(page.locator(VISIBLE_CAPTURE)).toHaveCount(1, { timeout: SYNC_WAIT_MS });
  await createTags(page, [...new Set([...entries.flatMap((entry) => entry.tags), 'travel'])]);
  for (const entry of entries) await createThroughUi(page, entry);
  await expect(page.getByTestId(TILES)).toHaveCount(entries.length, { timeout: SYNC_WAIT_MS });
  await expect(page.getByTestId(PENDING)).toHaveCount(0, { timeout: SYNC_WAIT_MS });
  await context.close();
}

test.beforeAll(async ({ browser }) => {
  await prepareMedia();
  await mkdir(OUTPUT_DIRECTORY, { recursive: true });
  ada = await seedAccount({ username: 'ada' });
  const ben = await seedAccount({ username: 'ben' });
  await author(browser, ada, ADA_ENTRIES);
  await author(browser, ben, BEN_ENTRIES);
  await seedShare(ada, ben, { name: 'Hiking trips', tag: 'hiking' });
  await seedShare(ben, ada, { name: 'Garden diary' });
});

async function imagesLoaded(page: Page, prefix: string): Promise<void> {
  await page.waitForFunction(
    (tilePrefix) => {
      const tiles = [...document.querySelectorAll(`[data-testid^="${tilePrefix}"]`)];
      return (
        tiles.length > 0 &&
        tiles.every((tile) => {
          const image = tile.querySelector('img');
          return image !== null && image.complete && image.naturalWidth > 0;
        })
      );
    },
    prefix,
    { timeout: SYNC_WAIT_MS },
  );
}

async function shot(page: Page, name: string): Promise<void> {
  await page.waitForTimeout(SETTLE_MS);
  await page.screenshot({ path: join(OUTPUT_DIRECTORY, `${name}.png`) });
}

async function captureSet(browser: Browser, options: BrowserContextOptions, prefix: string): Promise<void> {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  await signInThroughUi(page, ada);
  await expect(page.getByTestId(TILES).first()).toBeVisible({ timeout: SYNC_WAIT_MS });
  await imagesLoaded(page, 'gallery.tile.');
  await shot(page, `${prefix}-gallery`);

  await page.getByTestId('gallery.tile.0').click();
  await expect(page.getByTestId('markdown.view')).toBeVisible({ timeout: SYNC_WAIT_MS });
  if (prefix === 'web-mobile') await shot(page, `${prefix}-viewer`);
  await page.getByTestId('markdown.view').scrollIntoViewIfNeeded();
  await shot(page, `${prefix}-viewer-note`);
  await page.getByTestId('viewer.back').click();

  await page.getByTestId('tabs.calendar').click();
  await shot(page, `${prefix}-calendar`);

  await page.getByTestId('tabs.shared').click();
  await expect(page.getByTestId('shared.withMeRow.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await shot(page, `${prefix}-shared`);
  await page.getByTestId('tabs.gallery').click();

  if (prefix === 'web-mobile') {
    await page.getByTestId('gallery.capture').click();
    await page
      .getByTestId('capture.fileInput')
      .setInputFiles([join(DATED_DIRECTORY, 'ferry.jpg'), join(DATED_DIRECTORY, 'ridge-sunset.jpg')]);
    await expect(page.getByTestId('capture.trayItem.1')).toBeVisible({ timeout: SYNC_WAIT_MS });
    await page.getByTestId('capture.next').click();
    await page.getByTestId('compose.note').fill('Back at the harbor. Fish and chips by the water.');
    await page.getByTestId('tags.choose').click();
    await page.getByTestId('tags.option.travel').click();
    await page.getByTestId('tags.done').click();
    await shot(page, `${prefix}-compose`);
    await page.goBack();
    await page.getByTestId('capture.close').click();
    await page.getByTestId('capture.discard').click();

    await page.getByTestId('gallery.more').click();
    await page.getByTestId('lock.lockNow').click();
    await expect(page.getByTestId('lock.title')).toBeVisible();
    await shot(page, `${prefix}-lock`);
  }
  await context.close();
}

test('capture README screenshots', async ({ browser }) => {
  await captureSet(browser, MOBILE, 'web-mobile');
  await captureSet(browser, DESKTOP, 'web-desktop');
});
