import {
  addFiles,
  expect,
  openRouteInApp,
  signInThroughUi,
  test,
} from '../../support/fixtures.ts';
import { readServerJournal } from '../../support/server-state.ts';
import { seedAccount, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const CAPTURE_ROUTE = '/capture';
const SERVER_WAIT_MS = 60_000;
const INVALID_DATE = '2026-02-30';
const VALID_DATE = '2026-03-02';
const LATER_DATE = '2026-03-05';

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  account = await seedAccount({
    username: uiUsername('X-10-compose', testInfo.project.name as UiProject, 0),
  });
});

test('compose: note, tags, time and location, then Save shows Saved. and the entry reaches the server', async ({
  page,
}) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();
  await openRouteInApp(page, CAPTURE_ROUTE);
  await addFiles(page, ['photo-gps.jpg', 'photo-portrait.jpg']);
  await expect(page.getByTestId('capture.trayItem.1')).toBeVisible();

  await page.getByTestId('capture.next').click();
  await expect(page.getByTestId('compose.screen')).toBeVisible();
  await expect(page.getByTestId('edit.page.0')).toBeVisible();
  await expect(page.getByTestId('edit.page.1')).toBeAttached();
  await expect(page.getByTestId('location.coordinates')).toContainText('48.85840, 2.29450');
  await expect(page.getByTestId('location.source')).toHaveText('from photo');

  await page.getByTestId('compose.change').click();
  await page.getByTestId('timeEditor.date').fill(INVALID_DATE);
  await page.getByTestId('timeEditor.done').click();
  await expect(page.getByTestId('timeEditor.invalid')).toHaveText(
    'Use the format 2026-11-11 and 14:05.',
  );
  await page.getByTestId('timeEditor.date').fill(VALID_DATE);
  await page.getByTestId('timeEditor.done').click();
  await expect(page.getByTestId('timeEditor.invalid')).toBeHidden();
  await expect(page.getByTestId('compose.whenValue')).toContainText('Mar 2');

  await page.getByTestId('edit.addNote').click();
  await page.getByTestId('compose.note').fill('Picnic');
  await page.getByTestId('tags.choose').click();
  await page.getByTestId('tags.editTags').click();
  await page.getByTestId('tags.add').click();
  await page.getByTestId('tags.nameField').fill('Family');
  await page.getByTestId('tags.save').click();
  await page.getByTestId('tags.add').click();
  await page.getByTestId('tags.nameField').fill('lake');
  await page.getByTestId('tags.save').click();
  await page.locator('[data-testid="settings.back"]:visible').click();
  await page.getByTestId('tags.choose').click();
  await page.getByTestId('tags.option.family').click();
  await page.getByTestId('tags.option.lake').click();
  await page.getByTestId('tags.done').click();
  await expect(page.getByTestId('tags.chip.0')).toHaveText('family');
  await expect(page.getByTestId('tags.chip.1')).toHaveText('lake');
  await page.getByTestId('tags.remove.1').click();
  await expect(page.getByTestId('tags.chip.1')).toBeHidden();

  await page.getByTestId('compose.save').click();
  await expect(page.getByTestId('entry.saved')).toHaveText('Saved.');
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();

  await expect
    .poll(() => readServerJournal(account), { timeout: SERVER_WAIT_MS })
    .toEqual({ entryCount: 1, trashedCount: 0, mediaCount: 2, objectCount: 4 });
});

test('compose: Use original time resets, an edited time is kept after leaving (autosave, D-89), and Remove Location hides the row', async ({
  page,
}) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();
  await openRouteInApp(page, CAPTURE_ROUTE);
  await addFiles(page, ['photo-gps.jpg', 'photo-portrait.jpg']);
  await expect(page.getByTestId('capture.trayItem.1')).toBeVisible();
  await page.getByTestId('capture.next').click();
  const when = page.getByTestId('compose.whenValue');
  const computed = await when.innerText();

  await page.getByTestId('compose.change').click();
  await page.getByTestId('timeEditor.date').fill(VALID_DATE);
  await page.getByTestId('timeEditor.done').click();
  await expect(when).not.toHaveText(computed);
  await page.getByTestId('compose.change').click();
  await page.getByTestId('timeEditor.reset').click();
  await expect(when).toHaveText(computed);
  await expect(page.getByTestId('timeEditor.date')).toBeHidden();

  await page.getByTestId('compose.change').click();
  await page.getByTestId('timeEditor.date').fill(LATER_DATE);
  await page.getByTestId('timeEditor.done').click();
  await expect(when).not.toHaveText(computed);
  const edited = await when.innerText();

  await expect(page.getByTestId('location.coordinates')).toBeVisible();
  await page.getByTestId('location.remove').click();
  await expect(page.getByTestId('location.coordinates')).toBeHidden();

  await page.getByTestId('compose.back').click();
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  const reopened = page.getByTestId('edit.screen');
  await expect(reopened.getByTestId('compose.whenValue')).toHaveText(edited);
  await expect(reopened.getByTestId('location.coordinates')).toHaveCount(0);
});

test('compose: Back on a new entry keeps it (autosave, D-86/D-89) and returns to the gallery', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('home.signedInAs')).toBeVisible();
  await openRouteInApp(page, CAPTURE_ROUTE);
  await addFiles(page, ['screenshot.png']);
  await expect(page.getByTestId('capture.trayItem.0')).toBeVisible();

  await page.getByTestId('capture.next').click();
  await expect(page.getByTestId('compose.screen')).toBeVisible();
  await page.getByTestId('compose.back').click();

  await expect(page.getByTestId('capture.screen')).toHaveCount(0);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible();
});
