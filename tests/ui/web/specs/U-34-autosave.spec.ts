import { expect, signInThroughUi, test } from '../../support/fixtures.ts';
import { seedAccount, seedEntries, type SeededAccount } from '../../support/seed.ts';
import { uiUsername, type UiProject } from '../../support/usernames.ts';

const SYNC_WAIT_MS = 60_000;

let account: SeededAccount;

test.beforeAll(async ({}, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'U-34 runs in the desktop project only.');
  account = await seedAccount({ username: uiUsername('U-34', testInfo.project.name as UiProject, 0) });
  await seedEntries(account, [
    { takenAtUtc: '2026-11-03T10:00:00.000Z', offsetMinutes: 0, note: 'First draft', tags: [], media: ['photo-gps.jpg'] },
  ]);
});

test('U-34 the edit page saves a note without a Save button, and the toolbar writes markdown', async ({ page }) => {
  await signInThroughUi(page, account);
  await expect(page.getByTestId('gallery.tile.0')).toBeVisible({ timeout: SYNC_WAIT_MS });
  await page.getByTestId('gallery.tile.0').click();
  await page.getByTestId('viewer.edit').click();
  const editor = page.getByTestId('edit.screen');
  await expect(editor.getByTestId('compose.note')).toHaveCount(0);
  await editor.getByTestId('edit.editNote').click();
  const note = editor.getByTestId('compose.note');
  await note.fill('Lake day ');
  await note.press('End');
  await editor.getByTestId('toolbar.bold').click();
  await note.pressSequentially('swim');
  await expect(note).toHaveValue('Lake day **swim**');
  await editor.getByTestId('compose.back').click();

  await expect(page.getByTestId('edit.screen')).toHaveCount(0);
  await page.getByTestId('viewer.edit').click();
  await expect(page.getByTestId('edit.screen').getByTestId('markdown.view')).toContainText('Lake day swim');
});
