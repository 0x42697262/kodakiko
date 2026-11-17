import {
  COMPOSE_UP_TIMEOUT_MS,
  INVITE_EXPIRES_DAYS,
  INVITE_FRAGMENT_PREFIX,
  INVITE_URL_PATTERN,
  SERVER_CLI,
  SERVER_ENV_FILE,
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
