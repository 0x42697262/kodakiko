import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { expect, test as base, type Page } from '@playwright/test';
import {
  CSP_CONSOLE_MARKER,
  FIXTURES_DIRECTORY,
  FIXTURE_MIME_TYPES,
  RECOVERY_CONFIRM_COUNT,
  RECOVERY_WORD_COUNT,
  SIGN_UP_WORDS_TIMEOUT_MS,
} from './constants.ts';
import type { SeededInvite } from './seed.ts';

export type Credentials = { readonly username: string; readonly password: string };

const CONFIRM_POSITION_PATTERN = /\d+/;
const NOT_FOUND = -1;

export const test = base.extend<{ pageGuard: void }>({
  pageGuard: [
    async ({ context }, use) => {
      const problems: string[] = [];
      const attach = (page: Page): void => {
        page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
        page.on('console', (message) => {
          if (message.text().includes(CSP_CONSOLE_MARKER)) {
            problems.push(`console: ${message.text()}`);
          }
        });
      };
      context.pages().forEach(attach);
      context.on('page', attach);
      await use();
      expect(problems).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

export async function signUpThroughUi(
  page: Page,
  invite: SeededInvite,
  credentials: Credentials,
): Promise<void> {
  await page.goto(`/invite#${invite.token}`);
  await page.getByTestId('signUp.username').fill(credentials.username);
  await page.getByTestId('signUp.password').fill(credentials.password);
  await page.getByTestId('signUp.confirm').fill(credentials.password);
  await page.getByTestId('signUp.submit').click();
}

export async function signInThroughUi(page: Page, account: Credentials): Promise<void> {
  await page.goto('/sign-in');
  await page.getByTestId('signIn.username').fill(account.username);
  await page.getByTestId('signIn.password').fill(account.password);
  await page.getByTestId('signIn.submit').click();
}

export async function unlockThroughUi(page: Page, password: string): Promise<void> {
  const field = page.getByTestId('lock.password');
  await field.fill(password);
  await field.press('Enter');
}

export async function openRouteInApp(page: Page, path: string): Promise<void> {
  await page.evaluate((target) => {
    window.history.pushState(null, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

export async function readRecoveryWords(page: Page): Promise<readonly string[]> {
  await expect(page.getByTestId('recoveryKey.word.1')).toBeVisible({
    timeout: SIGN_UP_WORDS_TIMEOUT_MS,
  });
  const words: string[] = [];
  for (let position = 1; position <= RECOVERY_WORD_COUNT; position += 1) {
    words.push((await page.getByTestId(`recoveryKey.word.${position}`).innerText()).trim());
  }
  return words;
}

export async function confirmRecoveryWords(page: Page, words: readonly string[]): Promise<void> {
  for (let index = 0; index < RECOVERY_CONFIRM_COUNT; index += 1) {
    const label = await page.getByTestId(`recoveryKey.confirmLabel.${index}`).innerText();
    const position = Number(CONFIRM_POSITION_PATTERN.exec(label)?.[0] ?? NOT_FOUND);
    await page.getByTestId(`recoveryKey.confirmField.${index}`).fill(words[position - 1] ?? '');
  }
}

async function fixtureBase64(name: string): Promise<string> {
  return (await readFile(join(FIXTURES_DIRECTORY, name))).toString('base64');
}

function fixtureMimeType(name: string): string {
  return FIXTURE_MIME_TYPES[extname(name)] ?? 'application/octet-stream';
}

export async function addFiles(page: Page, names: readonly string[]): Promise<void> {
  await page
    .getByTestId('capture.fileInput')
    .setInputFiles(names.map((name) => join(FIXTURES_DIRECTORY, name)));
}

type TransferEventKind = 'paste' | 'drop';

type TransferEventInput = {
  readonly kind: TransferEventKind;
  readonly selector: string | null;
  readonly name: string;
  readonly mimeType: string;
  readonly base64: string;
};

async function dispatchTransferEvent(page: Page, input: TransferEventInput): Promise<void> {
  await page.evaluate(({ kind, selector, name, mimeType, base64 }) => {
    const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
    const transfer = new DataTransfer();
    transfer.items.add(new File([bytes], name, { type: mimeType }));
    const target =
      selector === null
        ? (document.activeElement ?? document.body)
        : document.querySelector(selector);
    if (target === null) throw new Error(`No element matches ${selector}`);
    const event =
      kind === 'paste'
        ? new ClipboardEvent('paste', { clipboardData: transfer, bubbles: true, cancelable: true })
        : new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
  }, input);
}

export async function pasteFile(page: Page, name: string): Promise<void> {
  await dispatchTransferEvent(page, {
    kind: 'paste',
    selector: null,
    name,
    mimeType: fixtureMimeType(name),
    base64: await fixtureBase64(name),
  });
}

export async function dropFile(page: Page, name: string): Promise<void> {
  await dispatchTransferEvent(page, {
    kind: 'drop',
    selector: '[data-testid="capture.dropZone"]',
    name,
    mimeType: fixtureMimeType(name),
    base64: await fixtureBase64(name),
  });
}
