import { mkdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { devices, type Browser, type BrowserContextOptions, type Page } from '@playwright/test';
import { buildTiff, insertExifIntoJpeg } from '../../../packages/core/test/media/builders.ts';
import { expect, signInThroughUi, test } from '../support/fixtures.ts';
import { run } from '../support/process.ts';
import {
  seedAccount,
  seedEntriesFromFiles,
  seedShare,
  type SeedFile,
  type SeedFileEntry,
  type SeededAccount,
} from '../support/seed.ts';

const HERE = fileURLToPath(new URL('./', import.meta.url));
const MEDIA_DIRECTORY = join(HERE, '../reports/demo-media');
const OUTPUT_DIRECTORY = join(HERE, '../../../docs/screenshots');
const GENERATOR = join(HERE, 'generate-demo-media.sh');
const GENERATE_TIMEOUT_MS = 120_000;
const SYNC_WAIT_MS = 60_000;
const SETTLE_MS = 1_500;
const TILES = /^gallery\.tile\.\d+$/;
const DESKTOP: BrowserContextOptions = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } };
const MOBILE: BrowserContextOptions = { ...devices['Pixel 7'] };

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

async function media(name: string): Promise<SeedFile> {
  return { bytes: new Uint8Array(await readFile(join(MEDIA_DIRECTORY, name))), fileName: name };
}

async function withLocation(file: SeedFile): Promise<SeedFile> {
  const tiff = buildTiff({
    dateTimeOriginal: '2026:11:12 17:40:00',
    offsetTimeOriginal: '+01:00',
    gps: { latitude: 46.5586, longitude: 7.8355 },
  });
  return { bytes: insertExifIntoJpeg(file.bytes, tiff), fileName: file.fileName };
}

async function adaEntries(): Promise<SeedFileEntry[]> {
  return [
    { takenAtUtc: '2026-11-12T16:40:00.000Z', offsetMinutes: 60, note: RIDGE_NOTE, tags: ['hiking', 'sunset'], files: [await withLocation(await media('sunset.jpg')), await media('mountains.jpg')] },
    { takenAtUtc: '2026-11-12T07:15:00.000Z', offsetMinutes: 60, note: 'Ferry across the bay. Calm water all the way.', tags: ['travel', 'sea'], files: [await media('sea.jpg'), await media('fractal.mp4')] },
    { takenAtUtc: '2026-11-09T13:05:00.000Z', offsetMinutes: 60, note: 'Sunday walk in the woods with the kids.', tags: ['walk', 'family'], files: [await media('forest.jpg'), await media('flowers.jpg')] },
    { takenAtUtc: '2026-11-05T21:30:00.000Z', offsetMinutes: 60, note: 'Clear sky and a full moon.', tags: ['night'], files: [await media('night.jpg')] },
    { takenAtUtc: '2026-11-01T10:00:00.000Z', offsetMinutes: 60, note: 'First snow on the peaks.', tags: ['hiking'], files: [await media('mountains.jpg')] },
    { takenAtUtc: '2025-11-13T15:00:00.000Z', offsetMinutes: 60, note: 'Last flowers of the season.', tags: ['garden'], files: [await media('flowers.jpg')] },
  ];
}

let ada: SeededAccount;

test.beforeAll(async () => {
  await run('sh', [GENERATOR, MEDIA_DIRECTORY], { cwd: HERE, timeoutMs: GENERATE_TIMEOUT_MS });
  await mkdir(OUTPUT_DIRECTORY, { recursive: true });
  ada = await seedAccount({ username: 'ada' });
  const ben = await seedAccount({ username: 'ben' });
  const created = await seedEntriesFromFiles(ada, await adaEntries());
  await seedShare(ada, ben, { name: 'Hiking trips', entryIds: [created[0]?.entryId ?? '', created[4]?.entryId ?? ''] });
  const benCreated = await seedEntriesFromFiles(ben, [
    { takenAtUtc: '2026-11-10T09:00:00.000Z', offsetMinutes: 60, note: 'Tulip bulbs are in.', tags: ['garden'], files: [await media('flowers.jpg')] },
  ]);
  await seedShare(ben, ada, { name: 'Garden diary', entryIds: benCreated.map((entry) => entry.entryId) });
});

async function shot(page: Page, name: string): Promise<void> {
  await page.waitForTimeout(SETTLE_MS);
  await page.screenshot({ path: join(OUTPUT_DIRECTORY, `${name}.png`) });
}

async function openSession(browser: Browser, options: BrowserContextOptions): Promise<Page> {
  const context = await browser.newContext({ ...options, baseURL: test.info().project.use.baseURL ?? '' });
  const page = await context.newPage();
  await signInThroughUi(page, ada);
  await expect(page.getByTestId(TILES).first()).toBeVisible({ timeout: SYNC_WAIT_MS });
  return page;
}

async function captureSet(browser: Browser, options: BrowserContextOptions, prefix: string): Promise<void> {
  const page = await openSession(browser, options);
  await shot(page, `${prefix}-gallery`);

  await page.getByTestId('gallery.tile.0').click();
  await expect(page.getByTestId('viewer.pageIndicator')).toBeVisible();
  await expect(page.getByTestId('markdown.view')).toBeVisible();
  await shot(page, `${prefix}-viewer`);
  await page.getByTestId('viewer.back').click();

  await page.getByTestId('tabs.calendar').click();
  await shot(page, `${prefix}-calendar`);

  await page.getByTestId('tabs.shared').click();
  await expect(page.getByTestId('shared.withMeRow.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await shot(page, `${prefix}-shared`);
  await page.getByTestId('tabs.gallery').click();

  if (prefix === 'web-mobile') {
    await page.getByTestId('gallery.capture').click();
    await page.getByTestId('capture.fileInput').setInputFiles([join(MEDIA_DIRECTORY, 'sea.jpg'), join(MEDIA_DIRECTORY, 'sunset.jpg')]);
    await expect(page.getByTestId('capture.trayItem.1')).toBeVisible({ timeout: SYNC_WAIT_MS });
    await page.getByTestId('capture.next').click();
    await page.getByTestId('compose.note').fill('Back at the harbor. Fish and chips by the water.');
    await page.getByTestId('tags.field').fill('travel');
    await page.getByTestId('tags.field').press('Enter');
    await shot(page, `${prefix}-compose`);
    await page.goBack();
    await page.goBack();

    await page.getByTestId('gallery.more').click();
    await page.getByTestId('lock.lockNow').click();
    await expect(page.getByTestId('lock.title')).toBeVisible();
    await shot(page, `${prefix}-lock`);
  }
  await page.context().close();
}

test('capture README screenshots', async ({ browser }) => {
  await captureSet(browser, MOBILE, 'web-mobile');
  await captureSet(browser, DESKTOP, 'web-desktop');
});
