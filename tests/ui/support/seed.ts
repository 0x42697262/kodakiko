import sodiumLibrary from 'libsodium-wrappers-sumo';
import type { Sodium } from '@kodakiko/core';
import { createTestAuthContext, createWrappersSodium } from '@kodakiko/client/testing';
import {
  BEARER_PREFIX,
  HTTP_ACCEPTED,
  UI_TEST_PASSWORD,
  USERS_ME_DELETE_PATH,
  WEB_ORIGIN,
  INVITE_EXPIRES_DAYS,
  INVITE_FRAGMENT_PREFIX,
  INVITE_URL_PATTERN,
  SERVER_CLI,
  SERVER_ENV_FILE,
  COMPOSE_UP_TIMEOUT_MS,
} from './constants.ts';
import { fromRoot } from './paths.ts';
import { run } from './process.ts';
import { serverEnvironment } from './stack.ts';
import { STACK_TEXT } from './text.ts';

export type InviteRole = 'member' | 'admin';

export type SeededInvite = { readonly url: string; readonly token: string };

export async function seedInvite(role: InviteRole): Promise<SeededInvite> {
  const { stdout } = await run(
    'node',
    [
      `--env-file=${SERVER_ENV_FILE}`,
      SERVER_CLI,
      'create-invite',
      '--role',
      role,
      '--expires-days',
      INVITE_EXPIRES_DAYS,
    ],
    { cwd: fromRoot(), env: serverEnvironment(), timeoutMs: COMPOSE_UP_TIMEOUT_MS },
  );
  const url = INVITE_URL_PATTERN.exec(stdout)?.[0];
  if (url === undefined) throw new Error(STACK_TEXT.inviteUrlMissing);
  const token = new URL(url).hash.slice(INVITE_FRAGMENT_PREFIX.length);
  return { url, token };
}

export type SeedAccountOptions = {
  readonly username: string;
  readonly password?: string;
  readonly role?: InviteRole;
};

export type SeededAccount = {
  readonly username: string;
  readonly password: string;
  readonly recoveryWords: readonly string[];
  readonly sessionToken: string;
};

async function createNodeSodium(): Promise<Sodium> {
  await sodiumLibrary.ready;
  return createWrappersSodium(sodiumLibrary, async (input) =>
    sodiumLibrary.crypto_pwhash(
      input.outputLength,
      input.password,
      input.salt,
      input.opsLimit,
      input.memLimitBytes,
      sodiumLibrary.crypto_pwhash_ALG_ARGON2ID13,
    ),
  );
}

export async function seedAccount(options: SeedAccountOptions): Promise<SeededAccount> {
  const password = options.password ?? UI_TEST_PASSWORD;
  const invite = await seedInvite(options.role ?? 'member');
  const context = createTestAuthContext({
    baseUrl: WEB_ORIGIN,
    fetcher: fetch,
    sodium: await createNodeSodium(),
  });
  const { recoveryWords } = await context.auth.signUp({
    username: options.username,
    password,
    inviteToken: invite.token,
    keepSignedIn: false,
  });
  const accountKey = context.activeAccountKey.get();
  const sessionToken = accountKey === null ? null : await context.sessionStore.load(accountKey);
  if (sessionToken === null) throw new Error(STACK_TEXT.sessionTokenMissing);
  return { username: options.username, password, recoveryWords, sessionToken };
}

export async function seedDeletionRequest(account: SeededAccount): Promise<void> {
  const response = await fetch(`${WEB_ORIGIN}${USERS_ME_DELETE_PATH}`, {
    method: 'POST',
    headers: { Authorization: `${BEARER_PREFIX}${account.sessionToken}` },
  });
  if (response.status !== HTTP_ACCEPTED) {
    throw new Error(STACK_TEXT.deletionRequestFailed(response.status));
  }
}
